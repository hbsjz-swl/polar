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

    @Test void fileObservationsAlsoCountAsEvidence() {
        TaskState state = new TaskState();
        state.observeUserMessage("帮我核对这份报价单的价格");
        state.recordToolResult("read_file", "总价 ¥1280");

        assertTrue(state.hasFact("1280"), "an amount read from a file is an observation, not a fabrication");
    }

    @Test void aGenericLookupDoesNotArmTheGate() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下这个模块里有多少个 TODO");

        assertFalse(state.strictFacts(), "the gate guards prices, not every look-up");
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

    @Test void aQueryIntentArmsTheGateWithoutTheMagicPhrase() {
        TaskState state = new TaskState();
        state.observeUserMessage("查询一下10月2日从石家庄到厦门的机票和返程机票，对比哪个性价比更高");

        assertTrue(state.strictFacts(), "asking to look something up implies verified numbers");
    }

    @Test void anExplicitEstimateRequestOverridesTheQueryIntent() {
        TaskState state = new TaskState();
        state.observeUserMessage("帮我估算一下去厦门的费用就行，不用真查");

        assertFalse(state.strictFacts(), "an estimate request is permission to guess");
    }

    @Test void tracksTheObservedHostEvenWhenNoPriceWasReturned() {
        TaskState state = new TaskState();
        state.recordToolResult("browser_view",
                "{\"url\":\"https://www.amap.com/ss/search\",\"title\":\"高德地图\"}");

        assertEquals("www.amap.com", state.lastObservedHost());
        assertTrue(state.render().contains("amap.com"));
    }

    @Test void intentAndHostSurviveRoundTrip() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下机票价格");
        state.recordToolResult("browser_view", "{\"url\":\"https://flights.ctrip.com/x\"}");

        TaskState restored = TaskState.fromJson(state.toJson());

        assertTrue(restored.strictFacts());
        assertEquals("flights.ctrip.com", restored.lastObservedHost());
    }

    @Test void extractsTheHostFromAUrl() {
        assertEquals("trains.ctrip.com", TaskState.hostOf("https://trains.ctrip.com/x?y=1"));
        assertNull(TaskState.hostOf("about:blank"));
        assertNull(TaskState.hostOf(null));
    }

    @Test void bindsAFactToItsPageTitleSoItCannotBeReattributed() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下机票价格");
        state.recordToolResult("browser_action",
                "{\"url\":\"https://flights.ctrip.com/online/list/oneway-tsn-xmn?depdate=2026-10-02\","
                        + "\"title\":\"天津到厦门机票查询预订-携程机票\",\"final\":{\"main_text\":\"¥720起\"}}");

        String block = state.render();
        assertTrue(block.contains("720"), block);
        // A URL-only source reads as "oneway-tsn-xmn", which the model is forbidden to
        // decode from memory — so it re-guesses the route and a 天津 price can surface
        // as 石家庄's. The title keeps the route readable and compression-proof.
        assertTrue(block.contains("天津到厦门"),
                "a fact must carry a readable route, not just an airport code: " + block);
    }

    @Test void fallsBackToTheUrlWhenThePageHasNoTitle() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下机票价格");
        state.recordToolResult("browser_view",
                "{\"url\":\"https://flights.ctrip.com/x\",\"final\":{\"main_text\":\"¥600起\"}}");

        assertTrue(state.render().contains("https://flights.ctrip.com/x"), state.render());
    }

    @Test void tracksTheFullObservedUrlSoStallsAreVisible() {
        TaskState state = new TaskState();
        state.recordToolResult("browser_view",
                "{\"url\":\"https://flights.ctrip.com/online/channel\",\"title\":\"携程机票\"}");

        assertEquals("https://flights.ctrip.com/online/channel", state.lastObservedUrl());
        assertEquals("https://flights.ctrip.com/online/channel",
                TaskState.fromJson(state.toJson()).lastObservedUrl());
    }

    @Test void unescapesQuotesInACapturedTitle() {
        assertEquals("A \"quoted\" title", TaskState.unescape("A \\\"quoted\\\" title"));
        assertEquals("plain", TaskState.unescape("plain"));
    }

    @Test void aValueKeepsTheFragmentItAppearedIn() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下机票价格，不要猜");
        state.recordToolResult("browser_action",
                "{\"url\":\"https://flights.ctrip.com/online/list/oneway-xmn-tsn?depdate=2026-10-05\","
                        + "\"title\":\"厦门到天津机票查询预订\",\"final\":{\"main_text\":"
                        + "\"更多日期 10-05 低至 ¥930 起。实际航班行：CA2838 ¥1230 起。\"}}");

        String block = state.render();

        // Both numbers are genuine page content, so "it is in the ledger" cannot tell
        // them apart — and a total built from the hint is just as well-sourced as one
        // built from the row. The captured fragment is what makes the difference visible.
        assertTrue(block.contains("930"), block);
        assertTrue(block.contains("1230"), block);
        assertTrue(block.contains("低至"), "the hint's own wording must survive: " + block);
        assertTrue(block.contains("CA2838"), "the row price must keep its flight: " + block);
    }

    @Test void contextNeverSwallowsANeighbouringValue() {
        String text = "更多日期 10-05 ¥930 起。实际航班行：CA2838 ¥1230 起。";
        List<TaskState.PriceToken> tokens = TaskState.scanPrices(text);

        String hint = TaskState.contextOf(text, tokens.get(0));
        String row = TaskState.contextOf(text, tokens.get(1));

        assertTrue(hint.contains("更多日期"), hint);
        assertTrue(row.contains("CA2838"), row);
        assertNotEquals(hint, row);
    }

    @Test void renderKeepsEverySourcePageInsteadOfTruncatingByCount() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下机票价格");
        // A single results page emits 15-20 price tokens, so a "keep the last N facts"
        // window silently evicts the earliest routes from the injected block — and the
        // model then re-states them from memory, which is how prices get reattributed.
        for (int page = 0; page < 6; page++) {
            StringBuilder prices = new StringBuilder();
            for (int i = 0; i < 8; i++) prices.append('¥').append(500 + page * 100 + i).append(" 起 ");
            state.recordToolResult("browser_action",
                    "{\"url\":\"https://flights.ctrip.com/online/list/oneway-a" + page
                            + "-b?depdate=2026-10-02\",\"title\":\"第" + page
                            + "条线路查询\",\"final\":{\"main_text\":\"" + prices + "\"}}");
        }

        String block = state.render();

        assertTrue(block.contains("第0条线路查询"), "the first route must still be listed: " + block);
        assertTrue(block.contains("第5条线路查询"), block);
        assertTrue(block.contains("500"), block);
    }

    @Test void renderStatesThatThePageCaliberMatters() {
        TaskState state = new TaskState();
        state.observeUserMessage("查一下机票价格");
        state.recordToolResult("browser_view", "{\"url\":\"https://a.cn/x\",\"final\":{\"main_text\":\"¥600起\"}}");

        assertTrue(state.render().contains("取证口径"), state.render());
    }
}
