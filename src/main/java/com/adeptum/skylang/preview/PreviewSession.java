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
import com.adeptum.skylang.Ticker;
import com.adeptum.skylang.backend.Profile;
import com.adeptum.skylang.deps.Budget;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.SourceEditor;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.AppearsCompiler;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.Verifier;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates a {@code sky preview} session and its edit loop. It stages the module's views, serves
 * them from a long-lived container, and lets the studio reshape a view in natural language: an edit
 * compiles into {@code appears} predicates, re-synthesizes the view, and relaunches to show it;
 * Accept freezes and writes the predicates back into the source; Reject reverts. Frozen views stage
 * with no model call; the model runs only while editing.
 */
public final class PreviewSession implements EditHandler, AutoCloseable {

    /** Candidates per method the preview build tries before giving up (retries = attempts − 1). */
    private static final int PREVIEW_ATTEMPTS = 4;

    private final Llm llm;
    private final Verifier verifier;
    private final Profile profile;
    private final Budget deps;
    private final AppearsCompiler appearsCompiler;
    private final Object lock = new Object();

    // Running-session state, established by run() and mutated by the edit loop under `lock`.
    private Path sourceFile;
    private Path lockPath;
    private Path buildDir;
    private String mavenCommand;
    private PrintStream out;
    private PrintStream err;
    private String activeSource;        // the .sky text the container currently reflects
    private String savedSource;         // the .sky text on disk (last accepted)
    private String lastAppliedSource;   // suppresses the watcher's echo of our own writes
    private volatile PreviewProcess container;
    private volatile StudioServer studio;
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    public PreviewSession(Llm llm, Verifier verifier, Profile profile, Budget deps) {
        this.llm = llm;
        this.verifier = verifier;
        this.profile = profile;
        this.deps = deps;
        this.appearsCompiler = new AppearsCompiler(llm);
    }

    /** The bound ports of a running preview: the studio control port and the container app port. */
    public record Handle(int studioPort, int appPort) {
    }

    /** Stage the views, launch the container, and start the studio with the edit loop — non-blocking. */
    public Handle open(Ast.Module module, Path sourceFile, Path lockPath, Path buildDir, int controlPort,
                       String mavenCommand, PrintStream out, PrintStream err) throws IOException {
        synchronized (lock) {
            this.sourceFile = sourceFile;
            this.lockPath = lockPath;
            this.buildDir = buildDir;
            this.mavenCommand = mavenCommand;
            this.out = out;
            this.err = err;
            this.activeSource = Files.readString(sourceFile);
            this.savedSource = activeSource;
            this.lastAppliedSource = activeSource;
            this.container = stageAndLaunch(module, true, out, err);   // full verify before launching
            this.studio = new StudioServer(controlPort, container.appPort(), viewNames(module), this, true);
            studio.onBrowserGone(stopSignal::countDown);   // closing the studio window stops the preview
            return new Handle(studio.port(), container.appPort());
        }
    }

    /** The port the current container bound (it changes across edits/reloads). */
    public int appPort() {
        synchronized (lock) {
            return container.appPort();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;   // idempotent: the normal exit and the shutdown hook may both call this
        }
        // Deliberately not under `lock`. This runs from the JVM shutdown hook, and the main thread
        // may hold `lock` while blocked in a launch that is waiting for the container to report
        // ready. Waiting for the lock here would freeze Ctrl-C at "staged: ..." until that launch
        // times out. Destroying the container instead makes the wait fail fast, so the preview stops
        // at once. The fields are volatile so the shutdown thread sees the latest instances.
        StudioServer currentStudio = studio;
        if (currentStudio != null) {
            currentStudio.close();
        }
        PreviewProcess currentContainer = container;
        if (currentContainer != null) {
            currentContainer.close();
        }
    }

    /** Serve the views with the edit loop, hot-reloading on save, until interrupted (Ctrl-C). */
    public int run(Ast.Module module, Path sourceFile, Path lockPath, Path buildDir, int controlPort,
                   String mavenCommand, PrintStream out, PrintStream err) {
        if (module.views().isEmpty()) {
            err.println("error: " + module.name() + " has no views to preview");
            return 1;
        }
        out.println("staging and starting the preview container (the first run may take a moment)...");
        Handle handle;
        try {
            handle = open(module, sourceFile, lockPath, buildDir, controlPort, mavenCommand, out, err);
        } catch (IOException e) {
            err.println("error: could not start the preview server: " + e.getMessage());
            return 1;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));

        String url = "http://localhost:" + handle.studioPort() + "/";
        out.println("preview ready — " + url
                + "  (edit in the browser or the file; close the window or Ctrl-C to stop)");
        openBrowser(url, out);

        Thread watcher = new Thread(() -> watch(sourceFile), "sky-preview-watch");
        watcher.setDaemon(true);
        watcher.start();

        try {
            stopSignal.await();   // until the studio window closes (or Ctrl-C fires the shutdown hook)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        out.println("preview: window closed — stopping.");
        close();
        return 0;
    }

    // ----- edit loop ---------------------------------------------------------

    @Override
    public EditResult edit(String viewName, String instruction) {
        synchronized (lock) {
            try {
                Ast.Module base = checked(activeSource);
                Ast.View view = base.views().stream()
                        .filter(v -> v.name().equals(viewName)).findFirst().orElse(null);
                if (view == null) {
                    return EditResult.error("no view named " + viewName);
                }
                List<String> lines = appearsCompiler.compile(view, instruction);
                if (lines.isEmpty()) {
                    return EditResult.error("couldn't express that as a structural change");
                }
                String next = SourceEditor.addAppears(activeSource, viewName, lines);
                relaunch(checked(next));   // re-synthesizes the view and gates it before showing
                activeSource = next;
                return EditResult.ok("applied: " + String.join("; ", lines));
            } catch (Exception e) {
                return EditResult.error("edit failed: " + e.getMessage());
            }
        }
    }

    @Override
    public EditResult accept() {
        synchronized (lock) {
            if (activeSource.equals(savedSource)) {
                return EditResult.error("nothing to accept");
            }
            try {
                lastAppliedSource = activeSource;   // suppress the watcher echo of this write
                Files.writeString(sourceFile, activeSource);
                savedSource = activeSource;
                return EditResult.ok("saved to " + sourceFile.getFileName());
            } catch (IOException e) {
                return EditResult.error("could not write " + sourceFile.getFileName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public EditResult reject() {
        synchronized (lock) {
            if (activeSource.equals(savedSource)) {
                return EditResult.error("nothing to revert");
            }
            try {
                relaunch(checked(savedSource));
                activeSource = savedSource;
                return EditResult.ok("reverted");
            } catch (Exception e) {
                return EditResult.error("revert failed: " + e.getMessage());
            }
        }
    }

    @Override
    public String spec(String viewName) {
        synchronized (lock) {
            try {
                return Parsing.parse(activeSource, "preview.sky").views().stream()
                        .filter(v -> v.name().equals(viewName)).findFirst()
                        .map(SpecJson::of).orElse(null);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Apply a deterministic structured change (no model), then re-render — mirrors {@link #edit}. */
    @Override
    public EditResult apply(StructuredChange change) {
        synchronized (lock) {
            try {
                Ast.Module base = checked(activeSource);
                if (base.views().stream().noneMatch(v -> v.name().equals(change.view()))) {
                    return EditResult.error("no view named " + change.view());
                }
                String next = change.applyTo(activeSource);
                relaunch(checked(next));   // re-render and gate it before showing
                activeSource = next;
                return EditResult.ok("set: " + change.describe());
            } catch (Exception e) {
                return EditResult.error("change failed: " + e.getMessage());
            }
        }
    }

    // ----- machinery ---------------------------------------------------------

    /**
     * Synthesize any unbuilt bodies and verify the staged project the same way {@code sky build}
     * does, so the preview only ever launches code that compiles and passes. With {@code recheck}
     * the whole project is re-verified even when every body is frozen, which catches a stale or
     * broken {@code sky.lock}. A verification failure aborts before the container is launched.
     */
    private PreviewProcess stageAndLaunch(Ast.Module module, boolean recheck, PrintStream out, PrintStream err)
            throws IOException {
        int staged = new Pipeline(llm, verifier, PREVIEW_ATTEMPTS, profile, deps)
                .preview().build(module, lockPath, buildDir, out, err, recheck);
        if (staged != 0) {
            throw new IOException("the project did not verify — it does not compile, or a contract, "
                    + "test, or view check failed (see the errors above). Fix the spec, or run "
                    + "`sky freeze` to regenerate frozen bodies, then preview again. "
                    + "(the studio was not launched)");
        }
        try (Ticker ticker = Ticker.start(out, "starting the preview container")) {
            return PreviewProcess.launch(buildDir, mavenCommand, module.name(), Duration.ofMinutes(5));
        }
    }

    /** Launch a fresh container for {@code module}, swap it in, and re-frame the studio. */
    private void relaunch(Ast.Module module) throws IOException {
        PreviewProcess next = stageAndLaunch(module, false, out, err);
        container.close();
        container = next;
        studio.setAppPort(next.appPort());
    }

    private void watch(Path sourceFile) {
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
                    reloadFromFile();
                }
            }
        } catch (IOException e) {
            err.println("preview: file watching failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reloadFromFile() {
        synchronized (lock) {
            try {
                String text = Files.readString(sourceFile);
                if (text.equals(lastAppliedSource)) {
                    return;   // our own accept-write; the container already reflects it
                }
                relaunch(checked(text));
                activeSource = text;
                savedSource = text;
                lastAppliedSource = text;
                out.println("preview: reloaded " + sourceFile.getFileName());
            } catch (Exception e) {
                err.println("preview: reload failed (keeping the last good view): " + e.getMessage());
            }
        }
    }

    private static Ast.Module checked(String source) {
        Ast.Module module = Parsing.parse(source, "preview.sky");
        new TypeChecker().check(module);
        return module;
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
