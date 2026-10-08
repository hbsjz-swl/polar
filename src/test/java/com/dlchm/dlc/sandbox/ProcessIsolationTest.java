package com.dlchm.dlc.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessIsolationTest {
    @TempDir
    Path workspace;

    @Test
    void wrappedShellCanRunAndExit() throws Exception {
        Process process = new ProcessBuilder(ProcessIsolation.command(workspace, "printf ok"))
                .directory(workspace.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            ProcessIsolation.destroyTree(process);
            throw new AssertionError("sandbox process did not exit");
        }
        assertEquals(0, process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Asserts the sandbox actually prevents the write.
     *
     * <p>The previous version of this test skipped itself whenever the native
     * sandbox was unavailable, and then asserted on the exit code rather than
     * on whether the file appeared. On a host where Seatbelt is unavailable that
     * combination made the test pass unconditionally — it verified nothing while
     * reporting green. A skip is still allowed, but it must be visible as a skip
     * and the assertion must be about the file, since {@code touch} on a denied
     * path can still exit zero depending on how the shell handles the error.</p>
     */
    @Test
    void writesOutsideTheWorkspaceDoNotLand() throws Exception {
        if (!ProcessIsolation.nativeAvailable()) {
            // No kernel sandbox here (Windows, a container, or a locked-down
            // host). Say so out loud rather than pretending the check passed.
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "native sandbox unavailable on this host; write confinement is not enforced");
        }
        Path outside = workspace.getParent().resolve("polar-sandbox-outside-" + System.nanoTime());
        try {
            Process process = new ProcessBuilder(ProcessIsolation.command(workspace,
                    "touch " + outside)).redirectErrorStream(true).start();
            process.waitFor(10, TimeUnit.SECONDS);
            assertFalse(Files.exists(outside),
                    "the sandbox profile let a write escape the workspace: " + outside);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void writesInsideTheWorkspaceAreAllowed() throws Exception {
        if (!ProcessIsolation.nativeAvailable()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "native sandbox unavailable on this host");
        }
        Path inside = workspace.resolve("allowed-" + System.nanoTime() + ".txt");
        Process process = new ProcessBuilder(ProcessIsolation.command(workspace,
                "touch " + inside)).redirectErrorStream(true).start();
        process.waitFor(10, TimeUnit.SECONDS);
        assertTrue(Files.exists(inside),
                "the sandbox blocked a write inside its own workspace");
        Files.deleteIfExists(inside);
    }

    /**
     * The Seatbelt profile must put the blanket allow before the deny.
     *
     * <p>Seatbelt resolves conflicting rules last-one-wins. With
     * {@code (allow default)} last — which is how this profile read — the deny
     * above it is cancelled out and the sandbox permits writes anywhere, which
     * is indistinguishable from running the command unsandboxed. Asserting on
     * the rule order catches that regression on any host, sandboxed or not.</p>
     */
    @Test
    void macProfileDeniesWritesWithoutBeingUndoneByAllowDefault() {
        // Built directly rather than read out of an argv: when the host sandbox
        // is unavailable the launcher is skipped entirely, and the rule order
        // would then never be checked anywhere.
        String profile = ProcessIsolation.macProfile(workspace);

        int allowDefault = profile.indexOf("(allow default)");
        int denyWrite = profile.indexOf("(deny file-write*");
        assertTrue(allowDefault >= 0 && denyWrite >= 0, profile);
        assertTrue(allowDefault < denyWrite,
                "(allow default) must precede the deny, otherwise it overrides it "
                        + "and the sandbox permits writes everywhere: " + profile);
        // And the workspace must be re-allowed after the deny, or nothing works.
        assertTrue(profile.indexOf("(allow file-write* (subpath \"" + workspace.toAbsolutePath()
                + "\"))") > denyWrite, profile);
    }

    @Test
    void windowsAndUnknownHostsGetNoSandboxLauncher() {
        // Claiming isolation without a launcher would be worse than admitting
        // there is none: the caller would trust a boundary that does not exist.
        for (Platform platform : List.of(Platform.of("Windows 11", "10.0"),
                Platform.of("FreeBSD", "14"))) {
            List<String> argv = ProcessIsolation.command(workspace, "printf ok", platform);
            assertFalse(argv.contains("sandbox-exec"), platform.name() + ": " + argv);
            assertFalse(argv.contains("bwrap"), platform.name() + ": " + argv);
            assertEquals(platform.shell().get(0), argv.get(0), platform.name() + ": " + argv);
        }
    }
}
