package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The "unknown tool" message a malformed tool call receives.
 *
 * <p>A bare rejection leaves the model nothing to act on. A real run produced the
 * name {@code bash_execute\n<parameter=command} — a parameter tag folded into the name
 * by a streaming parser — and the model could not tell a formatting slip from a
 * missing capability, so it guessed again.</p>
 */
class UnknownToolMessageTest {

    private static final Set<String> TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "glob_search", "grep_search",
            "bash_execute", "browser_start", "browser_view", "browser_action",
            "delegate_task");

    @Test void namesEveryToolActuallyAvailable() {
        String message = AgentLoop.unknownTool("nope", TOOLS);

        for (String tool : TOOLS) {
            assertTrue(message.contains(tool), () -> "message must offer " + tool);
        }
    }

    @Test void tellsTheModelHowToCallCorrectly() {
        String message = AgentLoop.unknownTool("nope", TOOLS);

        assertTrue(message.contains("exact name"),
                "the model has to be told to call by the name as given");
        assertTrue(message.contains("arguments"),
                "the leaked-tag case needs to be redirected to the arguments object");
    }

    @Test void quotesTheOffendingNameVerbatim() {
        // Newlines and angle brackets are exactly what made this hard to recognise;
        // trimming or escaping them would hide the evidence.
        String leaked = "bash_execute\n<parameter=command";

        String message = AgentLoop.unknownTool(leaked, TOOLS);

        assertTrue(message.contains(leaked), "the bad name must come back unchanged");
    }

    @Test void sortedSoTheListIsStableAcrossRuns() {
        // Same set, different iteration order. The guidance must not change between
        // turns, or the model sees a different "available tools" every time it errs.
        String message = AgentLoop.unknownTool("same", TOOLS);
        String reordered = AgentLoop.unknownTool("same", Set.of(
                "delegate_task", "bash_execute", "write_file", "read_file",
                "glob_search", "edit_file", "grep_search", "browser_view",
                "browser_action", "browser_start"));

        assertTrue(message.equals(reordered));
    }

    @Test void emptyToolSetDoesNotProduceADanglingColon() {
        // A subagent with everything denied still gets a readable message.
        String message = AgentLoop.unknownTool("browser_action", Set.of());

        assertTrue(message.contains("No tools are available"));
        assertFalse(message.contains("exact name: "),
                "an empty list must not be announced as a list");
    }

    @Test void doesNotAdvertiseToolsHiddenFromThisTurn() {
        // A subagent's denied prefixes must not be listed: the message comes from the
        // same map the dispatch uses, so it cannot promise a tool that would be refused.
        Set<String> subagentTools = Set.of("read_file", "grep_search");

        String message = AgentLoop.unknownTool("bash_execute", subagentTools);

        assertFalse(message.contains("bash_execute:"),
                "a denied tool must not be offered as an option");
        assertTrue(message.contains("read_file"));
    }

    @Test void staysShortEnoughToNotWasteContext() {
        // Nine tools is the realistic ceiling; the message must not become a paragraph.
        List<String> many = List.of("read_file", "write_file", "edit_file", "glob_search",
                "grep_search", "bash_execute", "browser_start", "browser_view",
                "browser_action");

        String message = AgentLoop.unknownTool("typo", Set.copyOf(many));

        assertTrue(message.length() < 400,
                () -> "message was " + message.length() + " chars, too long to act on");
    }
}