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

package com.adeptum.skylang.cli;

import com.adeptum.skylang.Pipeline;
import com.adeptum.skylang.config.ConfigException;
import com.adeptum.skylang.config.ConfigStore;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.SkyParseException;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.LangChain4jLlm;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.synth.SynthException;
import com.adeptum.skylang.types.CheckException;
import com.adeptum.skylang.types.TypeChecker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Callable;

/**
 * {@code sky tdd} — the red-green loop as a watch mode: each save re-checks the file and
 * rebuilds, which regenerates exactly the methods whose specifications changed, until their
 * examples, specs and contracts hold; what passes is frozen. Errors keep the watch alive —
 * a red loop is feedback, not an exit.
 */
@Command(mixinStandardHelpOptions = true, name = "tdd", description = "Watch the file and regenerate edited methods until their tests pass.")
public final class TddCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<file.sky>",
            description = "The SkyLang source file to watch. Default: the directory's sole .sky file.")
    Path file;

    @Option(names = "--profile",
            description = "Target profile (default: the sky.project manifest, else jvm-jakarta).")
    String profile;

    @Option(names = "--once", description = "Run a single cycle instead of watching (for scripts).")
    boolean once;

    @Option(names = "--attempts", description = "Candidates per method before giving up (default: 5).",
            defaultValue = "5")
    int attempts;

    @Override
    public Integer call() {
        try {
            file = SourceFiles.resolve(file);
        } catch (ConfigException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }

        int first = cycle();
        if (once) {
            return first;
        }
        System.out.println("  watching " + file + " ...");
        Path dir = file.toAbsolutePath().getParent();
        String name = file.getFileName().toString();
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            while (true) {
                WatchKey key = watcher.take();
                boolean touched = key.pollEvents().stream()
                        .anyMatch(event -> name.equals(String.valueOf(event.context())));
                key.reset();
                if (touched) {
                    Thread.sleep(150);   // debounce an editor's write burst
                    cycle();
                    System.out.println("  watching " + file + " ...");
                }
            }
        } catch (IOException e) {
            System.err.println("tdd: file watching failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** One red-green cycle: parse, check, rebuild. Every failure reports and returns. */
    private int cycle() {
        Ast.Module module;
        try {
            module = Parsing.parseUnit(file);
            new TypeChecker().check(module);
        } catch (SkyParseException | CheckException e) {
            System.out.println("  red   " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.out.println("  red   cannot read " + file + ": " + e.getMessage());
            return 1;
        }

        Path root = file.toAbsolutePath().getParent();
        Llm llm = new LangChain4jLlm(new ConfigStore()::resolve);
        try {
            ActiveProfile.Activation active = ActiveProfile.activate(profile, file, module);
            int code = new Pipeline(llm, active.profile().verifier(), Math.max(0, attempts - 1),
                    active.profile(), active.deps())
                    .build(module, root.resolve("sky.lock"),
                            root.resolve("build").resolve(active.profile().id()),
                            System.out, System.err);
            System.out.println(code == 0
                    ? "  green — examples, specs and contracts hold; fresh bodies frozen"
                    : "  red   — a clause was violated; adjust the cases or the spec");
            return code;
        } catch (ConfigException | CheckException | SynthException e) {
            System.out.println("  red   " + e.getMessage());
            return 1;
        }
    }
}
