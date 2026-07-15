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
import com.adeptum.skylang.config.Manifest;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.SkyParseException;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.run.RunSession;
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
 * {@code sky run} — build the module and bring the application up in a browser. It is the full
 * {@code sky build}, and then the artifact that build emitted is served: the war, deployed to a
 * container, opened at its front door. Where {@code sky preview} shows the views in a studio, this
 * runs the application itself.
 */
@Command(mixinStandardHelpOptions = true, name = "run",
        description = "Build the application and bring it up in a browser.")
public final class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<file.sky>",
            description = "The SkyLang source file to run. Default: the directory's sole .sky file.")
    Path file;

    @Option(names = "--profile",
            description = "Target profile (default: the sky.project manifest, else jvm-jakarta).")
    String profile;

    @Option(names = "--port", description = "Port to serve the application on (default: 8080).",
            defaultValue = "8080")
    int port;

    @Option(names = "--skip-build",
            description = "Serve the artifact already in the build directory, without synthesizing, "
                    + "verifying or packaging. Fast, but it may be stale.")
    boolean skipBuild;

    @Override
    public Integer call() {
        // The application is told which port to bind and reports that same port back, so there is
        // no ephemeral-port dance to get wrong: a port it cannot serve on is refused up front.
        if (port < 1 || port > 65535) {
            System.err.println("error: --port must be between 1 and 65535, not " + port);
            return 1;
        }
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

        Llm llm = new LangChain4jLlm(new ConfigStore()::resolve);   // resolved lazily; a frozen build needs no key

        try {
            Path buildDir = root.resolve("build").resolve(active.profile().id());
            String name = Manifest.load(root).map(Manifest::project).orElse(module.name());
            return new RunSession(llm, active.profile().verifier(), active.profile(), active.deps())
                    .run(module, file, lockPath, buildDir, name, port, skipBuild, System.out, System.err);
        } catch (CheckException e) {
            // The profile cannot host its own artifact — a portability error, like any other.
            System.err.println("error [frontend]: " + e.getMessage());
            return 1;
        } catch (ConfigException | SynthException e) {
            // Exit 3: generation could not reach a model — a configuration or provider error.
            System.err.println("error: " + e.getMessage());
            return 3;
        }
    }
}
