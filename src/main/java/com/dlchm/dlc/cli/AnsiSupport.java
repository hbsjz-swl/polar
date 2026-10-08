package com.dlchm.dlc.cli;

/**
 * Decides whether ANSI escape sequences will render or print as noise.
 *
 * <p>On Windows the answer depends on the console host: enabling virtual
 * terminal processing is the console's job, and older {@code cmd.exe} hosts
 * have no idea what {@code ESC[36m} means and print it verbatim. Detecting this
 * once at startup keeps every colour constant in the CLI a plain field rather
 * than a hardcoded escape, so an unsupported terminal degrades to clean text
 * instead of a screenful of control characters.</p>
 */
public final class AnsiSupport {

    private static volatile Boolean enabled;

    private AnsiSupport() {}

    public static boolean enabled() {
        Boolean cached = enabled;
        if (cached != null) return cached;
        boolean value = detect();
        enabled = value;
        return value;
    }

    /** Overrides detection; used by tests and by the CLI once JLine reports the real terminal. */
    public static void override(Boolean value) {
        enabled = value;
    }

    /** The escape sequence for {@code code}, or an empty string when unsupported. */
    public static String ansi(String code) {
        return enabled() ? "[" + code : "";
    }

    private static boolean detect() {
        // The cross-platform convention: any value disables colour.
        if (System.getenv("NO_COLOR") != null) return false;
        if ("dumb".equalsIgnoreCase(System.getenv("TERM"))) return false;
        String term = System.getenv("TERM");
        // Windows Terminal and ConEmu announce themselves; both handle ANSI.
        if (System.getenv("WT_SESSION") != null) return true;
        if (System.getenv("ConEmuANSI") != null) return true;
        if (term != null && term.toLowerCase().contains("xterm")) return true;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("linux")) return true;
        if (os.contains("win")) {
            // Console virtual terminal processing arrived in Windows 10 1511.
            // Older hosts render escapes as literal characters, so guess from the
            // build number and let DLC_FORCE_COLOR override either way.
            String version = System.getenv("OS");
            return version != null && !version.contains("Windows 7")
                    && !version.contains("Windows 8")
                    && !version.contains("Windows Vista")
                    && !version.contains("Windows XP");
        }
        return false;
    }
}
