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

package com.adeptum.skylang.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * The ts-node verification harness: compile the staged project with tsc, then run the
 * generated node:test suite. The TypeScript compiler is resolved from {@code SKY_TSC}
 * (a path to the tsc executable) or {@code tsc} on PATH; node comes from PATH.
 */
public final class NodeVerifier implements Verifier {

    private final String tsc;
    private final String node;

    public NodeVerifier() {
        this(System.getenv().getOrDefault("SKY_TSC", "tsc"),
                System.getenv().getOrDefault("SKY_NODE", "node"));
    }

    public NodeVerifier(String tsc, String node) {
        this.tsc = tsc;
        this.node = node;
    }

    @Override
    public VerificationResult verify(Path stagedProjectDir) {
        VerificationResult compiled = run(stagedProjectDir, List.of(tsc, "-p", "."),
                "could not run '" + tsc + "' (set SKY_TSC or put tsc on PATH)");
        if (!compiled.passed()) {
            // tsc diagnostics count as a failed compile of the staged project.
            return VerificationResult.fail("COMPILATION ERROR\n" + compiled.output());
        }
        return run(stagedProjectDir, List.of(node, "--test", "dist/"),
                "could not run '" + node + "' (set SKY_NODE or put node on PATH)");
    }

    private VerificationResult run(Path dir, List<String> command, String startupHint) {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return exit == 0 ? VerificationResult.pass() : VerificationResult.fail(output);
        } catch (IOException e) {
            return VerificationResult.fail(startupHint + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VerificationResult.fail("verification interrupted");
        }
    }
}
