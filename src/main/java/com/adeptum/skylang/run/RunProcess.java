/*
 * SkyLang is a specification language whose compiler writes the code.
 * Copyright © 2026 Adeptum AB, Org.nr 559494-1824.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Website: https://www.adeptum.se
 * Contact: info@adeptum.se
 */

package com.adeptum.skylang.run;

import com.adeptum.skylang.backend.RunPlan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a profile's {@link RunPlan} and owns the serving application. The native CLI cannot host the
 * artifact itself, so the plan's command runs as a subprocess; this class starts it, learns the port
 * it bound from the plan's ready marker, streams the application's own output to the console once it
 * is serving, and tears it down.
 */
public final class RunProcess implements AutoCloseable {

    /** How much of the output to keep back, to explain a launch that never reached the marker. */
    private static final int TAIL_LINES = 25;

    // Every subprocess is tracked from the moment it starts, so a JVM shutdown — Ctrl-C while a
    // launch is still waiting for the marker, before any owner holds a reference — force-kills it
    // instead of orphaning a container on the port.
    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> LIVE.forEach(Process::destroyForcibly), "sky-run-reaper"));
    }

    private final Process process;
    private final int port;
    private final AtomicBoolean stopping = new AtomicBoolean();

    private RunProcess(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    /**
     * Call {@code onCrash} if the application exits on its own. A relaunch or a Ctrl-C stops it
     * deliberately, and those are not crashes — only an exit nobody asked for is worth reporting.
     */
    public void onCrash(Runnable onCrash) {
        process.onExit().thenRun(() -> {
            if (!stopping.get()) {
                onCrash.run();
            }
        });
    }

    /** Start the application described by {@code plan} and wait until it is serving. */
    public static RunProcess launch(RunPlan plan, Duration timeout, PrintStream out) throws IOException {
        Process process = new ProcessBuilder(plan.command())
                .directory(plan.directory().toFile())
                .redirectErrorStream(true)
                .start();
        LIVE.add(process);

        // A single reader drains all child output: it completes when it sees the ready marker, and
        // from then on the application's log is the user's log — this is a running app, not a build.
        CompletableFuture<Integer> ready = new CompletableFuture<>();
        Deque<String> tail = new ArrayDeque<>();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int at = line.indexOf(plan.readyMarker());
                    if (at >= 0 && !ready.isDone()) {
                        ready.complete(Integer.parseInt(line.substring(at + plan.readyMarker().length()).trim()));
                    } else if (ready.isDone()) {
                        out.println(line);
                    } else {
                        remember(tail, line);
                    }
                }
            } catch (IOException | NumberFormatException e) {
                ready.completeExceptionally(e);
            } finally {
                ready.completeExceptionally(
                        new IOException("the application stopped before it was serving" + explain(tail)));
            }
        }, "sky-run-reader");
        drain.setDaemon(true);
        drain.start();

        try {
            return new RunProcess(process, ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            LIVE.remove(process);
            process.destroyForcibly();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException(cause.getMessage() == null
                    ? "the application did not start in time" + explain(tail) : cause.getMessage(), cause);
        }
    }

    /** The port the application bound. */
    public int port() {
        return port;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        stopping.set(true);
        LIVE.remove(process);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void remember(Deque<String> tail, String line) {
        synchronized (tail) {
            tail.addLast(line);
            if (tail.size() > TAIL_LINES) {
                tail.removeFirst();
            }
        }
    }

    /** The last of the output the launch never got past — the only clue to why it failed. */
    private static String explain(Deque<String> tail) {
        synchronized (tail) {
            return tail.isEmpty() ? "" : ":\n" + String.join("\n", tail);
        }
    }
}
