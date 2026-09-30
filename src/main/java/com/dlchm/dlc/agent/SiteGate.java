package com.dlchm.dlc.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guards the narration against describing a page that is not open.
 *
 * <p>On a turn that hops between sites, the model tends to carry a page
 * description forward from earlier in the conversation and narrate it as the
 * current screen — "当前截图确认是携程火车票查询页" while the browser actually
 * sits on amap.com. That false premise is what produces the guessed selectors
 * that follow it, so it is worth catching at the narration level instead of
 * waiting for the click to time out.</p>
 *
 * <p>Deliberately narrow, in the same spirit as {@link AnswerGate}: it only
 * fires when the browser is on a site it recognises, the narration carries a
 * present-tense marker attached to a <em>different</em> recognised site, and
 * nothing in the same sentence names the site that is really open.</p>
 */
final class SiteGate {
    /** A site the agent works with: the name it uses in prose and the host it lives on. */
    record Site(String label, String host) {}

    private static final List<Site> SITES = List.of(
            new Site("携程", "ctrip.com"),
            new Site("12306", "12306.cn"),
            new Site("高德", "amap.com"),
            new Site("百度", "baidu.com"),
            new Site("必应", "bing.com"),
            new Site("谷歌", "google.com"),
            new Site("去哪儿", "qunar.com"),
            new Site("飞猪", "fliggy.com"),
            new Site("同程", "ly.com"));

    /**
     * Markers that turn a site name into a claim about the screen. Kept to the
     * present tense on purpose: "改用携程查" is a plan, not a description of what
     * is open, and must not be flagged.
     */
    private static final Pattern PRESENT = Pattern.compile(
            "当前|现在|目前|此时|此刻|屏幕上|打开的|停留在|页面(?:已经|上是|是|显示|上)");

    /** How far before a site name a present marker still binds to it. */
    private static final int WINDOW = 26;

    private SiteGate() { }

    /**
     * @return the site label the narration wrongly claims is on screen, or
     *         {@code null} when the description is consistent with reality.
     */
    static String violation(String observedHost, String narration) {
        if (observedHost == null || observedHost.isBlank() || narration == null || narration.isBlank()) {
            return null;
        }
        Site observed = siteOf(observedHost);
        if (observed == null) return null;
        List<Site> asserted = new ArrayList<>();
        for (Site site : SITES) {
            int at = indexOfIgnoreCase(narration, site.label());
            if (at < 0 || !assertedHere(narration, at)) continue;
            if (observed.equals(site)) return null; // names the site that is really open
            asserted.add(site);
        }
        return asserted.isEmpty() ? null : asserted.get(0).label();
    }

    /** The site a host belongs to, or {@code null} for hosts outside the known set. */
    static Site siteOf(String host) {
        if (host == null) return null;
        String normalized = host.toLowerCase(Locale.ROOT);
        for (Site site : SITES) {
            if (normalized.equals(site.host()) || normalized.endsWith("." + site.host())
                    || normalized.contains(site.host())) {
                return site;
            }
        }
        return null;
    }

    private static boolean assertedHere(String narration, int siteAt) {
        int from = Math.max(0, siteAt - WINDOW);
        if (from == siteAt) return false;
        return PRESENT.matcher(narration.substring(from, siteAt)).find();
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
