package com.dlchm.dlc.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-turn task memory: the goal, the hard constraints the user stated, and the
 * verified values the tools actually returned.
 *
 * <p>It lives on {@link Session} and is re-rendered into the system message on
 * every turn. {@code ContextCompressor} rewrites only {@code messages[1..]} and
 * keeps {@code messages[0]}, so this state survives compaction by construction;
 * {@link MarkdownSessionStore} persists it, so it also survives a restart.</p>
 */
public final class TaskState {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_GOAL_CHARS = 600;
    private static final int MAX_FACT_COUNT = 300;
    private static final int MAX_CONSTRAINT_COUNT = 12;
    private static final int MAX_SOURCE_CHARS = 160;
    private static final int RENDER_FACT_LIMIT = 40;

    /** Phrases that turn "be accurate" into a hard, checkable constraint. */
    private static final List<String> STRICT_HINTS = List.of(
            "不要猜", "不猜", "不准猜", "不能猜", "别猜", "禁止猜", "请勿猜", "不要自己猜",
            "不要编", "不能编", "别编", "不要臆测", "不要虚构", "不得虚构", "不要凭经验",
            "必须实时", "要实时", "以实时为准", "用实时", "必须是真实", "要真实的");

    private static final Pattern CURRENCY_RANGE = Pattern.compile(
            "(?:¥|￥|RMB|CNY)\\s*([0-9][0-9,]*(?:\\.\\d+)?)\\s*[-–—~～至到]\\s*([0-9][0-9,]*(?:\\.\\d+)?)");
    private static final Pattern CURRENCY = Pattern.compile(
            "(?:¥|￥|RMB|CNY)\\s*([0-9][0-9,]*(?:\\.\\d+)?)");
    private static final Pattern YUAN = Pattern.compile(
            "([0-9][0-9,]*(?:\\.\\d+)?)\\s*元");
    private static final Pattern QUOTED_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]{0,200})\"");

    private String goal = "";
    private final List<String> constraints = new ArrayList<>();
    private final List<Fact> facts = new ArrayList<>();

    /** One tool-observed value together with where it came from. */
    public record Fact(String value, String source, String at) {}

    /** A price-looking token found in free text. {@code start} is its index in that text. */
    public record PriceToken(String raw, String value, int start) {}

    public synchronized String goal() { return goal; }

    public synchronized List<String> constraints() { return List.copyOf(constraints); }

    public synchronized List<Fact> facts() { return List.copyOf(facts); }

    /** True when the user stated a constraint that makes fabricated numbers a failure. */
    public synchronized boolean strictFacts() { return !constraints.isEmpty(); }

    public synchronized Set<String> factValues() {
        Set<String> values = new LinkedHashSet<>();
        for (Fact fact : facts) values.add(fact.value());
        return values;
    }

    public synchronized void reset() {
        goal = "";
        constraints.clear();
        facts.clear();
    }

    public synchronized void copyFrom(TaskState other) {
        if (other == null) return;
        List<String> otherConstraints;
        List<Fact> otherFacts;
        String otherGoal;
        synchronized (other) {
            otherGoal = other.goal;
            otherConstraints = new ArrayList<>(other.constraints);
            otherFacts = new ArrayList<>(other.facts);
        }
        goal = otherGoal;
        constraints.clear();
        constraints.addAll(otherConstraints);
        facts.clear();
        facts.addAll(otherFacts);
    }

    /**
     * Fold a new user message into the task. A bare continuation ("继续", "接着查")
     * keeps the current goal and the facts collected so far; anything else starts a
     * new task and drops the previous task's ledger.
     */
    public synchronized void observeUserMessage(String input) {
        if (input == null || input.isBlank()) return;
        if (!isContinuation(input)) {
            goal = limit(input.replaceAll("\\s+", " ").trim(), MAX_GOAL_CHARS);
            facts.clear();
        }
        for (String hint : STRICT_HINTS) {
            if (!input.contains(hint) || constraints.size() >= MAX_CONSTRAINT_COUNT) continue;
            String rule = sentenceContaining(input, hint);
            if (!constraints.contains(rule)) constraints.add(rule);
        }
    }

    /**
     * Record the price-like values a tool actually returned, so a later answer can be
     * checked against them. Only observation tools feed the ledger.
     */
    public synchronized void recordToolResult(String toolName, String result) {
        if (toolName == null || !toolName.startsWith("browser_")) return;
        if (result == null || result.isBlank()) return;
        List<PriceToken> tokens = scanPrices(result);
        if (tokens.isEmpty()) return;
        String source = toolName;
        Matcher url = QUOTED_URL.matcher(result);
        if (url.find()) source = toolName + " " + url.group(1);
        source = limit(source, MAX_SOURCE_CHARS);
        String at = Instant.now().toString();
        for (PriceToken token : tokens) {
            facts.add(new Fact(token.value(), source, at));
            if (facts.size() > MAX_FACT_COUNT) facts.remove(0);
        }
    }

    /** True when the answer's number is backed by a tool observation. */
    public synchronized boolean hasFact(String value) {
        for (Fact fact : facts) if (fact.value().equals(value)) return true;
        return false;
    }

    public synchronized String toJson() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("goal", goal);
            ArrayNode constraintArray = root.putArray("constraints");
            for (String constraint : constraints) constraintArray.add(constraint);
            ArrayNode factArray = root.putArray("facts");
            for (Fact fact : facts) {
                ObjectNode node = factArray.addObject();
                node.put("value", fact.value());
                node.put("source", fact.source());
                node.put("at", fact.at());
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static TaskState fromJson(String json) {
        TaskState state = new TaskState();
        if (json == null || json.isBlank()) return state;
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) return state;
            state.goal = limit(root.path("goal").asText(""), MAX_GOAL_CHARS);
            for (JsonNode constraint : root.path("constraints")) {
                if (state.constraints.size() >= MAX_CONSTRAINT_COUNT) break;
                String value = constraint.asText("");
                if (!value.isBlank()) state.constraints.add(value);
            }
            for (JsonNode fact : root.path("facts")) {
                String value = fact.path("value").asText("");
                if (value.isBlank()) continue;
                state.facts.add(new Fact(value, fact.path("source").asText(""),
                        fact.path("at").asText("")));
            }
        } catch (Exception e) {
            return new TaskState();
        }
        return state;
    }

    /** Markdown block appended to the system message every turn. */
    public synchronized String render() {
        if (goal.isBlank() && constraints.isEmpty() && facts.isEmpty()) return "";
        StringBuilder block = new StringBuilder("\n\n## Task State（跨回合任务状态，历史压缩不会丢失）\n");
        if (!goal.isBlank()) block.append("- 当前目标：").append(goal).append('\n');
        if (!constraints.isEmpty()) {
            block.append("- 硬约束（违反即视为任务失败）：\n");
            for (String constraint : constraints) block.append("  - ").append(constraint).append('\n');
        }
        if (!facts.isEmpty()) {
            block.append("- 工具已取证的数值（回答中的金额必须来自这里，否则就是编造）：\n");
            int from = Math.max(0, facts.size() - RENDER_FACT_LIMIT);
            for (int i = from; i < facts.size(); i++) {
                Fact fact = facts.get(i);
                block.append("  - ").append(fact.value()).append("  ← ").append(fact.source()).append('\n');
            }
            if (from > 0) block.append("  - （更早的 ").append(from).append(" 条已省略）\n");
        }
        return block.toString();
    }

    /**
     * Extract price-looking tokens. Currency ranges are matched first so that the
     * trailing half of {@code ¥800–900} is not lost to the single-value pattern.
     */
    public static List<PriceToken> scanPrices(String text) {
        List<PriceToken> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) return tokens;
        List<int[]> spans = new ArrayList<>();
        Matcher range = CURRENCY_RANGE.matcher(text);
        while (range.find()) {
            spans.add(new int[] {range.start(), range.end()});
            tokens.add(new PriceToken(range.group(), normalize(range.group(1)), range.start()));
            tokens.add(new PriceToken(range.group(), normalize(range.group(2)), range.start()));
        }
        Matcher currency = CURRENCY.matcher(text);
        while (currency.find()) {
            if (covered(spans, currency.start())) continue;
            tokens.add(new PriceToken(currency.group(), normalize(currency.group(1)), currency.start()));
        }
        Matcher yuan = YUAN.matcher(text);
        while (yuan.find()) {
            if (covered(spans, yuan.start())) continue;
            tokens.add(new PriceToken(yuan.group(), normalize(yuan.group(1)), yuan.start()));
        }
        return tokens;
    }

    private static boolean covered(List<int[]> spans, int position) {
        for (int[] span : spans) {
            if (position >= span[0] && position < span[1]) return true;
        }
        return false;
    }

    /** "1,280.00" -> "1280"; keeps meaningful decimals. */
    static String normalize(String raw) {
        String value = raw.replace(",", "").trim();
        if (value.contains(".")) {
            value = value.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return value;
    }

    private static boolean isContinuation(String input) {
        String text = input.trim();
        if (text.length() > 24) return false;
        return text.matches("^(继续|接着|再来|go\\s*on|continue|ok|好的?|嗯+|可以|行|对|是的|按.+继续)[!！。.~～\\s]*$")
                || text.contains("继续") || text.contains("接着") || text.contains("再查");
    }

    private static String sentenceContaining(String text, String needle) {
        for (String part : text.split("[。！？!?\\n]")) {
            if (part.contains(needle)) return limit(part.trim(), 120);
        }
        return limit(needle, 120);
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
