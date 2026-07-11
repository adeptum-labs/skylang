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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The staged project is disposable; the lock is not. sky clean must honour that line. */
class CleanCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void captureOut() {
        originalOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    @Test
    void removesTheBuildDirectoryAndPreservesTheLock(@TempDir Path unused) throws IOException {
        Path file = dir.resolve("shop.sky");
        Files.writeString(file, "module shop\n");
        Files.writeString(dir.resolve("sky.lock"), "{}\n");
        Path staged = dir.resolve("build/jvm-jakarta/src");
        Files.createDirectories(staged);
        Files.writeString(staged.resolve("A.java"), "class A {}\n");

        int exit = new CommandLine(new SkyCli()).execute("clean", file.toString());

        assertEquals(0, exit);
        assertFalse(Files.exists(dir.resolve("build")), "the staged project is disposable");
        assertTrue(Files.exists(dir.resolve("sky.lock")), "the lock is never touched");
        String transcript = out.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("removed build/jvm-jakarta"), transcript);
        assertTrue(transcript.contains("sky.lock preserved"), transcript);
    }

    @Test
    void aProjectWithoutABuildDirectoryIsANoOp() throws IOException {
        Path file = dir.resolve("shop.sky");
        Files.writeString(file, "module shop\n");

        int exit = new CommandLine(new SkyCli()).execute("clean", file.toString());

        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("nothing to remove"),
                out.toString(StandardCharsets.UTF_8));
    }
}
