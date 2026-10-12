package com.dlchm.dlc.agent;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The user's original request plus the contract that defines "done", re-injected into
 * the system message on every loop iteration.
 *
 * <h2>Why the goal has to be re-injected</h2>
 * <p>The loop is a plain {@code for} over model requests, and nothing between two
 * requests preserves text appended to the system message: history is replayed
 * verbatim, and the context compressor rewrites {@code messages[1..]} wholesale. So a
 * goal stated once at the start is gone by iteration three — exactly when a long task
 * starts drifting toward whatever the last tool result happened to mention.</p>
 *
 * <h2>Why it is a contract, not a reminder</h2>
 * <p>"Remember the user's request" gives the model nothing to check against. The
 * success criteria below are stated as obligations it can violate, so a turn that
 * quietly narrows the scope (one flight instead of a comparison, yesterday's price
 * instead of the requested date) is recognisably wrong rather than merely incomplete.</p>
 *
 * <h2>Idempotence</h2>
 * <p>Injection is guarded by a marker in the rendered text, so repeated calls on an
 * already-annotated message add nothing. Without the guard, a message passed through
 * twice would carry the goal twice and the budget twice.</p>
 */
public final class GoalContract {

    /** Present in rendered output once the contract has been injected. */
    public static final String MARKER = "不可变更的本轮目标";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String userRequest;
    private final int maxIterations;

    public GoalContract(String userRequest, int maxIterations) {
        this.userRequest = userRequest == null ? "" : userRequest.strip();
        this.maxIterations = Math.max(1, maxIterations);
    }

    /**
     * Appends the contract to a system message, or returns it unchanged if already
     * present. Idempotent by design: the loop calls this on every iteration.
     */
    public String inject(String systemMessage) {
        if (systemMessage == null || systemMessage.contains(MARKER)) return systemMessage;
        return systemMessage + render();
    }

    /** The contract body, including the budget block. */
    public String render() {
        return render(maxIterations, 0);
    }

    /**
     * The contract with live budget figures.
     *
     * @param iteration zero-based index of the iteration about to run
     */
    public String render(int iteration, int toolCallsUsed) {
        int remaining = Math.max(0, maxIterations - iteration);
        return """

                <不可变更的本轮目标>
                用户原始请求：
                %s

                成功判定契约：
                - 必须完整满足原始请求中的对象、时间范围、业务范围、输出格式和排除项。
                - 关键事实必须来自本轮真实工具调用结果、会话已有证据或用户输入，不得用模型常识补写。
                - 系统缺少对应能力时，明确说明哪一部分做不到，不要用近似数据冒充。
                - 执行失败时，保留已完成动作的证据并说明真实失败原因，不得声称成功。
                - 可以依据工具结果调整下一步的具体做法，但不得修改、弱化、缩小或替换原始目标。
                - 回复使用中文；把完整汇总留给目标达成时，中途进展一两句话带过即可。
                </不可变更的本轮目标>

                <本轮执行预算>
                本次最多 %d 轮模型请求；当前是第 %d 轮，剩余 %d 轮。
                已执行工具调用 %d 次。
                证据已足够时主动收尾并输出完成标记，不要为凑轮数继续调用工具。
                预算耗尽时系统会依据已有证据强制收尾，因此明显做不完时应先交付已取得的部分。
                </本轮执行预算>
                """.formatted(userRequest, maxIterations, iteration + 1, remaining, toolCallsUsed);
    }
}