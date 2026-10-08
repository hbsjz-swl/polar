package com.dlchm.dlc.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-platform behaviour of the shell launcher and safety patterns.
 *
 * <p>The Windows cases run on macOS on purpose. They are the ones that cannot
 * be exercised by hand until someone sits down at a Windows machine, and the
 * failure they guard against — an argv that invokes a shell which does not
 * exist there — is invisible on the machine that wrote the code.</p>
 */
class PlatformTest {

    private static final Platform WINDOWS = Platform.of("Windows 11", "10.0");
    private static final Platform MAC = Platform.of("Mac OS X", "14.5");
    private static final Platform LINUX = Platform.of("Linux", "6.8");

    @Test void osDetectionCoversTheThreeSupportedHosts() {
        assertTrue(WINDOWS.isWindows());
        assertTrue(MAC.isMac());
        assertTrue(LINUX.isLinux());
        assertEquals("Windows", WINDOWS.name());
        assertEquals("macOS", MAC.name());
        assertEquals("Linux", LINUX.name());
    }

    @Test void windowsUsesPowerShellRatherThanTheAbsentBash() {
        // The whole point: Windows ships no bash, so a bash-based argv there
        // fails with "Cannot run program bash" for every single command.
        List<String> shell = WINDOWS.shell();
        assertEquals("powershell.exe", shell.get(0));
        assertTrue(shell.contains("-NoProfile"),
                "a user profile can redefine the shell, making behaviour unreproducible");
        assertTrue(shell.contains("-NonInteractive"),
                "an interactive prompt would hang the agent until the timeout");
    }

    @Test void unixHostsKeepBash() {
        assertEquals(List.of("bash", "-c"), MAC.shell());
        assertEquals(List.of("bash", "-c"), LINUX.shell());
    }

    @Test void windowsCommandPropagatesTheNativeExitCode() {
        // PowerShell does not inherit a native command's exit status, so without
        // this a failed build reads as success and the model plans from a lie.
        String wrapped = WINDOWS.wrapForExitCode("mvn -q package");
        assertTrue(wrapped.startsWith("mvn -q package"), wrapped);
        assertTrue(wrapped.endsWith("; exit $LASTEXITCODE"), wrapped);
    }

    @Test void unixCommandIsNotRewritten() {
        assertEquals("mvn -q package", MAC.wrapForExitCode("mvn -q package"));
    }

    @Test void windowsHintsNameTheSubstitutionsTheModelWouldOtherwiseGuessWrong() {
        String hints = WINDOWS.shellHints();
        // Each of these is a concrete thing the model writes by default and that
        // fails on Windows; asserting on the words keeps the guidance specific.
        assertTrue(hints.contains("PowerShell"), hints);
        assertTrue(hints.contains("Remove-Item"), hints);
        assertTrue(hints.contains("$env:TEMP"), hints);
        assertTrue(hints.contains("not recognized"), hints);
        assertFalse(hints.contains("&& for chaining") && !hints.contains("instead of `&&`"),
                "the && substitution must be spelled out");
    }

    @Test void unixHintsDoNotPushPowerShellOntoABashHost() {
        String hints = MAC.shellHints();
        assertTrue(hints.contains("bash"), hints);
        assertFalse(hints.contains("Remove-Item"), hints);
    }

    @Test void pathSeparatorMatchesTheHost() {
        assertEquals("\\", WINDOWS.pathSeparator());
        assertEquals("/", MAC.pathSeparator());
    }

    @Test void windowsReportsNoNativeSandboxInsteadOfClaimingIsolation() {
        // There is no Seatbelt equivalent in the Windows OS. Reporting true
        // would leave commands unsandboxed while the caller believes otherwise.
        assertFalse(ProcessIsolation.nativeAvailable(WINDOWS));
    }

    @Test void fallbackCommandRunsThroughTheHostShellNotHardcodedBash() {
        List<String> argv = ProcessIsolation.command(Path.of("/tmp/ws"), "dir", WINDOWS);
        assertEquals("powershell.exe", argv.get(0));
        assertTrue(argv.get(argv.size() - 1).contains("dir"), argv.toString());
        assertFalse(argv.contains("bash"), argv.toString());
    }

    @Test void realHostCommandStillInvokesBashOnMac() {
        List<String> argv = ProcessIsolation.command(Path.of("/tmp/ws"), "printf ok");
        assertTrue(argv.contains("bash"), argv.toString());
        assertTrue(argv.contains("printf ok"), argv.toString());
    }
}
