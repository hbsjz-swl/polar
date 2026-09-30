package com.dlchm.dlc.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskStateTest {
    @Test void detectsStrictConstraintAndKeepsFactsAcrossContinuations() {
        TaskState state = new TaskState();
        state.observeUserMessage("帮我做攻略，用工具查实时价格，不要自己猜价格");
        assertTrue(state.strictFacts());
        state.recordToolResult("browser_action",
                "{\"url\":\"https://flights.ctrip.com/x\",\"final\":{\"main_text\":\"¥600起\"}}");
        assertTrue(state.hasFact("600"));

        state.observeUserMessage("继续");

        assertEquals("帮我做攻略，用工具查实时价格，不要自己猜价格", state.goal());
        assertTrue(state.hasFact("600"), "a bare continuation must not drop the fact ledger");
    }

    @Test void newTaskDropsThePreviousLedger() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下机票价格，不要猜");
        state.recordToolResult("browser_view", "¥1280");
        assertTrue(state.hasFact("1280"));

        state.observeUserMessage("帮我重构这个 Java 模块");

        assertEquals("帮我重构这个 Java 模块", state.goal());
        assertFalse(state.hasFact("1280"), "a new task must not inherit the old task's numbers");
    }

    @Test void scanPricesKeepsBothEndsOfARangeAndIgnoresOtherNumbers() {
        List<TaskState.PriceToken> tokens =
                TaskState.scanPrices("二等座合计约 ¥800–900，车次 G1095，日期 2026-10-02");
        List<String> values = tokens.stream().map(TaskState.PriceToken::value).toList();

        assertTrue(values.contains("800"));
        assertTrue(values.contains("900"), "the upper half of a currency range must survive");
        assertTrue(values.stream().noneMatch(v -> v.equals("1095") || v.equals("2026")));
    }

    @Test void onlyObservationToolsFeedTheLedger() {
        TaskState state = new TaskState();
        state.recordToolResult("bash_execute", "¥999");
        assertFalse(state.hasFact("999"));
    }

    @Test void renderIsEmptyUntilThereIsSomethingToSay() {
        assertEquals("", new TaskState().render());
        TaskState state = new TaskState();
        state.observeUserMessage("不要猜价格");
        assertTrue(state.render().contains("硬约束"));
    }

    @Test void roundTripsThroughJson() {
        TaskState state = new TaskState();
        state.observeUserMessage("不要猜价格，查实时票价");
        state.recordToolResult("browser_view", "¥600");

        TaskState restored = TaskState.fromJson(state.toJson());

        assertEquals(state.goal(), restored.goal());
        assertTrue(restored.strictFacts());
        assertTrue(restored.hasFact("600"));
    }

    @Test void damagedJsonDegradesToAnEmptyState() {
        assertEquals("", TaskState.fromJson("not json").render());
        assertEquals("", TaskState.fromJson(null).render());
    }
}
