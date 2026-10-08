package com.dlchm.dlc.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Facts about the machine DLC is running on.
 *
 * <p>Centralised so the rest of the codebase asks one object instead of
 * re-deriving {@code os.name} at each call site. The methods read system
 * properties through overridable suppliers, so a test can exercise the Windows
 * behaviour from macOS — which is the only practical way to keep the Windows
 * path from rotting until someone runs it on Windows.</p>
 */
public final class Platform {

    private static final java.util.function.Supplier<String> OS =
            () -> System.getProperty("os.name", "");
    private static final java.util.function.Supplier<String> OS_VERSION =
            () -> System.getProperty("os.version", "");

    private static volatile Platform instance;

    private final java.util.function.Supplier<String> osName;
    private final java.util.function.Supplier<String> osVersion;

    private Platform(java.util.function.Supplier<String> osName,
                     java.util.function.Supplier<String> osVersion) {
        this.osName = osName;
        this.osVersion = osVersion;
    }

    /** The real host. */
    public static Platform get() {
        Platform local = instance;
        if (local == null) {
            synchronized (Platform.class) {
                local = instance;
                if (local == null) {
                    local = new Platform(OS, OS_VERSION);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Builds a platform with fixed facts, for tests. */
    public static Platform of(String osName, String osVersion) {
        return new Platform(() -> osName, () -> osVersion);
    }

    /** Restores the real host; call from a test teardown. */
    public static void reset() {
        instance = null;
    }

    public boolean isWindows() {
        return os().contains("win");
    }

    public boolean isMac() {
        String os = os();
        return os.contains("mac") || os.contains("darwin");
    }

    public boolean isLinux() {
        return os().contains("linux");
    }

    public String osVersion() {
        return osVersion.get() == null ? "" : osVersion.get();
    }

    private String os() {
        String value = osName.get();
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** Human-readable name for prompts and error messages. */
    public String name() {
        if (isWindows()) return "Windows";
        if (isMac()) return "macOS";
        if (isLinux()) return "Linux";
        return osName.get() == null ? "unknown" : osName.get();
    }

    /**
     * The shell used to run commands, as an argv prefix.
     *
     * <p>Windows has no {@code bash} by default — PowerShell is the only shell
     * guaranteed to exist, so falling back to {@code bash -c} there made every
     * shell tool fail with a confusing "Cannot run program bash".</p>
     */
    public List<String> shell() {
        if (isWindows()) {
            // -NoProfile keeps a user's profile from redefining the shell, and
            // -NonInteractive stops a prompt from hanging the agent forever.
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command");
        }
        return List.of("bash", "-c");
    }

    /**
     * Wraps a command so its exit status survives the shell.
     *
     * <p>PowerShell does not propagate a native command's exit code as its own,
     * so a build that failed would look successful to the agent loop and the
     * model would carry on from a false premise.</p>
     */
    public String wrapForExitCode(String command) {
        if (!isWindows()) return command;
        return command + "; exit $LASTEXITCODE";
    }

    /** The platform's scratch directory, used in prompts and error hints. */
    public Path tempDir() {
        return Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
    }

    /**
     * A short description of shell conventions, injected into the system prompt.
     *
     * <p>The model defaults to POSIX commands regardless of host, so without
     * this it writes {@code rm -rf} on Windows and fails with "not recognized",
     * then tends to retry variations of the same wrong guess.</p>
     */
    public String shellHints() {
        if (isWindows()) {
            // Plain concatenation rather than a text block: line-continuation in a
            // text block eats the quotes around "not recognized", and that is the
            // one phrase the model most needs to read.
            return "- The shell is PowerShell, not bash. Use `Remove-Item -Recurse -Force` "
                    + "instead of `rm -rf`, `dir` instead of `ls`, `$env:TEMP` instead of `/tmp`, "
                    + "and `;` or a newline instead of `&&` for chaining. `curl` is an alias for "
                    + "Invoke-WebRequest, which takes different flags — prefer "
                    + "`Invoke-WebRequest -Uri <url> -OutFile <path>`. A command that comes back "
                    + "\"not recognized\" is bash syntax: rewrite it in PowerShell rather than "
                    + "retrying a variation. Each command's exit code is reported back, so read it "
                    + "instead of assuming success.";
        }
        return "- The shell is bash. POSIX commands and paths apply; do not use PowerShell syntax.";
    }

    /** Separator used when building paths in messages and commands. */
    public String pathSeparator() {
        return isWindows() ? "\\" : "/";
    }

    /**
     * Locates an executable on {@code PATH}, or {@code null} when absent.
     *
     * <p>Scans {@link java.io.File#pathSeparator} directly instead of shelling
     * out to {@code which}/{@code where}: neither binary is guaranteed to exist
     * on a slim container, and each probe would cost a process launch.</p>
     */
    public static String findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String directory : path.split(java.io.File.pathSeparator, -1)) {
            if (directory.isBlank()) continue;
            try {
                Path candidate = Path.of(directory).resolve(name);
                if (java.nio.file.Files.isExecutable(candidate)) return candidate.toString();
            } catch (Exception ignored) { }
        }
        return null;
    }
}
