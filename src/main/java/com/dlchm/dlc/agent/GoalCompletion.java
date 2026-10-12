package com.dlchm.dlc.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model's own declaration of whether the goal is finished.
 *
 * <h2>What replaces what</h2>
 * <p>The loop used to infer this from the answer's wording: an {@code ASK_TO_CONTINUE}
 * pattern ("要不要我继续") paired with a {@code NEXT_STEP} pattern ("下一步") meant
 * "the model is stalling, push it". That reads the prose, not the task, so it fired on
 * a polite question after a finished answer and stayed silent when a model expressed
 * the same stall differently. This replaces the guess with an explicit marker the
 * model is instructed to emit.</p>
 *
 * <h2>Why markers rather than JSON status</h2>
 * <p>A structured status field would need the model to emit valid JSON in its final
 * prose turn, which it routinely embeds inside Markdown or splits across streamed
 * chunks. A line-anchored marker survives both, and a false positive is harmless: the
 * loop only uses it to <em>skip</em> a re-drive, and the evidence gates still run
 * afterwards.</p>
 */
public final class GoalCompletion {

    /** The model asserts the original request is fully satisfied. */
    public static final String DONE = "[GOAL_DONE]";

    /** The model asserts it cannot proceed without the user (credentials, captcha, ambiguity). */
    public static final String BLOCKED = "[GOAL_BLOCKED]";

    private static final Pattern DONE_PATTERN = Pattern.compile("\\[GOAL_DONE]");
    private static final Pattern BLOCKED_PATTERN = Pattern.compile("\\[GOAL_BLOCKED]");

    private GoalCompletion() { }

    public enum Verdict {
        /** Goal asserted complete. */
        DONE,
        /** Goal asserted blocked on the user. */
        BLOCKED,
        /** No assertion either way. */
        UNSTATED
    }

    /** Reads the verdict out of a finished assistant turn. */
    public static Verdict verdict(String text) {
        if (text == null) return Verdict.UNSTATED;
        // BLOCKED first: a turn can carry both when the model states what it finished
        // and what it could not, and the blocking reason is the actionable half.
        if (BLOCKED_PATTERN.matcher(text).find()) return Verdict.BLOCKED;
        if (DONE_PATTERN.matcher(text).find()) return Verdict.DONE;
        return Verdict.UNSTATED;
    }

    /**
     * Strips the markers so they are not shown to the user.
     *
     * <p>They are protocol, not content: leaving them in makes the answer look like it
     * was assembled by a machine that forgot to clean up. The collapse of 3+ newlines
     * matters because a marker on its own line leaves a gap where it was.</p>
     */
    public static String strip(String text) {
        if (text == null) return null;
        return text.replace(DONE, "").replace(BLOCKED, "").replaceAll("\\n{3,}", "\n\n");
    }

    /**
     * The instruction the model needs in order to emit the markers.
     *
     * <p>Stated as an output rule with the two markers named, because a vague "tell us
     * when you're done" produces prose the loop still has to interpret.</p>
     */
    public static String instruction() {
        return """

                <目标完成声明>
                当你认为用户原始请求已完整满足时，在最终回复的末尾单独一行输出 [GOAL_DONE]；
                当你因缺少凭据（登录/验证码/2FA）、需要人工审批、或存在真正歧义而无法继续时，
                输出 [GOAL_BLOCKED] 并说明具体缺什么。
                这两个标记是协议标记，不要向用户解释它们的含义；未输出任何标记表示本轮只是中途进展。
                调用工具的轮次不要输出这两个标记，也不要输出阶段性总结正文。
                </目标完成声明>
                """;
    }
}