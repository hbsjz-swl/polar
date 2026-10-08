package com.dlchm.dlc.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DlcCliTest {

    /**
     * The regression: the console printed only the first 500 characters of a failure,
     * and a Playwright call log carries its verdict on the last line. The intercepting
     * captcha iframe was cut away, so the operator read "Timeout 7000ms exceeded" with
     * no cause and no way to tell an obstruction from a slow page.
     */
    @Test void consoleLineKeepsTheVerdictAtTheEndOfALongFailure() {
        String verdict = "<iframe class=popwscps_d_iframe src=https://i.eastmoney.com/websitecaptcha/slidervalid>"
                + "</iframe> intercepts pointer events";
        String detail = "Locator.click: Timeout 7000ms exceeded.\nCall log:\n"
                + "- waiting for locator(\"[data-dlc-ref=\\\"e5\\\"]\")\n".repeat(30) + verdict;

        String shown = DlcCli.clipDetail(detail, 500);

        assertTrue(shown.length() <= 500, "the console line must stay bounded");
        assertTrue(shown.contains(verdict), "the diagnosis is the reason the line is printed at all");
        assertTrue(shown.contains("Timeout 7000ms"), "the symptom is kept too");
        assertTrue(shown.contains("[中间省略]"), "a cut must not look like the whole message");
    }

    @Test void shortDetailIsPrintedUntouched() {
        assertEquals("已完成 一切正常", DlcCli.clipDetail("已完成 一切正常", 500));
        assertNull(DlcCli.clipDetail(null, 500));
    }
}