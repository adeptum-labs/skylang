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

import com.adeptum.skylang.config.ConfigException;
import com.adeptum.skylang.config.ConfigStore;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.SkyParseException;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.preview.PreviewSession;
import com.adeptum.skylang.synth.LangChain4jLlm;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.synth.SynthException;
import com.adeptum.skylang.types.CheckException;
import com.adeptum.skylang.types.TypeChecker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code sky preview} — serve the module's views live in a browser. The views are staged and served
 * by a long-lived embedded container in a subprocess; frozen views need no model credentials.
 */
@Command(mixinStandardHelpOptions = true, name = "preview", description = "Serve the module's views live in a browser studio.")
public final class PreviewCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<file.sky>",
            description = "The SkyLang source file to preview. Default: the directory's sole .sky file.")
    Path file;

    @Option(names = "--profile",
            description = "Target profile (default: the sky.project manifest, else jvm-jakarta).")
    String profile;

    @Option(names = "--port", description = "Studio control port (default: 4599).", defaultValue = "4599")
    int port;

    @Override
    public Integer call() {
        try {
            file = SourceFiles.resolve(file);
        } catch (ConfigException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }

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

        Llm llm = new LangChain4jLlm(new ConfigStore()::resolve);   // resolved lazily; frozen views need no key

        try {
            Path buildDir = root.resolve("build").resolve(active.profile().id());
            return new PreviewSession(llm, active.profile().verifier(), active.profile(), active.deps())
                    .run(module, file, lockPath, buildDir, port, "mvn", System.out, System.err);
        } catch (ConfigException | SynthException e) {
            // Exit 3: generation could not reach a model — a configuration or provider error.
            System.err.println("error: " + e.getMessage());
            return 3;
        }
    }
}
