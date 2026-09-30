package com.dlchm.dlc.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable, human-readable session storage.
 *
 * <p>Only the conversation transcript is stored. Provider credentials and
 * application configuration are deliberately not written here. Each message
 * is a single-line JSON record inside a Markdown document so that the file is
 * useful to a person and can still be parsed without relying on model APIs.</p>
 */
public class MarkdownSessionStore {
    private static final Logger log = LoggerFactory.getLogger(MarkdownSessionStore.class);
    private static final String HEADER = "---";
    private static final String JSON_START = "```json";
    private static final String JSON_END = "```";
    private static final String TASK_STATE_KEY = "task_state:";

    private final Path sessionsDirectory;
    private final ObjectMapper mapper;

    public MarkdownSessionStore(SandboxPathResolver paths) {
        this(paths.getWorkspaceRoot().resolve(".dlc").resolve("sessions"), new ObjectMapper());
    }

    MarkdownSessionStore(Path sessionsDirectory, ObjectMapper mapper) {
        this.sessionsDirectory = sessionsDirectory.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public Path getSessionsDirectory() {
        return sessionsDirectory;
    }

    /** Load a transcript. A damaged file is ignored rather than crashing startup. */
    public List<Message> load(String sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.isRegularFile(file)) return List.of();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (!JSON_START.equals(lines.get(i).trim()) || i + 2 >= lines.size()) continue;
                String json = lines.get(++i).trim();
                if (!JSON_END.equals(lines.get(++i).trim())) continue;
                try {
                    PersistedMessage record = mapper.readValue(json, PersistedMessage.class);
                    Message message = restore(record);
                    if (message != null) messages.add(message);
                } catch (Exception parseError) {
                    log.warn("Skipping invalid message in session {}: {}", sessionId,
                            parseError.getClass().getSimpleName());
                }
            }
            return messages;
        } catch (IOException e) {
            log.warn("Cannot load session {}: {}", sessionId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Load the cross-turn task state. Falls back to an empty state when absent. */
    public TaskState loadTaskState(String sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.isRegularFile(file)) return new TaskState();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.startsWith(TASK_STATE_KEY)) continue;
                return TaskState.fromJson(line.substring(TASK_STATE_KEY.length()).trim());
            }
        } catch (IOException e) {
            log.debug("Cannot read task state for {}: {}", sessionId, e.getClass().getSimpleName());
        }
        return new TaskState();
    }

    /** Persist the current in-memory transcript atomically. */
    public void save(Session session) {
        Objects.requireNonNull(session, "session");
        List<Message> messages = session.getHistory();
        StringBuilder markdown = new StringBuilder(512);
        markdown.append(HEADER).append('\n')
                .append("session_id: ").append(safeHeader(session.getId())).append('\n')
                .append("channel: ").append(safeHeader(session.getChannelType())).append('\n')
                .append("user: ").append(safeHeader(session.getUserId())).append('\n')
                .append("created_at: ").append(session.getCreatedAt()).append('\n')
                .append("updated_at: ").append(Instant.now()).append('\n')
                .append("message_count: ").append(messages.size()).append('\n')
                .append("task_state: ").append(safeHeader(session.getTaskState().toJson())).append('\n')
                .append(HEADER).append("\n\n")
                .append("# Polar session ").append(session.getId()).append("\n\n")
                .append("<!-- This file is generated by Polar. It contains transcript data only; "
                        + "credentials are never persisted here. -->\n\n");
        int index = 0;
        for (Message message : messages) {
            PersistedMessage record = toRecord(message);
            if (record == null) continue;
            String role = record.role();
            markdown.append("## Message ").append(++index).append(" · ").append(role).append("\n\n")
                    .append(JSON_START).append('\n');
            try {
                markdown.append(mapper.writeValueAsString(record));
            } catch (Exception e) {
                log.warn("Cannot serialize message in session {}: {}", session.getId(),
                        e.getClass().getSimpleName());
                continue;
            }
            markdown.append('\n').append(JSON_END).append("\n\n");
        }
        try {
            Files.createDirectories(sessionsDirectory);
            Path target = fileFor(session.getId());
            Path temporary = Files.createTempFile(sessionsDirectory, target.getFileName().toString(), ".tmp");
            Files.writeString(temporary, markdown, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Cannot persist session {}: {}", session.getId(), e.getClass().getSimpleName());
        }
    }

    public List<SessionFile> list() {
        if (!Files.isDirectory(sessionsDirectory)) return List.of();
        List<SessionFile> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDirectory, "*.md")) {
            for (Path file : stream) {
                try {
                    String id = readHeader(file, "session_id").orElseGet(() -> decodeFileId(file));
                    String updated = readHeader(file, "updated_at").orElse("");
                    result.add(new SessionFile(id, updated, file));
                } catch (Exception ignored) {
                    // A partially written file should not hide the other sessions.
                }
            }
        } catch (IOException e) {
            log.debug("Cannot list sessions: {}", e.getClass().getSimpleName());
        }
        result.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return Collections.unmodifiableList(result);
    }

    public Optional<Path> findFile(String sessionId) {
        Path file = fileFor(sessionId);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    private Path fileFor(String sessionId) {
        String value = sessionId == null ? "unknown" : sessionId;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return sessionsDirectory.resolve(encoded + ".md").normalize();
    }

    private String decodeFileId(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".md")) name = name.substring(0, name.length() - 3);
        try {
            return new String(Base64.getUrlDecoder().decode(name), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    private Optional<String> readHeader(Path file, String key) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith(key + ":")) return Optional.of(line.substring(key.length() + 1).trim());
            if (line.equals(HEADER) && !key.equals("session_id")) {
                // Continue: all metadata is in the first front-matter block.
            }
        }
        return Optional.empty();
    }

    private PersistedMessage toRecord(Message message) {
        if (message instanceof UserMessage user) {
            return new PersistedMessage("user", redact(user.getText()), List.of(), List.of());
        }
        if (message instanceof AssistantMessage assistant) {
            List<PersistedToolCall> calls = assistant.getToolCalls().stream()
                    .map(c -> new PersistedToolCall(c.id(), c.type(), c.name(), redact(c.arguments()))).toList();
            return new PersistedMessage("assistant", redact(assistant.getText()), calls, List.of());
        }
        if (message instanceof ToolResponseMessage tools) {
            List<PersistedToolResponse> responses = tools.getResponses().stream()
                    .map(r -> new PersistedToolResponse(r.id(), r.name(), redact(r.responseData()))).toList();
            return new PersistedMessage("tool", "", List.of(), responses);
        }
        return null;
    }

    private Message restore(PersistedMessage record) {
        if (record == null || record.role() == null) return null;
        return switch (record.role().toLowerCase(Locale.ROOT)) {
            case "user" -> new UserMessage(record.text() == null ? "" : record.text());
            case "assistant" -> {
                List<AssistantMessage.ToolCall> calls = (record.toolCalls() == null ? List.<PersistedToolCall>of()
                        : record.toolCalls()).stream()
                        .map(c -> new AssistantMessage.ToolCall(c.id(), c.type(), c.name(), c.arguments())).toList();
                yield AssistantMessage.builder()
                        .content(record.text() == null ? "" : record.text())
                        .toolCalls(calls)
                        .build();
            }
            case "tool" -> {
                List<ToolResponseMessage.ToolResponse> responses = (record.toolResponses() == null
                        ? List.<PersistedToolResponse>of() : record.toolResponses()).stream()
                        .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.responseData())).toList();
                yield ToolResponseMessage.builder().responses(responses).build();
            }
            default -> null;
        };
    }

    private String safeHeader(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private String redact(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?i)(api[_-]?key|access[_-]?token|secret|password|authorization)\\s*([:=])\\s*([^\\s,;]+)",
                        "$1$2[REDACTED]")
                .replaceAll("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("\\b(?:sk|ghp|github_pat)-[A-Za-z0-9_=-]{16,}", "[REDACTED]");
    }

    public record SessionFile(String id, String updatedAt, Path path) {}
    public record PersistedMessage(String role, String text, List<PersistedToolCall> toolCalls,
                                   List<PersistedToolResponse> toolResponses) {}
    public record PersistedToolCall(String id, String type, String name, String arguments) {}
    public record PersistedToolResponse(String id, String name, String responseData) {}
}
