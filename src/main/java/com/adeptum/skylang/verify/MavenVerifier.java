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

/**
 * Runs the staged Maven project's test suite ({@code mvn -q -B test}) as the verification
 * harness (guide §10.1). Requires a JDK and Maven on PATH; the backend delegates the actual
 * compilation and test run here rather than doing it in-process.
 */
public final class MavenVerifier implements Verifier {

    private final String mavenCommand;

    public MavenVerifier() {
        this("mvn");
    }

    public MavenVerifier(String mavenCommand) {
        this.mavenCommand = mavenCommand;
    }

    @Override
    public VerificationResult verify(Path stagedProjectDir) {
        ProcessBuilder pb = new ProcessBuilder(mavenCommand, "-q", "-B", "test")
                .directory(stagedProjectDir.toFile())
                .redirectErrorStream(true);
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return exit == 0 ? VerificationResult.pass() : VerificationResult.fail(output);
        } catch (IOException e) {
            return VerificationResult.fail("could not run '" + mavenCommand
                    + "' (is Maven on PATH?): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VerificationResult.fail("verification interrupted");
        }
    }
}
