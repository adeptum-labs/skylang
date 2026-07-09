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
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;

/**
 * Orchestrates a {@code sky preview} session: stage the module's views, launch the long-lived
 * preview container, serve a browser studio, and hot-reload on save. Frozen views are staged with
 * no model call; the container compiles and serves the project.
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
        PreviewProcess preview = stageAndLaunch(module, lockPath, buildDir, mavenCommand, out, err);
        StudioServer studio = new StudioServer(controlPort, preview.appPort(), viewNames(module));
        return new Handle(preview, studio);
    }

    /** Serve the views, hot-reloading on every save, until interrupted (Ctrl-C). */
    public int run(Ast.Module module, Path sourceFile, Path lockPath, Path buildDir, int controlPort,
                   String mavenCommand, PrintStream out, PrintStream err) {
        if (module.views().isEmpty()) {
            err.println("error: " + module.name() + " has no views to preview");
            return 1;
        }

        out.println("staging and starting the preview container (the first run may take a moment)...");
        PreviewProcess preview;
        StudioServer studio;
        try {
            preview = stageAndLaunch(module, lockPath, buildDir, mavenCommand, out, err);
            studio = new StudioServer(controlPort, preview.appPort(), viewNames(module));
        } catch (IOException e) {
            err.println("error: could not start the preview server: " + e.getMessage());
            return 1;
        }

        PreviewProcess[] container = {preview};
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            studio.close();
            container[0].close();
        }));

        String url = "http://localhost:" + studio.port() + "/";
        out.println("preview ready — " + url + "  (edit " + sourceFile.getFileName() + " to reload; Ctrl-C to stop)");
        openBrowser(url, out);

        watch(sourceFile, lockPath, buildDir, mavenCommand, container, studio, out, err);
        return 0;
    }

    private PreviewProcess stageAndLaunch(Ast.Module module, Path lockPath, Path buildDir,
                                          String mavenCommand, PrintStream out, PrintStream err) throws IOException {
        int staged = new Pipeline(llm, STAGE_ONLY).build(module, lockPath, buildDir, out, err);
        if (staged != 0) {
            throw new IOException("could not stage the views for preview");
        }
        return PreviewProcess.launch(buildDir, mavenCommand, module.name(), Duration.ofMinutes(5));
    }

    /** Watch the source file and relaunch the container on each change; the studio stays up. */
    private void watch(Path sourceFile, Path lockPath, Path buildDir, String mavenCommand,
                       PreviewProcess[] container, StudioServer studio, PrintStream out, PrintStream err) {
        Path dir = sourceFile.toAbsolutePath().getParent();
        String name = sourceFile.getFileName().toString();
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            while (true) {
                WatchKey key = watcher.take();
                boolean touched = key.pollEvents().stream()
                        .anyMatch(event -> name.equals(String.valueOf(event.context())));
                key.reset();
                if (touched) {
                    Thread.sleep(150);   // debounce an editor's write burst
                    reload(sourceFile, lockPath, buildDir, mavenCommand, container, studio, out, err);
                }
            }
        } catch (IOException e) {
            err.println("preview: file watching failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reload(Path sourceFile, Path lockPath, Path buildDir, String mavenCommand,
                        PreviewProcess[] container, StudioServer studio, PrintStream out, PrintStream err) {
        try {
            Ast.Module updated = Parsing.parseFile(sourceFile);
            new TypeChecker().check(updated);
            PreviewProcess next = stageAndLaunch(updated, lockPath, buildDir, mavenCommand, out, err);
            container[0].close();
            container[0] = next;
            studio.setAppPort(next.appPort());
            out.println("preview: reloaded " + sourceFile.getFileName());
        } catch (Exception e) {
            err.println("preview: reload failed (keeping the last good view): " + e.getMessage());
        }
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
