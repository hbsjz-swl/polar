package com.dlchm.dlc.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** How many distinct values one source page may contribute to the rendered block. */
    private static final int MAX_RENDER_PER_SOURCE = 24;
    /** How many distinct source pages the rendered block keeps (most recent kept first). */
    private static final int MAX_RENDER_SOURCES = 16;
    /** Chars of surrounding page text captured with a value, to keep its meaning. */
    private static final int CONTEXT_BEFORE = 24;
    private static final int CONTEXT_AFTER = 14;
    private static final int MAX_CONTEXT_CHARS = 90;

    /** Phrases that turn "be accurate" into a hard, checkable constraint. */
    private static final List<String> STRICT_HINTS = List.of(
            "不要猜", "不猜", "不准猜", "不能猜", "别猜", "禁止猜", "请勿猜", "不要自己猜",
            "不要编", "不能编", "别编", "不要臆测", "不要虚构", "不得虚构", "不要凭经验",
            "必须实时", "要实时", "以实时为准", "用实时", "必须是真实", "要真实的");

    /**
     * Intents that implicitly require verified data. A user who says "查一下机票
     * 价格" is asking for real numbers whether or not they spell out "不要猜", and
     * relying on the literal phrase leaves the ledger unarmed exactly when it
     * matters.
     *
     * <p>Kept to price / real-time / booking vocabulary on purpose. Generic verbs
     * such as 查询 or 对比 would also arm the gate for "查一下这个模块" — a task whose
     * amounts come from a file, not from a page, and which must not be treated as
     * fabricated.</p>
     */
    private static final List<String> INTENT_HINTS = List.of(
            "价格", "票价", "费用", "报价", "多少钱", "比价", "性价比", "实时",
            "机票", "火车票", "高铁", "动车", "航班", "车次", "酒店", "房价", "优惠", "折扣");

    /**
     * Explicit requests for an estimate. These grant permission to reason rather
     * than observe, so they veto {@link #INTENT_HINTS} — "帮我大概估个预算" must not
     * arm the gate.
     */
    private static final List<String> ESTIMATE_HINTS = List.of(
            "估算", "估个", "大概估", "预估", "预测", "拍脑袋", "随便估", "粗略", "毛估");

    private static final Pattern CURRENCY_RANGE = Pattern.compile(
            "(?:¥|￥|RMB|CNY)\\s*([0-9][0-9,]*(?:\\.\\d+)?)\\s*[-–—~～至到]\\s*([0-9][0-9,]*(?:\\.\\d+)?)");
    private static final Pattern CURRENCY = Pattern.compile(
            "(?:¥|￥|RMB|CNY)\\s*([0-9][0-9,]*(?:\\.\\d+)?)");
    private static final Pattern YUAN = Pattern.compile(
            "([0-9][0-9,]*(?:\\.\\d+)?)\\s*元");
    private static final Pattern QUOTED_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]{0,200})\"");
    private static final Pattern QUOTED_TITLE = Pattern.compile(
            "\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,200})\"");
    private static final Pattern HOST = Pattern.compile("https?://([a-zA-Z0-9.\\-]+)");

    /**
     * Non-browser tools whose output is evidence. An amount quoted from a file is
     * as much an observation as one read off a page, and must not be treated as
     * invented. Action tools (bash_execute / write_file) stay out: their output is
     * noise to this ledger.
     */
    private static final Set<String> LEDGER_TOOLS = Set.of("read_file", "grep_search", "glob_search");

    private String goal = "";
    private final List<String> constraints = new ArrayList<>();
    private final List<Fact> facts = new ArrayList<>();
    /** Set when the current goal implicitly demands verified data (see INTENT_HINTS). */
    private boolean intentStrict;
    /** Host of the most recent browser observation, e.g. {@code trains.ctrip.com}. */
    private String lastObservedHost = "";
    /**
     * Full URL of the most recent browser observation. Host alone cannot tell two
     * route pages apart, and a page whose URL never changes while the model keeps
     * clicking is stalled — see {@code AgentLoop}'s stall hint.
     */
    private String lastObservedUrl = "";

    /**
     * One tool-observed value together with where it came from.
     *
     * <p>{@code source} answers "which page", {@code context} answers "which number on
     * that page". Both are needed: a single results page holds a date-bar hint
     * ("更多日期 10-05 ¥930") and the actual flight rows ("CA2838 ¥1230起"), and the
     * hint arrives first. Without the fragment the two are indistinguishable in the
     * ledger, so a total built from the wrong one still looks fully sourced.</p>
     */
    public record Fact(String value, String source, String context, String at) {}

    /** A price-looking token found in free text. {@code start} is its index in that text. */
    public record PriceToken(String raw, String value, int start) {}

    public synchronized String goal() { return goal; }

    public synchronized List<String> constraints() { return List.copyOf(constraints); }

    public synchronized List<Fact> facts() { return List.copyOf(facts); }

    /** True when fabricated numbers would be a failure, from phrase or from intent. */
    public synchronized boolean strictFacts() { return !constraints.isEmpty() || intentStrict; }

    /** Host of the last browser observation; empty until a page has been seen. */
    public synchronized String lastObservedHost() { return lastObservedHost; }

    /** Full URL of the last browser observation; empty until a page has been seen. */
    public synchronized String lastObservedUrl() { return lastObservedUrl; }

    public synchronized Set<String> factValues() {
        Set<String> values = new LinkedHashSet<>();
        for (Fact fact : facts) values.add(fact.value());
        return values;
    }

    public synchronized void reset() {
        goal = "";
        constraints.clear();
        facts.clear();
        intentStrict = false;
        lastObservedHost = "";
        lastObservedUrl = "";
    }

    public synchronized void copyFrom(TaskState other) {
        if (other == null) return;
        List<String> otherConstraints;
        List<Fact> otherFacts;
        String otherGoal;
        boolean otherIntent;
        String otherHost;
        String otherUrl;
        synchronized (other) {
            otherGoal = other.goal;
            otherConstraints = new ArrayList<>(other.constraints);
            otherFacts = new ArrayList<>(other.facts);
            otherIntent = other.intentStrict;
            otherHost = other.lastObservedHost;
            otherUrl = other.lastObservedUrl;
        }
        goal = otherGoal;
        constraints.clear();
        constraints.addAll(otherConstraints);
        facts.clear();
        facts.addAll(otherFacts);
        intentStrict = otherIntent;
        lastObservedHost = otherHost;
        lastObservedUrl = otherUrl;
    }

    /**
     * Fold a new user message into the task. A bare continuation ("继续", "接着查")
     * keeps the current goal and the facts collected so far; anything else starts a
     * new task and drops the previous task's ledger.
     */
    public synchronized void observeUserMessage(String input) {
        if (input == null || input.isBlank()) return;
        boolean continuation = isContinuation(input);
        if (!continuation) {
            goal = limit(input.replaceAll("\\s+", " ").trim(), MAX_GOAL_CHARS);
            facts.clear();
            intentStrict = requiresVerifiedData(input);
        } else {
            // A continuation keeps the earlier verdict; a bare "继续" carries no
            // intent of its own, but "继续对比价格" still re-asserts it.
            intentStrict |= requiresVerifiedData(input);
        }
        for (String hint : STRICT_HINTS) {
            if (!input.contains(hint) || constraints.size() >= MAX_CONSTRAINT_COUNT) continue;
            String rule = sentenceContaining(input, hint);
            if (!constraints.contains(rule)) constraints.add(rule);
        }
    }

    /**
     * True when the request asks for data that must be observed rather than
     * reasoned: any query/comparison/price intent, unless the user explicitly
     * asked for an estimate.
     */
    static boolean requiresVerifiedData(String input) {
        if (input == null || input.isBlank()) return false;
        for (String hint : ESTIMATE_HINTS) if (input.contains(hint)) return false;
        for (String hint : INTENT_HINTS) if (input.contains(hint)) return true;
        return false;
    }

    /**
     * Record the price-like values a tool actually returned, so a later answer can be
     * checked against them. Only observation tools feed the ledger.
     */
    public synchronized void recordToolResult(String toolName, String result) {
        if (toolName == null || result == null || result.isBlank()) return;
        boolean browser = toolName.startsWith("browser_");
        if (!browser && !LEDGER_TOOLS.contains(toolName)) return;
        String pageUrl = null;
        String pageTitle = null;
        if (browser) {
            Matcher url = QUOTED_URL.matcher(result);
            if (url.find()) pageUrl = url.group(1);
            Matcher title = QUOTED_TITLE.matcher(result);
            if (title.find()) pageTitle = unescape(title.group(1));
            String host = hostOf(pageUrl);
            // The current page is tracked even for results that carry no price: it is
            // what lets a later narration be checked against the site actually open.
            if (host != null) lastObservedHost = host;
            if (pageUrl != null && !pageUrl.isBlank()) lastObservedUrl = limit(pageUrl.trim(), MAX_SOURCE_CHARS);
        }
        List<PriceToken> tokens = scanPrices(result);
        if (tokens.isEmpty()) return;
        // Prefer the page title as the fact's source. The URL names the route in
        // airport codes ("oneway-tsn-xmn"), and the model is forbidden from decoding
        // those from memory — so a ledger keyed on the URL is one it silently
        // re-guesses, which is how a 天津 price ended up reported as 石家庄's. The
        // title says "天津到厦门" in words, so the binding survives compaction and
        // cannot be misread.
        String source = pageTitle != null && !pageTitle.isBlank() ? pageTitle
                : (pageUrl == null ? toolName : toolName + " " + pageUrl);
        source = limit(source, MAX_SOURCE_CHARS);
        String at = Instant.now().toString();
        for (PriceToken token : tokens) {
            // Capture what the number was sitting next to. The value alone cannot say
            // whether it is a bookable row price or a "low as" hint for another date,
            // and the arithmetic closure in AnswerGate accepts a sum either way.
            String context = contextOf(result, token);
            facts.add(new Fact(token.value(), source, context, at));
            if (facts.size() > MAX_FACT_COUNT) facts.remove(0);
        }
    }

    /** One rendered ledger line: the value, plus the text it appeared in when there is one. */
    private static String factLine(Fact fact) {
        String context = fact.context();
        return context == null || context.isBlank() ? fact.value() : fact.value() + " ｜ " + context;
    }

    /** The page text a value appeared in, so its meaning survives next to the number. */
    static String contextOf(String text, PriceToken token) {
        if (text == null || token == null) return "";
        int from = Math.max(0, token.start() - CONTEXT_BEFORE);
        int to = Math.min(text.length(), token.start() + token.raw().length() + CONTEXT_AFTER);
        if (from >= to) return "";
        String snippet = unescape(text.substring(from, to)).replaceAll("\\s+", " ").trim();
        return limit(stripScaffolding(snippet), MAX_CONTEXT_CHARS);
    }

    /**
     * The window is cut out of raw tool JSON, so its edges can land on the envelope
     * ({@code "main_text":"…"} / {@code …"}). Drop that so the fragment reads as page
     * text rather than as protocol noise.
     */
    private static String stripScaffolding(String snippet) {
        int open = snippet.lastIndexOf("\":\"");
        if (open >= 0) snippet = snippet.substring(open + 3);
        int close = snippet.lastIndexOf("\"}");
        if (close >= 0) snippet = snippet.substring(0, close);
        return snippet.trim();
    }

    /** "https://trains.ctrip.com/x" -> "trains.ctrip.com"; null when unparseable. */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher match = HOST.matcher(url.trim());
        if (!match.find()) return null;
        String host = match.group(1).toLowerCase(Locale.ROOT);
        return host.isBlank() ? null : host;
    }

    /** Undo the JSON string escapes inside a captured title, so it renders as text. */
    static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n', 'r', 't' -> out.append(' ');
                default -> out.append(next);
            }
        }
        return out.toString().trim();
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
            root.put("intentStrict", intentStrict);
            root.put("lastHost", lastObservedHost);
            root.put("lastUrl", lastObservedUrl);
            ArrayNode constraintArray = root.putArray("constraints");
            for (String constraint : constraints) constraintArray.add(constraint);
            ArrayNode factArray = root.putArray("facts");
            for (Fact fact : facts) {
                ObjectNode node = factArray.addObject();
                node.put("value", fact.value());
                node.put("source", fact.source());
                node.put("context", fact.context());
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
            state.intentStrict = root.path("intentStrict").asBoolean(false);
            state.lastObservedHost = limit(root.path("lastHost").asText(""), MAX_SOURCE_CHARS);
            state.lastObservedUrl = limit(root.path("lastUrl").asText(""), MAX_SOURCE_CHARS);
            for (JsonNode constraint : root.path("constraints")) {
                if (state.constraints.size() >= MAX_CONSTRAINT_COUNT) break;
                String value = constraint.asText("");
                if (!value.isBlank()) state.constraints.add(value);
            }
            for (JsonNode fact : root.path("facts")) {
                String value = fact.path("value").asText("");
                if (value.isBlank()) continue;
                state.facts.add(new Fact(value, fact.path("source").asText(""),
                        limit(fact.path("context").asText(""), MAX_CONTEXT_CHARS),
                        fact.path("at").asText("")));
            }
        } catch (Exception e) {
            return new TaskState();
        }
        return state;
    }

    /** Markdown block appended to the system message every turn. */
    public synchronized String render() {
        if (goal.isBlank() && constraints.isEmpty() && facts.isEmpty() && lastObservedHost.isBlank()) return "";
        StringBuilder block = new StringBuilder("\n\n## Task State（跨回合任务状态，历史压缩不会丢失）\n");
        if (!goal.isBlank()) block.append("- 当前目标：").append(goal).append('\n');
        if (!constraints.isEmpty()) {
            block.append("- 硬约束（违反即视为任务失败）：\n");
            for (String constraint : constraints) block.append("  - ").append(constraint).append('\n');
        }
        if (intentStrict && constraints.isEmpty()) {
            block.append("- 硬约束（违反即视为任务失败）：本任务要求真实数据，"
                    + "回答中的金额必须来自下方已取证的数值；查不到就明确写“未获得实时数据”，不得以具体金额呈现。\n");
        }
        if (!lastObservedHost.isBlank()) {
            block.append("- 浏览器最近所在站点：").append(lastObservedHost)
                    .append("（这是事实，不是记忆。描述或操作页面前必须先确认它，不要凭历史推测当前页面。）\n");
        }
        if (!facts.isEmpty()) {
            block.append("- 取证口径：同一个页面上往往有不同口径的数字——一是列表/详情里**与所查日期和条件对应的实际报价**，"
                    + "二是顶部或侧边的“低至 / 参考价 / 更多日期 / 历史低价”这类**跨日期提示**（通常渲染更早）。"
                    + "只有前者能用于合计与结论；两者冲突时以实际报价为准，并在回答里说明这个差异。\n");
            block.append("- 工具已取证的数值（按来源页面分组。每条「｜」后面是这个数值在原页面里出现的文字片段，"
                    + "用它判断这个数字的口径；回答里的金额必须来自这里，并归到正确的来源与口径，"
                    + "不得把甲线路的价格写成乙线路的）：\n");
            Map<String, List<Fact>> bySource = new LinkedHashMap<>();
            for (Fact fact : facts) bySource.computeIfAbsent(fact.source(), key -> new ArrayList<>()).add(fact);
            List<String> sources = new ArrayList<>(bySource.keySet());
            int dropped = 0;
            if (sources.size() > MAX_RENDER_SOURCES) {
                dropped = sources.size() - MAX_RENDER_SOURCES;
                sources = sources.subList(dropped, sources.size());
            }
            for (String source : sources) {
                block.append("  · ").append(source).append('\n');
                LinkedHashSet<String> lines = new LinkedHashSet<>();
                for (Fact fact : bySource.get(source)) lines.add(factLine(fact));
                int shown = 0;
                for (String line : lines) {
                    if (shown++ >= MAX_RENDER_PER_SOURCE) break;
                    block.append("    - ").append(line).append('\n');
                }
                int hidden = lines.size() - shown;
                if (hidden > 0) {
                    block.append("    - （该页还有 ").append(hidden)
                            .append(" 个数值未列出；需要时重新打开该页核对）\n");
                }
            }
            if (dropped > 0) {
                block.append("  · （更早的 ").append(dropped)
                        .append(" 个来源页面已省略；需要时重新打开核对，不要凭记忆复述它们的价格）\n");
            }
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
