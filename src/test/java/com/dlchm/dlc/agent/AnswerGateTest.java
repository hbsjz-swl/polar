package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.dlchm.dlc.session.TaskState;
import org.junit.jupiter.api.Test;

class AnswerGateTest {
    @Test void flagsAFabricatedPriceWhenTheUserDemandedRealData() {
        TaskState state = new TaskState();
        state.observeUserMessage("不要自己猜价格，用工具查");
        state.recordToolResult("browser_action",
                "{\"url\":\"https://flights.ctrip.com/x\",\"final\":{\"main_text\":\"¥600起\"}}");

        assertEquals("¥830", AnswerGate.violation(state, "10/5 厦门→北京 最低价约 ¥830 起"));
    }

    @Test void allowsPricesTheToolsActuallyObserved() {
        TaskState state = new TaskState();
        state.observeUserMessage("不要自己猜价格");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥600起\"}}");

        assertNull(AnswerGate.violation(state, "去程 ¥600，可以直接预订"));
    }

    @Test void allowsDerivedTotalsBuiltFromObservedValues() {
        TaskState state = new TaskState();
        state.observeUserMessage("不要自己猜价格");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥600\"}}");

        assertNull(AnswerGate.violation(state, "2 成人 1 儿童，合计 ¥1800"));
    }

    @Test void staysSilentWhenTheUserDidNotDemandVerifiedData() {
        TaskState state = new TaskState();
        state.observeUserMessage("帮我大概估个预算");

        assertNull(AnswerGate.violation(state, "预计每人 ¥2000 左右"));
    }
}
