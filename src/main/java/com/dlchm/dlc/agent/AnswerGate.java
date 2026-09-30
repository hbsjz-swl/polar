package com.dlchm.dlc.agent;

import com.dlchm.dlc.session.TaskState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards the last mile: an answer may not present a price the tools never observed
 * while the user has asked for real (non-guessed) numbers.
 *
 * <p>Deliberately high precision. It only runs when {@link TaskState#strictFacts()}
 * holds, and it skips numbers written in an arithmetic context — sums ("合计"),
 * averages ("人均") and every difference/comparison form ("A 比 B 便宜 ¥N",
 * "N1 - N2", "推算") — because those are derived from sourced components rather
 * than observed on their own. Getting this set too narrow is not "safe": a
 * derived number has no page to be looked up on, so flagging it forces the turn
 * into a redrive loop that can never succeed.</p>
 *
 * <p>Two independent ways to qualify as arithmetic, because wording alone is not
 * enough: an arithmetic <em>context</em> next to the number, and a <em>provable</em>
 * sum/difference/product of values the answer already cites. The second one covers
 * markdown tables and bolded summaries, where a derived figure has no keyword
 * anywhere near it.</p>
 */
final class AnswerGate {
    /**
     * How far back to look for a derivation marker before a price token. A plain
     * comparison needs room for "天津比北京便宜 " (8 chars) plus a table cell prefix,
     * so a tight window would flag legitimate differences as fabrications.
     */
    private static final int DERIVED_WINDOW = 32;
    /**
     * Contexts that mark a number as arithmetic on other numbers rather than an
     * observation of its own. Must cover differences and comparisons, not just
     * sums: a travel answer is mostly "A 比 B 便宜 ¥N" and "N1 - N2 = ¥M", and a
     * marker set that only knows 合计 flags every one of them and re-drives the
     * turn forever (a derived value can never be "looked up" on a page).
     */
    private static final Pattern DERIVED = Pattern.compile(
            "合计|总计|共计|小计|一共|总共|人均|平均|预算|换算|折算|折合|合共|共[计约]?"
                    + "|乘以|乘|×|\\*\\s*\\d"
                    + "|差额|差价|差值|相差|差距|便宜|省钱|省下|省了|多花|少花"
                    + "|相减|减去|加上|叠加|累计|净省|净差|比较后|算下来|算上"
                    + "|推算|推导|计算|算出|得出|求得|求和|=|＝|往返");

    private AnswerGate() { }

    /**
     * @return the offending raw token when the answer asserts an unsourced price,
     *         otherwise {@code null}.
     */
    static String violation(TaskState state, String answer) {
        if (state == null || !state.strictFacts() || answer == null || answer.isBlank()) return null;
        Set<String> known = state.factValues();
        // A value is "trusted" once it is either observed by a tool and cited in the
        // answer, or provably arithmetic on other trusted values. Seeding only from
        // cited observations matters: a fact that sits in the ledger but is not in
        // the answer cannot vouch for anything the answer claims.
        Set<String> trusted = new LinkedHashSet<>();
        List<TaskState.PriceToken> unexplained = new ArrayList<>();
        for (TaskState.PriceToken token : TaskState.scanPrices(answer)) {
            if (known.contains(token.value())) trusted.add(token.value());
            else unexplained.add(token);
        }
        // Close over arithmetic to a fixpoint: 550+800 -> 1350, then 1930-1830 -> 100.
        // Keyword windows cannot see a derived value in a markdown table cell (no
        // "合计" next to it), and a derived number has no page to be looked up on,
        // so treating it as fabricated re-drives the turn forever.
        boolean grew = true;
        while (grew && !trusted.isEmpty()) {
            grew = false;
            for (Iterator<TaskState.PriceToken> it = unexplained.iterator(); it.hasNext(); ) {
                TaskState.PriceToken token = it.next();
                if (derivesFrom(token.value(), trusted)) {
                    trusted.add(token.value());
                    it.remove();
                    grew = true;
                }
            }
        }
        for (TaskState.PriceToken token : unexplained) {
            if (isDerived(answer, token.start())) continue;
            return token.raw();
        }
        return null;
    }

    /**
     * How many distinct values the answer quotes that a tool actually observed. A
     * non-trivial count is evidence the turn already delivered something, which is
     * what makes a closing "want me to also do X?" an offer rather than a stall.
     */
    static int citedEvidenceCount(TaskState state, String answer) {
        if (state == null || answer == null || answer.isBlank()) return 0;
        Set<String> known = state.factValues();
        if (known.isEmpty()) return 0;
        Set<String> cited = new LinkedHashSet<>();
        for (TaskState.PriceToken token : TaskState.scanPrices(answer)) {
            if (known.contains(token.value())) cited.add(token.value());
        }
        return cited.size();
    }

    /** True when {@code value} is a sum, difference or product of two trusted values. */
    private static boolean derivesFrom(String value, Set<String> trusted) {
        BigDecimal target = parse(value);
        if (target == null) return false;
        for (String left : trusted) {
            BigDecimal a = parse(left);
            if (a == null) continue;
            for (String right : trusted) {
                // A value cannot vouch for itself: 600 + 600 = 1200 is one observation
                // doubled, not a combination of two, and allowing it would pass any
                // multiple of a single sourced price.
                if (left.equals(right)) continue;
                BigDecimal b = parse(right);
                if (b == null) continue;
                if (a.add(b).compareTo(target) == 0
                        || a.subtract(b).compareTo(target) == 0
                        || a.multiply(b).compareTo(target) == 0) return true;
            }
        }
        return false;
    }

    private static BigDecimal parse(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isDerived(String answer, int position) {
        int from = Math.max(0, position - DERIVED_WINDOW);
        return DERIVED.matcher(answer.substring(from, position)).find();
    }
}
