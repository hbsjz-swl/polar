package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The goal contract is what keeps a 30-round loop pointed at the user's request.
 *
 * <p>Before it existed, the goal lived only in the first user message: history replay
 * preserves that, but the context compressor rewrites {@code messages[1..]} wholesale,
 * so the goal was gone after the first compaction — precisely when a long task starts
 * drifting toward whatever the last tool result mentioned.</p>
 */
class GoalContractTest {

    private static final String GOAL = "对比石家庄往返厦门的机票，10月2日去10月5日回";

    @Test void rendersTheOriginalRequestAndTheSuccessCriteria() {
        String rendered = new GoalContract(GOAL, 30).render();
        assertTrue(rendered.contains(GOAL), "the user's words must survive verbatim");
        assertTrue(rendered.contains("成功判定契约"));
        assertTrue(rendered.contains("不得修改、弱化、缩小或替换原始目标"),
                "the contract must forbid narrowing the goal, not merely encourage it");
        assertTrue(rendered.contains("不得用模型常识补写"),
                "facts must come from tool evidence, which is the AnswerGate's job too");
    }

    @Test void budgetBlockReportsLiveRoundAndToolCounts() {
        String rendered = new GoalContract(GOAL, 30).render(7, 12);
        assertTrue(rendered.contains("本次最多 30 轮"));
        assertTrue(rendered.contains("当前是第 8 轮"), "one-based for the model's benefit");
        assertTrue(rendered.contains("剩余 23 轮"), "30 budget minus the 7 already spent");
        assertTrue(rendered.contains("已执行工具调用 12 次"));
    }

    @Test void theFinalRoundLeavesNoBudgetToSpend() {
        String rendered = new GoalContract(GOAL, 30).render(29, 40);
        assertTrue(rendered.contains("当前是第 30 轮"));
        assertTrue(rendered.contains("剩余 1 轮"), "this round is the last one");
        assertTrue(rendered.contains("强制收尾"),
                "the model must know the system will cut it off, so it can deliver early");
    }

    /**
     * The loop re-renders every iteration against the same base template, so injection
     * must not stack: a message that already carries the contract is returned as-is.
     */
    @Test void injectionIsIdempotent() {
        GoalContract contract = new GoalContract(GOAL, 30);
        String once = contract.inject("SYSTEM PROMPT");
        assertTrue(once.contains("不可变更的本轮目标"));
        assertEquals(once, contract.inject(once), "a second injection must add nothing");
        assertEquals(once, contract.inject(contract.inject(once)));
    }

    @Test void injectionLeavesAnUnannotatedMessageAlone() {
        GoalContract contract = new GoalContract(GOAL, 30);
        // No marker present, so the contract IS appended — that is the point.
        String injected = contract.inject("plain");
        assertTrue(injected.startsWith("plain"));
        assertTrue(injected.contains("不可变更的本轮目标"));
    }

    @Test void injectionToleratesNull() {
        assertNull(new GoalContract(GOAL, 30).inject(null));
    }

    @Test void aBlankRequestStillProducesAUsableContract() {
        String rendered = new GoalContract("  ", 30).render();
        assertTrue(rendered.contains("成功判定契约"), "an empty goal must not drop the rules");
    }
}