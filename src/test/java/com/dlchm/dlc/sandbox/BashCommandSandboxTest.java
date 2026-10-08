package com.dlchm.dlc.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Destructive-command filtering across shells.
 *
 * <p>A blocklist written only against POSIX misses the Windows spelling of the
 * same destructive intent, which is the gap that lets a command wipe a drive
 * on one platform while being correctly rejected on another.</p>
 */
class BashCommandSandboxTest {

    private final BashCommandSandbox sandbox = new BashCommandSandbox();

    @Test void posixRootWipeIsBlocked() {
        assertThrows(SandboxViolationException.class, () -> sandbox.validate("rm -rf /"));
        assertThrows(SandboxViolationException.class, () -> sandbox.validate("rm -rf /*"));
    }

    @Test void windowsDriveWipeIsBlocked() {
        // Same intent as `rm -rf /`, different spelling — must not slip through.
        assertThrows(SandboxViolationException.class,
                () -> sandbox.validate("Remove-Item -Recurse -Force C:\\"));
        assertThrows(SandboxViolationException.class,
                () -> sandbox.validate("del /s /q C:\\Users"));
        assertThrows(SandboxViolationException.class,
                () -> sandbox.validate("rd /s /q C:\\Windows\\Temp"));
        assertThrows(SandboxViolationException.class,
                () -> sandbox.validate("format C:"));
    }

    @Test void diskAndCipherToolsAreBlocked() {
        assertThrows(SandboxViolationException.class, () -> sandbox.validate("diskpart"));
        assertThrows(SandboxViolationException.class,
                () -> sandbox.validate("cipher /w:C"));
        assertThrows(SandboxViolationException.class,
                () -> sandbox.validate("reg delete HKLM\\SOFTWARE"));
    }

    @Test void windowsDeletionsNeedConfirmationNotSilence() {
        // A destructive-but-scoped command should pause for a human, matching
        // how `rm file` behaves, rather than being blocked or waved through.
        assertTrue(sandbox.validate("Remove-Item build\\out.txt").requiresConfirmation());
        assertTrue(sandbox.validate("del notes.txt").requiresConfirmation());
    }

    @Test void deletionHiddenBehindASeparatorStillAsks() {
        // Checking only the head of the line let `echo hi && rm -rf ~/x` through.
        assertTrue(sandbox.validate("echo hi && rm -rf ~/x").requiresConfirmation());
        assertTrue(sandbox.validate("cd /tmp; del out.txt").requiresConfirmation());
    }

    @Test void aHarmlessWordContainingRmIsNotTreatedAsADeletion() {
        // Substring matching alone flags `confirm rm ` and any prose with "rm ".
        assertFalse(sandbox.validate("echo confirm").requiresConfirmation());
        assertFalse(sandbox.validate("grep -r pattern src/").requiresConfirmation());
        assertFalse(sandbox.validate("echo format report").requiresConfirmation());
    }

    @Test void ordinaryCommandsNeedNoConfirmation() {
        assertFalse(sandbox.validate("mvn -q package").requiresConfirmation());
        assertFalse(sandbox.validate("git status").requiresConfirmation());
        assertFalse(sandbox.validate("python script.py").requiresConfirmation());
    }

    @Test void forcePushAndSudoStillAsk() {
        assertTrue(sandbox.validate("git push --force origin main").requiresConfirmation());
        assertTrue(sandbox.validate("sudo apt install jq").requiresConfirmation());
        assertTrue(sandbox.validate("git reset --hard HEAD~3").requiresConfirmation());
    }

    @Test void blockingCoversCaseAndSurroundingWhitespace() {
        assertThrows(SandboxViolationException.class, () -> sandbox.validate("  RM -RF /  "));
        assertThrows(SandboxViolationException.class,
                () -> sandbox.validate("REMOVE-ITEM -Recurse -Force C:\\"));
    }
}
