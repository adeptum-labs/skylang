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
import com.adeptum.skylang.verify.Verifier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code sky build} — the full pipeline: parse, type-check, synthesize/verify unfrozen bodies,
 * freeze, and emit the target project. The model is called only for methods whose spec changed;
 * credentials (from {@code sky onboard} / env) are resolved lazily, so a fully-frozen build needs
 * none.
 */
@Command(name = "build", description = "Synthesize, verify, freeze, and emit the target artifact.")
public final class BuildCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<file.sky>", description = "The SkyLang source file to build.")
    Path file;

    @Option(names = "--profile",
            description = "Target profile (default: the sky.project manifest, else jvm-jakarta).")
    String profile;

    @Option(names = "--recheck",
            description = "Re-verify the staged project (tests, render checks, visual gate) even when "
                    + "everything is frozen. Offline — never calls the model.")
    boolean recheck;

    @Option(names = "--attempts", description = "Candidates per method before giving up (default: 5).",
            defaultValue = "5")
    int attempts;

    @Override
    public Integer call() {
        Ast.Module module;
        try {
            module = Parsing.parseFile(file);
            new TypeChecker().check(module);
        } catch (SkyParseException | CheckException e) {
            System.err.println("error [frontend]: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("error: cannot read " + file + ": " + e.getMessage());
            return 1;
        }

        Path root = file.toAbsolutePath().getParent();
        Path lockPath = root.resolve("sky.lock");

        ActiveProfile.Activation active;
        try {
            active = ActiveProfile.activate(profile, file, module);
        } catch (ConfigException | CheckException e) {
            System.err.println("error [frontend]: " + e.getMessage());
            return 1;
        }

        Llm llm = new LangChain4jLlm(new ConfigStore()::resolve);   // resolved lazily on first synth
        Verifier verifier = active.profile().verifier();

        try {
            return new Pipeline(llm, verifier, Math.max(0, attempts - 1), active.profile(), active.deps())
                    .build(module, lockPath, root.resolve("build").resolve(active.profile().id()),
                            System.out, System.err, recheck);
        } catch (ConfigException | SynthException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }
}
