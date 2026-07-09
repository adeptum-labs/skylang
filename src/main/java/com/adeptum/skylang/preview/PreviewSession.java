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

import com.adeptum.skylang.Pipeline;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Orchestrates a {@code sky preview} session: stage the module's views, launch the long-lived
 * preview container, and open the studio in a browser. Frozen views are staged with no model call;
 * the container compiles and serves the project.
 */
public final class PreviewSession {

    /** Staging skips the Maven verifier — the preview server compiles and serves the project itself. */
    private static final Verifier STAGE_ONLY = dir -> VerificationResult.pass();

    private final Llm llm;

    public PreviewSession(Llm llm) {
        this.llm = llm;
    }

    /** A running preview: the container subprocess and the studio server. Close to tear both down. */
    public record Handle(PreviewProcess preview, StudioServer studio) implements AutoCloseable {

        public int studioPort() {
            return studio.port();
        }

        public int appPort() {
            return preview.appPort();
        }

        @Override
        public void close() {
            studio.close();
            preview.close();
        }
    }

    /** Stage the views, launch the container, and start the studio — non-blocking. */
    public Handle start(Ast.Module module, Path lockPath, Path buildDir, int controlPort,
                        String mavenCommand, PrintStream out, PrintStream err) throws IOException {
        int staged = new Pipeline(llm, STAGE_ONLY).build(module, lockPath, buildDir, out, err);
        if (staged != 0) {
            throw new IOException("could not stage the views for preview");
        }
        PreviewProcess preview = PreviewProcess.launch(buildDir, mavenCommand, module.name(), Duration.ofMinutes(5));
        StudioServer studio = new StudioServer(controlPort, preview.appPort(), viewNames(module));
        return new Handle(preview, studio);
    }

    /** Serve the views until interrupted (Ctrl-C). */
    public int run(Ast.Module module, Path lockPath, Path buildDir, int controlPort,
                   String mavenCommand, PrintStream out, PrintStream err) {
        if (module.views().isEmpty()) {
            err.println("error: " + module.name() + " has no views to preview");
            return 1;
        }

        out.println("starting the preview container (the first run may take a moment)...");
        Handle handle;
        try {
            handle = start(module, lockPath, buildDir, controlPort, mavenCommand, out, err);
        } catch (IOException e) {
            err.println("error: could not start the preview server: " + e.getMessage());
            return 1;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(handle::close));

        String url = "http://localhost:" + handle.studioPort() + "/";
        out.println("preview ready — " + url + "  (Ctrl-C to stop)");
        openBrowser(url, out);
        try {
            new CountDownLatch(1).await();   // serve until interrupted
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static List<String> viewNames(Ast.Module module) {
        return module.views().stream().map(Ast.View::name).toList();
    }

    private static void openBrowser(String url, PrintStream out) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> command = os.contains("mac") ? List.of("open", url)
                : os.contains("win") ? List.of("cmd", "/c", "start", "", url)
                : List.of("xdg-open", url);
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            out.println("(open " + url + " in your browser)");
        }
    }
}
