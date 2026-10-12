package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * The compaction threshold decides how often a long task is interrupted mid-flight.
 *
 * <p>The regression this locks down: the effective window was hard-capped at 32_768
 * tokens, so a 512K-token model compacted at ~23K and every long task re-compacted every
 * few steps. Nothing could see the constant, so nothing could catch it.</p>
 */
class ContextCompressorThresholdTest {

    @Test void thresholdScalesWithTheConfiguredWindow() {
        // 524288 (agnes-3.0-flash) * 0.75
        assertEquals(393_216, ContextCompressor.compactionThreshold(524_288));
        // 131072 * 0.75
        assertEquals(98_304, ContextCompressor.compactionThreshold(131_072));
    }

    /** A 512K model must no longer be silently treated as a 32K model. */
    @Test void aLargeWindowIsNotCappedAtTheOld32768() {
        int threshold = ContextCompressor.compactionThreshold(524_288);
        assertTrue(threshold > 196_608,
                "the old cap produced ~23K; a 512K model needs a threshold in the hundreds of thousands");
        assertTrue(threshold < 524_288, "and it must stay below the window itself");
    }

    @Test void theCeilingStillBoundsAMisconfiguredWindow() {
        // A value larger than any real window must not push compaction past the ceiling.
        assertEquals(786_432, ContextCompressor.compactionThreshold(2_000_000),
                "1048576 * 0.75 — the ceiling keeps a bogus config from overflowing");
    }

    @Test void tinyWindowsKeepAUsableFloor() {
        assertEquals(1_536, ContextCompressor.compactionThreshold(1),
                "the floor must survive a nonsense configuration");
        assertEquals(1_536, ContextCompressor.compactionThreshold(2_048));
    }

    @Test void thresholdIsMonotonicAcrossThePracticalRange() {
        int previous = 0;
        for (int window : new int[]{4_096, 32_768, 131_072, 262_144, 524_288, 1_048_576}) {
            int threshold = ContextCompressor.compactionThreshold(window);
            assertTrue(threshold > previous, "a larger window must never compact earlier");
            previous = threshold;
        }
    }
}