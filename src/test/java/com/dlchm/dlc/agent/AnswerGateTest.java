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

    @Test void allowsAComputedDifferenceBetweenObservedPrices() {
        TaskState state = new TaskState();
        state.observeUserMessage("不要自己猜价格");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"厦门→北京 ¥1340\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"厦门→天津 ¥1230\"}}");

        // Both legs are sourced; the ¥110 is their difference, not an observation.
        // This is the exact shape the live run produced and got re-driven for.
        assertNull(AnswerGate.violation(state,
                "厦门→天津 ¥1230，厦门→北京 ¥1340，所以天津比北京便宜 ¥110"));
    }

    @Test void allowsADifferenceMarkedAsAnEstimateAfterTheNumber() {
        TaskState state = new TaskState();
        state.observeUserMessage("不要自己猜价格");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥1340\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥1230\"}}");

        assertNull(AnswerGate.violation(state, "差额为 90 元（1340-1230，推算值）"));
    }

    @Test void stillFlagsAPriceThatIsNotADifference() {
        TaskState state = new TaskState();
        state.observeUserMessage("不要自己猜价格");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥600\"}}");

        // "特价" is not an arithmetic context: this is a fabricated observation.
        assertEquals("¥299", AnswerGate.violation(state, "还有特价机票只要 ¥299 起"));
    }

    @Test void allowsADerivedTotalInATableCellWithNoKeywordNearIt() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下机票价格");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"石家庄→厦门 ¥550\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"厦门→石家庄 ¥800\"}}");

        // Verbatim shape from the live run: the total sits in a table cell whose
        // nearest derivation keyword is a table header 30+ chars away, so the
        // keyword window never fires and only 550+800 can justify the 1350.
        assertNull(AnswerGate.violation(state,
                "| 方案 | 去程 | 返程 | 合计 |\n| 石家庄直飞 | ¥550 | ¥800 | **¥1350** |"));
    }

    @Test void allowsADifferenceBetweenDerivedTotals() {
        TaskState state = new TaskState();
        state.observeUserMessage("对比一下机票价格");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"北京→厦门 ¥590\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"厦门→北京 ¥1340\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"天津→厦门 ¥600\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"厦门→天津 ¥1230\"}}");

        // 1930 and 1830 are one level deep; 100 only appears after both are trusted.
        assertNull(AnswerGate.violation(state, "去程 ¥590、¥600，返程 ¥1340、¥1230，"
                + "北京线合计 ¥1930，天津线合计 ¥1830，天津线便宜 ¥100"));
    }

    @Test void stillFlagsANumberThatIsNoCombinationOfTheCitedLegs() {
        TaskState state = new TaskState();
        state.observeUserMessage("对比一下机票价格");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥590\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥600\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥1340\"}}");
        state.recordToolResult("browser_action", "{\"final\":{\"main_text\":\"¥1230\"}}");

        assertEquals("¥1200", AnswerGate.violation(state,
                "去程 ¥590、¥600，返程 ¥1340、¥1230，另有一班特价 ¥1200 起"));
    }
}
