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
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.SkyParseException;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.synth.SynthException;
import com.adeptum.skylang.types.CheckException;
import com.adeptum.skylang.types.TypeChecker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code sky test} — run every example, spec and contract as a test suite, on demand and
 * offline: the bodies come from the frozen lock, the model is never consulted, and no
 * artifact is produced. The same suite that gates synthesis, re-run whole.
 */
@Command(mixinStandardHelpOptions = true, name = "test", description = "Run all contracts and examples as tests from the frozen lock.")
public final class TestCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<file.sky>",
            description = "The SkyLang source file to test. Default: the directory's sole .sky file.")
    Path file;

    @Option(names = "--profile",
            description = "Target profile (default: the sky.project manifest, else jvm-jakarta).")
    String profile;

    /** sky test never generates; an unfrozen method is the user's cue to build first. */
    private static final Llm OFFLINE = (system, user) -> {
        throw new SynthException("sky test is offline; an unfrozen method needs sky build first");
    };

    @Override
    public Integer call() {
        Ast.Module module;
        ActiveProfile.Activation active;
        try {
            file = SourceFiles.resolve(file);
            module = Parsing.parseUnit(file);
            new TypeChecker().check(module);
            active = ActiveProfile.activate(profile, file, module);
        } catch (SkyParseException | CheckException | ConfigException e) {
            System.err.println("error [frontend]: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("error: cannot read " + file + ": " + e.getMessage());
            return 1;
        }

        Path root = file.toAbsolutePath().getParent();
        Path buildDir = root.resolve("build").resolve(active.profile().id());
        try {
            int code = new Pipeline(OFFLINE, active.profile().verifier(), 0,
                    active.profile(), active.deps())
                    .build(module, root.resolve("sky.lock"), buildDir,
                            new PrintStream(OutputStream.nullOutputStream()), System.err, true);
            if (code != 0) {
                return code;
            }
        } catch (SynthException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        System.out.print(summary(module, active.profile().id(), buildDir));
        return 0;
    }

    /** The suite summary: staged test counts per service, the policies that held, all green. */
    static String summary(Ast.Module module, String profileId, Path buildDir) {
        StringBuilder sb = new StringBuilder();
        int width = module.services().stream().mapToInt(s -> s.name().length()).max().orElse(0) + 1;
        for (Ast.Service s : module.services()) {
            sb.append(String.format("  %-" + width + "s %2d tests  ✓%n",
                    s.name() + ":", stagedTests(module, s, profileId, buildDir)));
        }
        if (!module.policies().isEmpty()) {
            int bodies = module.services().stream().mapToInt(s -> s.methods().size()).sum();
            sb.append("  policies: ").append(module.policies().stream()
                            .map(Ast.Policy::name)
                            .reduce((a, b) -> a + ", " + b).orElse(""))
                    .append(" ✓ (").append(bodies).append(" bodies)\n");
        }
        return sb.append("  all green.\n").toString();
    }

    /** Ground truth for the count: the test methods actually staged for the service. */
    private static int stagedTests(Ast.Module module, Ast.Service s, String profileId, Path buildDir) {
        Path tests = profileId.equals("ts-node")
                ? buildDir.resolve("src").resolve(s.name() + ".test.ts")
                : buildDir.resolve("src/test/java").resolve(module.name()).resolve(s.name() + "Test.java");
        if (!Files.isRegularFile(tests)) {
            return 0;
        }
        try {
            Pattern marker = profileId.equals("ts-node")
                    ? Pattern.compile("^test\\(", Pattern.MULTILINE)
                    : Pattern.compile("@Test");
            Matcher m = marker.matcher(Files.readString(tests));
            int count = 0;
            while (m.find()) {
                count++;
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }
}
