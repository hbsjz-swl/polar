package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The completion marker replaces reading the model's prose for a stall.
 *
 * <p>The old heuristic — an "asking permission" phrase paired with a "next step" phrase
 * — mis-fired in both directions: it re-drove a turn that had finished and politely
 * asked a follow-up, and it stayed silent when the same stall was phrased unusually.
 * An explicit declaration is checkable; a guess about wording is not.</p>
 */
class GoalCompletionTest {

    @Test void readsAnExplicitDoneDeclaration() {
        assertEquals(GoalCompletion.Verdict.DONE, GoalCompletion.verdict(
                "已为你查得两地往返票价如下……\n\n[GOAL_DONE]"));
    }

    @Test void readsAnExplicitBlockedDeclaration() {
        assertEquals(GoalCompletion.Verdict.BLOCKED, GoalCompletion.verdict(
                "当前页面被验证码遮挡，需要你本人在浏览器窗口完成滑块验证。\n[GOAL_BLOCKED]"));
    }

    @Test void noMarkerMeansNoVerdict() {
        assertEquals(GoalCompletion.Verdict.UNSTATED, GoalCompletion.verdict("还在查，请看下一步"));
        assertEquals(GoalCompletion.Verdict.UNSTATED, GoalCompletion.verdict(""));
        assertEquals(GoalCompletion.Verdict.UNSTATED, GoalCompletion.verdict(null));
    }

    /**
     * A turn can state both what it finished and what it could not; the blocking reason
     * is the actionable half, so it wins.
     */
    @Test void blockedWinsWhenBothMarkersAppear() {
        assertEquals(GoalCompletion.Verdict.BLOCKED, GoalCompletion.verdict(
                "已取得 10/02 与 10/03 两天的票价。\n[GOAL_DONE]\n但 10/05 返程被验证码挡住。\n[GOAL_BLOCKED]"));
    }

    @Test void markersAreStrippedFromWhatTheUserReads() {
        String stripped = GoalCompletion.strip("结论如下。\n\n[GOAL_DONE]");
        assertEquals("结论如下。\n\n", stripped);
        assertFalse(stripped.contains("GOAL_DONE"), "protocol markers must not reach the user");
    }

    @Test void strippingAlsoCollapsesTheGapTheMarkerLeftBehind() {
        // A marker on its own line leaves a blank gap; 3+ newlines collapse to one
        // break so the answer does not end in a ragged hole.
        String stripped = GoalCompletion.strip("结论。\n\n[GOAL_DONE]\n\n");
        assertFalse(stripped.contains("\n\n\n"), "no ragged blank lines where the marker was");
    }

    @Test void strippingNullIsSafe() {
        assertNull(GoalCompletion.strip(null));
        assertEquals("plain", GoalCompletion.strip("plain"));
    }

    @Test void theInstructionNamesBothMarkers() {
        String instruction = GoalCompletion.instruction();
        assertTrue(instruction.contains("[GOAL_DONE]"));
        assertTrue(instruction.contains("[GOAL_BLOCKED]"));
        assertTrue(instruction.contains("不要向用户解释它们的含义"),
                "the model must not narrate the protocol to the user");
        assertTrue(instruction.contains("调用工具的轮次不要输出这两个标记"),
                "markers belong to the final prose turn only");
    }
}