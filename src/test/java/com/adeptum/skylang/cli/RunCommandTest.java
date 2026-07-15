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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sky run} refuses before it builds anything: a module with nothing to show, or a missing
 * artifact it was told not to build, must fail with an error the user can act on.
 */
class RunCommandTest {

    private static final String WITH_VIEW = """
            module shop
            entity Product { id Int  name Text }
            service Catalog {
              all() -> [Product]  intent "Every product."
            }
            view ProductList at "/products" {
              shows  Catalog.all() as a table of (name)
            }
            """;

    private static final String HEADLESS = """
            module shop
            entity Product { id Int  name Text }
            service Catalog {
              all() -> [Product]  intent "Every product."
            }
            """;

    @TempDir
    Path dir;

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void captureErr() {
        originalErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreErr() {
        System.setErr(originalErr);
    }

    private Path sourceFile(String source) throws IOException {
        Path file = dir.resolve("shop.sky");
        Files.writeString(file, source);
        return file;
    }

    @Test
    void aModuleWithNoViewsHasNothingToBringUp() throws IOException {
        Path file = sourceFile(HEADLESS);

        int exit = new CommandLine(new SkyCli()).execute("run", file.toString());

        assertEquals(1, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("no views to run"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void skippingTheBuildWithNoArtifactSaysWhereItLooked() throws IOException {
        Path file = sourceFile(WITH_VIEW);

        int exit = new CommandLine(new SkyCli()).execute("run", "--skip-build", file.toString());

        assertEquals(2, exit);
        String transcript = err.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("no artifact at"), transcript);
        assertTrue(transcript.contains("shop.war"), "the error names the artifact it wanted: " + transcript);
        assertTrue(transcript.contains("--skip-build"), "and how to get one: " + transcript);
    }
}
