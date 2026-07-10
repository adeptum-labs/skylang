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
import com.adeptum.skylang.verify.MavenVerifier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code sky freeze} — the deliberate expensive build: discard the lock and regenerate,
 * re-verify and re-freeze every body (for example, to adopt an improved model). Native
 * blocks are re-verified but never rewritten.
 */
@Command(name = "freeze", description = "Regenerate and re-verify every body, rewriting sky.lock.")
public final class FreezeCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<file.sky>", description = "The SkyLang source file to refreeze.")
    Path file;

    @Option(names = "--profile",
            description = "Target profile (default: the sky.project manifest, else jvm-jakarta).")
    String profile;

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

        com.adeptum.skylang.backend.Profile active;
        try {
            active = ActiveProfile.activate(profile, file, module);
        } catch (ConfigException | CheckException e) {
            System.err.println("error [frontend]: " + e.getMessage());
            return 1;
        }

        try {
            Files.deleteIfExists(lockPath);
        } catch (IOException e) {
            System.err.println("error: cannot clear " + lockPath + ": " + e.getMessage());
            return 1;
        }

        Llm llm = new LangChain4jLlm(new ConfigStore()::resolve);
        try {
            return new Pipeline(llm, new MavenVerifier(), Math.max(0, attempts - 1), active)
                    .build(module, lockPath, root.resolve("build").resolve(active.id()),
                            System.out, System.err);
        } catch (ConfigException | SynthException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }
}
