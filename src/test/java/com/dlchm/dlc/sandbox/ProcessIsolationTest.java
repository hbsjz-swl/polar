package com.dlchm.dlc.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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

    @Test
    void nativeWrapperRejectsWritesOutsideWorkspaceWhenAvailable() throws Exception {
        if (!ProcessIsolation.nativeAvailable()) return;
        Path outside = workspace.getParent().resolve("polar-sandbox-outside");
        Process process = new ProcessBuilder(ProcessIsolation.command(workspace,
                "touch " + outside)).redirectErrorStream(true).start();
        process.waitFor(5, TimeUnit.SECONDS);
        assertNotEquals(0, process.exitValue());
    }
}
