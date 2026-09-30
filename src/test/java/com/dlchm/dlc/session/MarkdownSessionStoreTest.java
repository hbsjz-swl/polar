package com.dlchm.dlc.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class MarkdownSessionStoreTest {
    @TempDir
    Path temp;

    @Test
    void roundTripsHumanReadableTranscriptAndToolProtocol() {
        MarkdownSessionStore store = new MarkdownSessionStore(temp.resolve("sessions"), new ObjectMapper());
        Session session = new Session("test/session", "test", "local");
        session.replaceHistory(List.of(
                new UserMessage("inspect ``` markdown"),
                AssistantMessage.builder().content("I will inspect it")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "read_file", "{\"path\":\"a\"}")))
                        .build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "read_file", "result"))).build()));

        store.save(session);
        List<Message> restored = store.load(session.getId());

        assertEquals(3, restored.size());
        assertEquals("inspect ``` markdown", restored.get(0).getText());
        assertTrue(((AssistantMessage) restored.get(1)).hasToolCalls());
        assertEquals("result", ((ToolResponseMessage) restored.get(2)).getResponses().get(0).responseData());
        assertTrue(store.findFile(session.getId()).isPresent());
    }

    @Test
    void redactsCommonCredentialShapesBeforeWriting() throws Exception {
        MarkdownSessionStore store = new MarkdownSessionStore(temp.resolve("sessions"), new ObjectMapper());
        Session session = new Session("redact", "test", "local");
        session.replaceHistory(List.of(new UserMessage("api-key=super-secret-token")));
        store.save(session);
        String markdown = java.nio.file.Files.readString(store.findFile("redact").orElseThrow());
        assertTrue(markdown.contains("api-key=[REDACTED]"));
        assertTrue(!markdown.contains("super-secret-token"));
    }

    @Test
    void persistsCrossTurnTaskStateAlongsideTheTranscript() {
        MarkdownSessionStore store = new MarkdownSessionStore(temp.resolve("sessions"), new ObjectMapper());
        Session session = new Session("task-state", "test", "local");
        session.getTaskState().observeUserMessage("用工具查实时价格，不要自己猜价格");
        session.getTaskState().recordToolResult("browser_view", "¥600");
        store.save(session);

        TaskState restored = store.loadTaskState("task-state");

        assertTrue(restored.strictFacts());
        assertTrue(restored.hasFact("600"));
    }

    @Test
    void missingTaskStateYieldsAnEmptyState() {
        MarkdownSessionStore store = new MarkdownSessionStore(temp.resolve("sessions"), new ObjectMapper());
        assertEquals("", store.loadTaskState("never-written").render());
    }
}
