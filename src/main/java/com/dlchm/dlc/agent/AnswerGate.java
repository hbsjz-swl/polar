package com.dlchm.dlc.agent;

import com.dlchm.dlc.session.TaskState;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards the last mile: an answer may not present a price the tools never observed
 * while the user has asked for real (non-guessed) numbers.
 *
 * <p>Deliberately high precision. It only runs when {@link TaskState#strictFacts()}
 * holds, and it skips numbers written in a derivation context ("合计 / 人均 / ×2")
 * because those are arithmetic on sourced components rather than fabrications.</p>
 */
final class AnswerGate {
    /** How far back to look for a derivation marker before a price token. */
    private static final int DERIVED_WINDOW = 24;
    private static final Pattern DERIVED = Pattern.compile(
            "合计|总计|共计|小计|人均|平均|预算|换算|折合|合共|共[计约]?|乘以|乘|×|\\*\\s*\\d");

    private AnswerGate() { }

    /**
     * @return the offending raw token when the answer asserts an unsourced price,
     *         otherwise {@code null}.
     */
    static String violation(TaskState state, String answer) {
        if (state == null || !state.strictFacts() || answer == null || answer.isBlank()) return null;
        Set<String> known = state.factValues();
        for (TaskState.PriceToken token : TaskState.scanPrices(answer)) {
            if (known.contains(token.value())) continue;
            if (isDerived(answer, token.start())) continue;
            return token.raw();
        }
        return null;
    }

    private static boolean isDerived(String answer, int position) {
        int from = Math.max(0, position - DERIVED_WINDOW);
        return DERIVED.matcher(answer.substring(from, position)).find();
    }
}
