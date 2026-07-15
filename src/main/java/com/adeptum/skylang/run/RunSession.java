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

import com.adeptum.skylang.Browser;
import com.adeptum.skylang.Pipeline;
import com.adeptum.skylang.Ticker;
import com.adeptum.skylang.backend.Profile;
import com.adeptum.skylang.backend.RunPlan;
import com.adeptum.skylang.deps.Budget;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates a {@code sky run} session: the module is built and packaged exactly as
 * {@code sky build} would, and the resulting artifact is served in a browser. Where
 * {@code sky preview} shows the views inside the studio, this runs the application itself — what
 * ships is what you see. Saving the source rebuilds and relaunches it; a rebuild that fails leaves
 * the running application alone.
 */
public final class RunSession implements AutoCloseable {

    /** Candidates per method the run build tries before giving up (retries = attempts − 1). */
    private static final int RUN_ATTEMPTS = 4;

    /** How long the application has to bind its port and report that it is serving. */
    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);

    private final Llm llm;
    private final Verifier verifier;
    private final Profile profile;
    private final Budget deps;
    private final Object lock = new Object();

    // Running-session state, established by run() and mutated by the watcher under `lock`.
    private Path sourceFile;
    private Path lockPath;
    private Path buildDir;
    private String projectName;
    private int port;
    private PrintStream out;
    private PrintStream err;
    private volatile RunProcess app;
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean crashed = new AtomicBoolean();

    public RunSession(Llm llm, Verifier verifier, Profile profile, Budget deps) {
        this.llm = llm;
        this.verifier = verifier;
        this.profile = profile;
        this.deps = deps;
    }

    /** The application a run brought up: the port it bound and the path its front door is at. */
    public record Handle(int port, String landingPath) {
    }

    /**
     * A launch that did not happen, carrying the exit code {@code sky run} should end with. A null
     * message means the failure has already been reported — the pipeline says what did not verify
     * far better than this can.
     */
    public static final class LaunchFailed extends Exception {

        private final int exitCode;

        LaunchFailed(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    /**
     * Build, package and serve — everything a run does except opening a browser and waiting. The
     * application keeps running until {@link #close}.
     *
     * @param skipBuild serve the artifact already in the build directory, without rebuilding it
     */
    public Handle open(Ast.Module module, Path sourceFile, Path lockPath, Path buildDir, String projectName,
                       int port, boolean skipBuild, PrintStream out, PrintStream err) throws LaunchFailed {
        if (module.views().isEmpty()) {
            throw new LaunchFailed(1, module.name() + " has no views to run");
        }
        synchronized (lock) {
            this.sourceFile = sourceFile;
            this.lockPath = lockPath;
            this.buildDir = buildDir;
            this.projectName = projectName;
            this.port = port;
            this.out = out;
            this.err = err;

            RunPlan plan = profile.runPlan(module, projectName, buildDir, port);
            if (skipBuild) {
                if (!Files.exists(plan.artifact())) {
                    throw new LaunchFailed(2, "no artifact at " + plan.artifact()
                            + " — run `sky run` without --skip-build to build one");
                }
                out.println("serving " + plan.artifact() + " (--skip-build: not rebuilt)");
            } else {
                int code = buildAndPackage(module);
                if (code != 0) {
                    throw new LaunchFailed(code, null);   // the pipeline has already said what failed
                }
            }
            try {
                app = launch(plan);
            } catch (IOException e) {
                throw new LaunchFailed(2, e.getMessage());
            }
            return new Handle(app.port(), plan.landingPath());
        }
    }

    /**
     * Build, package, serve, and open a browser; block until Ctrl-C.
     *
     * @return the process exit code
     */
    public int run(Ast.Module module, Path sourceFile, Path lockPath, Path buildDir, String projectName,
                   int port, boolean skipBuild, PrintStream out, PrintStream err) {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        Handle handle;
        try {
            handle = open(module, sourceFile, lockPath, buildDir, projectName, port, skipBuild, out, err);
        } catch (LaunchFailed e) {
            if (e.getMessage() != null) {
                err.println("error: " + e.getMessage());
            }
            return e.exitCode();
        }

        String url = "http://localhost:" + handle.port() + handle.landingPath();
        out.println("running — " + url + "  (edit the file to rebuild; Ctrl-C to stop)");
        Browser.open(url, out);

        Thread watcher = new Thread(() -> watch(sourceFile), "sky-run-watch");
        watcher.setDaemon(true);
        watcher.start();

        try {
            stopSignal.await();   // until Ctrl-C fires the shutdown hook, or the application dies
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        close();
        return crashed.get() ? 2 : 0;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;   // idempotent: the normal exit and the shutdown hook may both call this
        }
        // Deliberately not under `lock`: this runs from the shutdown hook, and the watcher may hold
        // `lock` while a rebuild is in flight. Destroying the application makes that wait moot, so
        // Ctrl-C stops the run at once rather than after the rebuild. `app` is volatile so the
        // shutdown thread sees the current instance.
        RunProcess current = app;
        if (current != null) {
            current.close();
        }
        stopSignal.countDown();
    }

    // ----- machinery ---------------------------------------------------------

    /** Synthesize, verify and freeze exactly as {@code sky build} does, then package the artifact. */
    private int buildAndPackage(Ast.Module module) {
        int built = new Pipeline(llm, verifier, RUN_ATTEMPTS, profile, deps)
                .build(module, lockPath, buildDir, out, err, false);
        if (built != 0) {
            return built;   // the pipeline has already said what failed
        }
        return profile.emit(projectName, buildDir, out) ? 0 : 2;
    }

    /**
     * Start the application, and stop the whole run if it later dies on its own — a `sky run` still
     * sitting there with nothing serving would be a lie of omission.
     */
    private RunProcess launch(RunPlan plan) throws IOException {
        RunProcess launched;
        try (Ticker ticker = Ticker.start(out, "starting the application")) {
            launched = RunProcess.launch(plan, START_TIMEOUT, out);
        }
        launched.onCrash(() -> {
            err.println("run: the application stopped on its own.");
            crashed.set(true);
            stopSignal.countDown();
        });
        return launched;
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
                    rebuild();
                }
            }
        } catch (IOException e) {
            err.println("run: file watching failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Rebuild, repackage, and relaunch. The running application is only stopped once the new
     * artifact exists, so a broken edit costs nothing — the old one keeps serving.
     */
    private void rebuild() {
        synchronized (lock) {
            if (closed.get()) {
                return;
            }
            try {
                out.println("run: " + sourceFile.getFileName() + " changed — rebuilding.");
                Ast.Module module = Parsing.parse(Files.readString(sourceFile), sourceFile.getFileName().toString());
                new TypeChecker().check(module);
                if (module.views().isEmpty()) {
                    throw new IOException(module.name() + " has no views to run");
                }
                int code = buildAndPackage(module);
                if (code != 0) {
                    throw new IOException("the build failed (see the errors above)");
                }
                RunPlan plan = profile.runPlan(module, projectName, buildDir, port);
                app.close();   // free the port only once there is something to put back on it
                RunProcess next;
                try {
                    next = launch(plan);
                } catch (IOException e) {
                    // Past this point the old application is already gone, so nothing is serving —
                    // saying it is unchanged would be a lie.
                    err.println("run: the rebuilt application did not start, and the previous one has "
                            + "stopped: " + e.getMessage() + " — save the file again to retry.");
                    return;
                }
                // A rebuild takes minutes, and close() runs without `lock` so that Ctrl-C need not
                // wait for it. That means the session may have shut down while this one was
                // starting: close() has already been and gone, and would never see this process.
                if (closed.get()) {
                    next.close();
                    return;
                }
                app = next;
                out.println("run: reloaded — http://localhost:" + next.port() + plan.landingPath());
            } catch (Exception e) {
                err.println("run: rebuild failed (the running application is unchanged): " + e.getMessage());
            }
        }
    }
}
