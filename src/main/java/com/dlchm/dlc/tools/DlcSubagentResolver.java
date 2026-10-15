package com.dlchm.dlc.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentResolver;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

/** Resolves the demo-style Markdown frontmatter used to define DLC specialist agents. */
public final class DlcSubagentResolver implements SubagentResolver {
    public static final String KIND = "DLC";
    private static final int MAX_DEFINITION_CHARS = 16_000;
    private final ResourceLoader resources = new DefaultResourceLoader();

    @Override
    public boolean canResolve(SubagentReference reference) {
        return reference != null && KIND.equals(reference.kind());
    }

    @Override
    public DlcSubagentDefinition resolve(SubagentReference reference) {
        if (!canResolve(reference)) {
            throw new IllegalArgumentException("Unsupported DLC subagent reference: " + reference);
        }
        try {
            Resource resource = resources.getResource(reference.uri());
            String markdown;
            try (var input = resource.getInputStream()) {
                markdown = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
            }
            if (markdown.length() > MAX_DEFINITION_CHARS) {
                throw new IllegalArgumentException("Subagent definition exceeds " + MAX_DEFINITION_CHARS + " chars: "
                        + reference.uri());
            }
            return parse(reference, markdown);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load subagent definition " + reference.uri(), e);
        }
    }

    private static DlcSubagentDefinition parse(SubagentReference reference, String markdown) {
        String normalized = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        if (!normalized.startsWith("---")) {
            throw new IllegalArgumentException("Subagent Markdown must start with YAML frontmatter: "
                    + reference.uri());
        }
        int frontmatterEnd = normalized.indexOf("\n---", 3);
        if (frontmatterEnd < 0) {
            throw new IllegalArgumentException("Subagent Markdown has unterminated frontmatter: "
                    + reference.uri());
        }

        String name = null;
        String description = null;
        String frontmatter = normalized.substring(3, frontmatterEnd);
        for (String line : frontmatter.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator < 0) continue;
            String key = line.substring(0, separator).trim();
            String value = unquote(line.substring(separator + 1).trim());
            if ("name".equals(key)) name = value;
            if ("description".equals(key)) description = value;
        }
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("Subagent frontmatter requires name and description: "
                    + reference.uri());
        }
        String instructions = normalized.substring(frontmatterEnd + 4).strip();
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("Subagent instructions are empty: " + reference.uri());
        }
        return new DlcSubagentDefinition(name, description, instructions, reference);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
