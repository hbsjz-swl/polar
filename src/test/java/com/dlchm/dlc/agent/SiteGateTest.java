package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SiteGateTest {
    @Test void flagsASiteTheBrowserIsNotOn() {
        String narration = "当前截图确认是携程火车票查询页，表单已设置为 上海 → 天津";

        assertEquals("携程", SiteGate.violation("www.amap.com", narration));
    }

    @Test void flagsTheStaleSiteCarriedOverFromAnEarlierTurn() {
        // Reproduces the logged failure: amap was open, the model said 百度.
        assertEquals("百度", SiteGate.violation("www.amap.com", "当前是百度搜索结果页"));
    }

    @Test void staysSilentWhenTheNarrationNamesTheOpenSite() {
        assertNull(SiteGate.violation("trains.ctrip.com", "当前页面是携程火车票查询页"));
    }

    @Test void ignoresAPlanThatHasNoPresentTenseMarker() {
        // "接下来改用携程查询" is an intention, not a claim about the screen.
        assertNull(SiteGate.violation("www.amap.com", "接下来改用携程查询石家庄到天津的高铁"));
    }

    @Test void staysSilentWhenTheOpenHostIsUnrecognised() {
        assertNull(SiteGate.violation("example.internal", "当前是携程火车票查询页"));
    }

    @Test void staysSilentWithoutAnObservation() {
        assertNull(SiteGate.violation("", "当前是携程火车票查询页"));
        assertNull(SiteGate.violation(null, "当前是携程火车票查询页"));
    }

    @Test void mapsSubdomainsToTheirSite() {
        assertEquals("携程", SiteGate.siteOf("trains.ctrip.com").label());
        assertEquals("高德", SiteGate.siteOf("www.amap.com").label());
        assertNull(SiteGate.siteOf("example.com"));
    }
}
