package com.dlchm.dlc.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets the user interrupt and steer a turn that is already running.
 *
 * <h2>The problem</h2>
 * <p>A turn runs to completion before {@code readLine} is called again, so while the
 * model is working the terminal accepts keystrokes only as echo: nobody reads them.
 * Worse, ESC is consumed by the line editor as an editing key, so even the conventional
 * "press ESC to stop" gives no way out — a long turn can only be waited out or
 * killed with Ctrl-C, which loses the session.</p>
 *
 * <h2>How this works</h2>
 * <p>A dedicated thread puts the terminal into raw mode for the duration of the turn
 * and reads bytes directly. ESC cancels the running turn (the agent loop already polls
 * {@code sink.isCancelled()} between iterations, so the turn unwinds at a safe
 * boundary). Anything else is accumulated into a line and, once complete, queued as
 * the next message — so "add a constraint mid-flight" works without stopping the turn.</p>
 *
 * <h2>Why raw mode rather than a second LineReader</h2>
 * <p>JLine owns the terminal's attributes and its line editor would swallow ESC and
 * printable characters alike. Reading bytes directly keeps this thread's input
 * entirely separate from the main thread's editor.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Started per turn and stopped when the turn ends, restoring the saved attributes.
 * Never left running between turns: it would steal the first keystroke of the next
 * {@code readLine}.</p>
 */
final class TurnInputWatcher {

    private static final Logger log = LoggerFactory.getLogger(TurnInputWatcher.class);
    private static final int ESC = 27;
    private static final int ENTER = '\r';
    private static final int NEWLINE = '\n';
    private static final int BACKSPACE = 127;
    private static final int CTRL_C = 3;

    /**
     * How long the reader waits for a keystroke before looping.
     *
     * <p>{@link NonBlockingReader#available()} is inherited as a constant 0, so
     * polling it would never deliver a byte. The timed read is the only API that
     * actually reports "nothing yet" — it returns {@link #NO_INPUT} — and it
     * keeps {@link #stop()} responsive instead of blocking on an idle terminal.</p>
     */
    private static final long READ_TIMEOUT_NANOS = 50_000_000L;

    /**
     * How long ESC waits for a possible continuation byte.
     *
     * <p>Long enough that the rest of an arrow-key sequence has arrived, short enough
     * that a bare ESC feels immediate.</p>
     */
    private static final long ESC_SETTLE_NANOS = 30_000_000L;

    /** {@link NonBlockingReader#READ_EXPIRED}: the timed read found nothing. */
    private static final int NO_INPUT = NonBlockingReader.READ_EXPIRED;

    /**
     * The slice of terminal control this class needs.
     *
     * <p>{@link Terminal} has twenty-odd abstract methods and most of them have nothing
     * to do with reading keys. Narrowing the dependency to raw-mode entry and a byte
     * reader keeps the input handling below testable without a tty, which is the only
     * way to cover the ESC-versus-arrow-key distinction.</p>
     */
    interface RawModeSource {
        /** Switches to raw mode and returns the previous attributes for restoration. */
        Attributes enter() throws IOException;

        NonBlockingReader reader();

        void restore(Attributes previous);
    }

    private final RawModeSource source;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Queue<String> pending = new ArrayDeque<>();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    private Thread thread;
    private volatile boolean running;
    private volatile boolean started;
    private volatile boolean readerDone;

    TurnInputWatcher(Terminal terminal) {
        this(new TerminalRawMode(terminal));
    }

    TurnInputWatcher(RawModeSource source) {
        this.source = source;
    }

    /** Adapts a JLine terminal to the narrow interface above. */
    private record TerminalRawMode(Terminal terminal) implements RawModeSource {
        @Override public Attributes enter() {
            // JLine supplies the port-correct baseline (VMIN/VTIME, Windows consoles);
            // only the flags this class depends on are forced afterwards, and each is
            // best-effort because a non-POSIX terminal does not support all of them.
            Attributes previous = terminal.getAttributes();
            Attributes raw = terminal.enterRawMode();
            if (raw == null) raw = new Attributes(previous);
            forceLocalFlag(raw, Attributes.LocalFlag.ECHO, false);
            // Signal chars off so Ctrl-C arrives as byte 3 instead of killing the JVM,
            // which would lose the session along with the turn.
            forceLocalFlag(raw, Attributes.LocalFlag.ISIG, false);
            terminal.setAttributes(raw);
            return previous;
        }

        @Override public NonBlockingReader reader() {
            return terminal.reader();
        }

        @Override public void restore(Attributes previous) {
            if (previous != null) terminal.setAttributes(previous);
        }

        private static void forceLocalFlag(Attributes attributes, Attributes.LocalFlag flag, boolean value) {
            try {
                attributes.setLocalFlag(flag, value);
            } catch (RuntimeException e) {
                log.debug("Terminal does not support {}", flag);
            }
        }
    }

    /** True once ESC (or Ctrl-C) has been seen; the caller cancels the agent turn. */
    boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Takes the queued messages typed during the turn, in order.
     *
     * <p>Called once, after the turn finishes; the queue is drained so a message is
     * never delivered twice.</p>
     */
    List<String> drainPending() {
        List<String> drained = new ArrayList<>();
        String message;
        while ((message = pending.poll()) != null) drained.add(message);
        return drained;
    }

    void start() {
        if (started) return;
        started = true;
        running = true;
        readerDone = false;
        thread = new Thread(this::watch, "dlc-turn-input");
        thread.setDaemon(true);
        thread.start();
    }

    /** True once the reader thread has run to completion (input exhausted or stopped). */
    boolean readerStopped() {
        return readerDone;
    }

    /** Stops reading and restores the terminal. Safe to call when not started. */
    void stop() {
        started = false;
        running = false;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            try {
                // Joined so the restore in the reader's finally block has completed
                // before the caller touches the terminal again; otherwise the main
                // thread's next readLine could race the attribute change.
                current.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    private void watch() {
        Attributes previous;
        try {
            previous = source.enter();
        } catch (IOException | RuntimeException e) {
            // Without raw mode the tty is line-buffered and this thread would compete
            // with the line editor for input, so steering simply stays unavailable —
            // the turn itself is unaffected.
            log.debug("Cannot enter raw mode for turn input: {}", e.toString());
            return;
        }
        NonBlockingReader reader = source.reader();
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int c = reader.read(READ_TIMEOUT_NANOS);
                if (c == NO_INPUT) continue;
                if (c < 0) break;
                handle(c);
            }
        } catch (IOException e) {
            // The terminal went away (window closed, ssh dropped). The turn keeps
            // running; only steering is lost, which is the same as before this class.
        } finally {
            try {
                source.restore(previous);
            } catch (RuntimeException ignored) {
                // Nothing further can be done; the main thread's LineReader resets the
                // terminal on its next read anyway.
            } finally {
                readerDone = true;
            }
        }
    }

    /**
     * Feeds a string of characters through {@link #handle(int)}, one per call.
     *
     * <p>Package-private so the input handling can be tested without a tty: raw-mode
     * entry and polling need a real terminal, but ESC detection, line editing and
     * queueing are pure input handling and are worth locking down.</p>
     */
    void feedForTest(String text) {
        for (int i = 0; i < text.length(); i++) {
            handle(text.charAt(i));
        }
    }

    /** Feeds one raw byte value, for multi-byte character cases. */
    void feedByteForTest(int value) {
        handle(value);
    }

    /** Waits for the reader thread to finish, so the restore path can be asserted. */
    void awaitStoppedForTest() throws InterruptedException {
        Thread current = thread;
        if (current != null) current.join(2_000);
    }

    private void handle(int c) {
        if (c == ESC) {
            // Only a bare ESC cancels. ESC followed by bytes is an escape sequence
            // (arrow keys, function keys), which must not be mistaken for a stop.
            if (nextByteIsSequence()) {
                skipSequence();
                return;
            }
            cancelled.set(true);
            return;
        }
        if (c == CTRL_C) {
            cancelled.set(true);
            return;
        }
        if (c == ENTER || c == NEWLINE) {
            String message = currentLine().strip();
            line.reset();
            if (message.isBlank()) return;
            pending.add(message);
            echo("\n" + AnsiSupport.ansi("2m") + "[已排队] " + message + AnsiSupport.ansi("0m") + "\n");
            return;
        }
        if (c == BACKSPACE || c == 8) {
            // Editing happens on the decoded string so one Backspace removes one
            // character, not one byte of a multi-byte one.
            String text = currentLine();
            if (!text.isEmpty()) {
                int end = text.offsetByCodePoints(text.length(), -1);
                line.reset();
                line.writeBytes(text.substring(0, end).getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        if (c >= 32 && c != 127) {
            line.write(c);
            // Echo only once a character is complete, otherwise a three-byte Chinese
            // character would print as three unrelated latin glyphs.
            String decoded = currentLine();
            if (!decoded.isEmpty()) {
                echo(String.valueOf(decoded.charAt(decoded.offsetByCodePoints(decoded.length(), -1))));
            }
            return;
        }
        // Other control bytes belong to the line editor's own key bindings and carry
        // no message content.
    }

    /**
     * Decodes what has been typed so far.
     *
     * <p>Bytes are accumulated and decoded as UTF-8 rather than one char at a time: a
     * Chinese character is three bytes, and treating each as its own character would
     * both mangle the text and echo three glyphs per keystroke.</p>
     *
     * <p>A malformed tail decodes to U+FFFD instead of throwing — a partially typed
     * character must not break the line being edited.</p>
     */
    private String currentLine() {
        byte[] bytes = line.toByteArray();
        if (bytes.length == 0) return "";
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Peeks for the {@code [} that starts an escape sequence.
     *
     * <p>JLine delivers arrow keys as ESC + {@code [} + letter, so a bare ESC is only
     * a stop when nothing follows. Without this check, pressing an arrow key during
     * a turn would cancel it.</p>
     *
     * <p>A timed {@code peek} rather than {@code available()}: the latter is
     * inherited as a constant 0, so it can never tell "nothing yet" from "not
     * loaded". {@link NonBlockingReader#peek(long)} returns {@link #NO_INPUT}
     * when the wait runs out, which is the answer this needs.</p>
     */
    private boolean nextByteIsSequence() {
        try {
            return source.reader().peek(ESC_SETTLE_NANOS) == '[';
        } catch (IOException e) {
            return false;
        }
    }

    private void skipSequence() {
        NonBlockingReader reader = source.reader();
        try {
            // Consume up to the final byte of a CSI sequence (letters terminate them).
            for (int i = 0; i < 8; i++) {
                int c = reader.read(READ_TIMEOUT_NANOS);
                if (c == NO_INPUT || c < 0) return;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '~') return;
            }
        } catch (IOException ignored) {
            // Whatever is left in the buffer is discarded with the turn.
        }
    }

    /**
     * Mirrors what was captured back to the terminal.
     *
     * <p>Echo is off at the tty level, so the line would otherwise be invisible — the
     * user would have no idea their input was being taken.</p>
     */
    private void echo(String text) {
        System.out.print(text);
        System.out.flush();
    }
}