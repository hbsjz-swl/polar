package com.dlchm.dlc.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jline.terminal.Attributes;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.NonBlockingReaderImpl;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the turn-steering keyboard reader.
 *
 * <p>The class exists because a running turn otherwise owns the terminal: keystrokes
 * echo but nobody reads them, and ESC is eaten by the line editor, so a long turn can
 * only be waited out or killed with Ctrl-C. These tests drive the byte handling
 * directly; the raw-mode entry itself needs a real tty and is exercised by running
 * the CLI.</p>
 */
class TurnInputWatcherTest {

    private static final String ESC = "\u001b";
    private static final String CTRL_C = "\u0003";
    private static final String DEL = "\u007f";

    /** A watcher whose terminal is a plain string, so no tty is involved. */
    private static TurnInputWatcher watcherOver(String queued) {
        return new TurnInputWatcher(new StubSource(queued));
    }

    @Test void escapeRequestsCancellation() {
        TurnInputWatcher watcher = watcherOver(ESC);

        watcher.feedForTest(ESC);

        assertTrue(watcher.isCancelled(), "a bare ESC is the conventional stop key");
    }

    @Test void controlCAlsoStops() {
        TurnInputWatcher watcher = watcherOver(CTRL_C);

        watcher.feedForTest(CTRL_C);

        assertTrue(watcher.isCancelled(),
                "Ctrl-C must reach us as a byte now that ISIG is off, not kill the JVM");
    }

    @Test void escapeSequenceIsNotMistakenForACancel() throws Exception {
        // An arrow key arrives as ESC [ A. Reading that as a stop would make ordinary
        // line editing cancel the turn — the failure this guard exists for.
        // Driven through the real read loop, because the disambiguation only works
        // when ESC has actually been consumed from the reader before peeking at it.
        TurnInputWatcher watcher = watcherOver(ESC + "[A");

        watcher.start();
        assertTrue(waitUntil(() -> watcher.readerStopped()), "reader should drain and stop");
        watcher.stop();

        assertFalse(watcher.isCancelled(), "ESC followed by '[' is a cursor key, not a stop");
    }

    @Test void arrowKeyBytesAreConsumedNotTreatedAsText() throws Exception {
        TurnInputWatcher watcher = watcherOver(ESC + "[A" + "hello\r");

        watcher.start();
        // readerStopped, not a drain: draining is the assertion's job and doing it
        // here would empty the queue before it is ever inspected.
        assertTrue(waitUntil(() -> watcher.readerStopped()), "reader should drain the input");
        watcher.stop();

        assertEquals(List.of("hello"), watcher.drainPending(),
                "the cursor-key payload must not leak into the queued message");
        assertFalse(watcher.isCancelled());
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return false;
    }

    @Test void typedLineIsQueuedOnEnter() {
        TurnInputWatcher watcher = watcherOver("");

        watcher.feedForTest("only rail\r");

        assertEquals(List.of("only rail"), watcher.drainPending());
        assertFalse(watcher.isCancelled(), "typing is not cancelling");
    }

    @Test void carriageReturnAndNewlineBothSubmit() {
        TurnInputWatcher watcher = watcherOver("");

        watcher.feedForTest("a\r");
        watcher.feedForTest("b\n");

        assertEquals(List.of("a", "b"), watcher.drainPending());
    }

    @Test void drainEmptiesTheQueueSoAMessageIsDeliveredOnce() {
        TurnInputWatcher watcher = watcherOver("");

        watcher.feedForTest("first\rsecond\r");

        assertEquals(List.of("first", "second"), watcher.drainPending());
        assertTrue(watcher.drainPending().isEmpty(), "a second drain must not repeat messages");
    }

    @Test void blankLinesAreNotQueued() {
        TurnInputWatcher watcher = watcherOver("");

        watcher.feedForTest("\r   \r");

        assertTrue(watcher.drainPending().isEmpty(), "an empty line is not a message");
    }

    @Test void backspaceEditsTheLineBeingTyped() {
        TurnInputWatcher watcher = watcherOver("");

        watcher.feedForTest("flyt");
        watcher.feedForTest(DEL);   // 127 is what most terminals send for Backspace
        watcher.feedForTest("s\r");

        assertEquals(List.of("flys"), watcher.drainPending());
    }

    @Test void backspaceOnAnEmptyLineIsHarmless() {
        TurnInputWatcher watcher = watcherOver("");

        watcher.feedForTest(DEL);
        watcher.feedForTest("\r");

        assertTrue(watcher.drainPending().isEmpty());
    }

    @Test void lineIsResetBetweenMessages() {
        TurnInputWatcher watcher = watcherOver("");

        watcher.feedForTest("ab\rcd\r");

        assertEquals(List.of("ab", "cd"), watcher.drainPending(),
                "the second message must not inherit the first one's text");
    }

    @Test void utf8TextIsAssembledFromItsIndividualBytes() {
        // Keystrokes arrive one byte at a time and a Chinese character is three of
        // them. Treating each byte as a character would mangle the message, and this
        // is a Chinese-facing CLI, so the bytes must be decoded as UTF-8 on submit.
        TurnInputWatcher watcher = watcherOver("");

        feedBytes(watcher, "北京");
        watcher.feedForTest("\r");

        assertEquals(List.of("北京"), watcher.drainPending());
    }

    @Test void backspaceRemovesAWholeMultiByteCharacter() {
        TurnInputWatcher watcher = watcherOver("");

        feedBytes(watcher, "北京");
        watcher.feedForTest(DEL);   // one character, not one byte
        watcher.feedForTest("\r");

        assertEquals(List.of("北"), watcher.drainPending());
    }

    @Test void halfTypedCharacterStillSubmits() {
        TurnInputWatcher watcher = watcherOver("");

        // Two bytes of a three-byte character: the decoder must not throw, and the
        // line must still submit rather than taking the session down with it.
        byte[] bytes = "京".getBytes(StandardCharsets.UTF_8);
        watcher.feedByteForTest(bytes[0] & 0xFF);
        watcher.feedByteForTest(bytes[1] & 0xFF);
        watcher.feedForTest("\r");

        assertEquals(1, watcher.drainPending().size());
    }

    @Test void stopIsSafeWithoutStart() {
        TurnInputWatcher watcher = watcherOver("");
        watcher.stop();
        watcher.stop();
        assertFalse(watcher.isCancelled());
    }

    @Test void startIsIdempotent() {
        TurnInputWatcher watcher = watcherOver("");
        watcher.start();
        watcher.start();   // a second reader thread would steal the next prompt's first key
        watcher.stop();
    }

    @Test void unreadableTerminalLeavesSteeringInertRatherThanFailing() {
        StubSource source = new StubSource("");
        source.failOnEnter = true;
        TurnInputWatcher watcher = new TurnInputWatcher(source);

        watcher.start();

        assertFalse(watcher.isCancelled());
        assertTrue(watcher.drainPending().isEmpty());
        watcher.stop();
    }

    @Test void rawModeIsReleasedWhenTheTurnEnds() {
        // Leaving the tty in raw mode would break the next prompt: no echo, no
        // canonical line editing, and Ctrl-C would stop killing the process.
        StubSource source = new StubSource("x\r");
        TurnInputWatcher watcher = new TurnInputWatcher(source);

        watcher.start();
        watcher.stop();

        assertTrue(source.restored, "terminal attributes must be put back");
    }

    private static void feedBytes(TurnInputWatcher watcher, String text) {
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            watcher.feedByteForTest(b & 0xFF);
        }
    }

    /** Raw-mode source over a string; no tty involved. */
    private static final class StubSource implements TurnInputWatcher.RawModeSource {
        private final NonBlockingReader reader;
        boolean failOnEnter;
        boolean restored;

        StubSource(String pending) {
            this.reader = new NonBlockingReaderImpl("test", new StringReader(pending));
        }

        @Override public Attributes enter() throws IOException {
            if (failOnEnter) throw new IOException("no tty");
            return new Attributes();
        }

        @Override public NonBlockingReader reader() {
            return reader;
        }

        @Override public void restore(Attributes previous) {
            restored = true;
        }
    }
}