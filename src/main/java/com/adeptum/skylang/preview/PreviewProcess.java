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

package com.adeptum.skylang.preview;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Launches and owns the long-lived preview container. The native CLI cannot host a servlet
 * container, so the generated {@code PreviewServer} runs in a Maven {@code exec:java} subprocess
 * that boots one embedded TomEE and serves the staged views; this class starts it, learns the port
 * it bound from its {@code PREVIEW READY} line, and tears it down.
 */
public final class PreviewProcess implements AutoCloseable {

    private static final String READY_MARKER = "PREVIEW READY app=";

    private final Process process;
    private final int appPort;

    private PreviewProcess(Process process, int appPort) {
        this.process = process;
        this.appPort = appPort;
    }

    /** Start the preview server for the module in {@code buildDir} and wait until it is serving. */
    public static PreviewProcess launch(Path buildDir, String mavenCommand, String pkg, Duration timeout)
            throws IOException {
        Process process = new ProcessBuilder(mavenCommand, "-B", "test-compile", "exec:java",
                "-Dexec.mainClass=" + pkg + ".PreviewServer",
                "-Dexec.classpathScope=test")
                .directory(buildDir.toFile())
                .redirectErrorStream(true)
                .start();

        // A single reader drains all child output, completing when it sees the readiness marker.
        CompletableFuture<Integer> ready = new CompletableFuture<>();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int at = line.indexOf(READY_MARKER);
                    if (at >= 0 && !ready.isDone()) {
                        ready.complete(Integer.parseInt(line.substring(at + READY_MARKER.length()).trim()));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                ready.completeExceptionally(e);
            } finally {
                ready.completeExceptionally(new IOException("preview server stopped before it was ready"));
            }
        }, "sky-preview-reader");
        drain.setDaemon(true);
        drain.start();

        try {
            int port = ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new PreviewProcess(process, port);
        } catch (Exception e) {
            process.destroyForcibly();
            throw new IOException("preview server did not become ready in time", e);
        }
    }

    /** The port the embedded container bound; views are served at {@code /app/<View>.xhtml}. */
    public int appPort() {
        return appPort;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
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
}
