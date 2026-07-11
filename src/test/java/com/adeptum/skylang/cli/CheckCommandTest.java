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
 * {@code sky check} is the project's checkpoint: it narrates what the hard layer contains —
 * entities, refined types, closed value sets — and confirms that no model was consulted.
 */
class CheckCommandTest {

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
    void theCheckpointListsTheHardLayer() throws IOException {
        Path file = dir.resolve("shop.sky");
        Files.writeString(file, """
                module shop
                type Quantity = Int(1..)
                entity Product { id Int @id  name Text(1..120)  price Money  stock Int @min(0) }
                entity Role { name Text @id  values Member, Staff, Admin }
                entity OrderStatus { name Text @id  values Draft, Placed, Shipped, Cancelled }
                """);

        int exit = new CommandLine(new SkyCli()).execute("check", file.toString());

        assertEquals(0, exit);
        String transcript = out.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("parsing shop.sky ..."), transcript);
        assertTrue(transcript.contains("entities: Product, Role, OrderStatus"), transcript);
        assertTrue(transcript.contains("refined types: Quantity"), transcript);
        assertTrue(transcript.contains("values: Role (3), OrderStatus (4)"), transcript);
        assertTrue(transcript.contains("type-checking hard layer ..."), transcript);
        assertTrue(transcript.contains("no model calls; nothing generated."), transcript);
    }

    @Test
    void servicesJoinTheListingWhenTheyExist() throws IOException {
        Path file = dir.resolve("shop.sky");
        Files.writeString(file, """
                module shop
                entity Product { id Int @id  stock Int @min(0) }
                service Catalog {
                  restock(p Product, units Int) -> Product  intent "x"
                  rename(p Product) -> Product              intent "x"
                }
                """);

        int exit = new CommandLine(new SkyCli()).execute("check", file.toString());

        assertEquals(0, exit);
        String transcript = out.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("services: Catalog (2 methods)"), transcript);
    }

    @Test
    void aLongEntityListWrapsWithAlignedContinuation() throws IOException {
        Path file = dir.resolve("shop.sky");
        Files.writeString(file, """
                module shop
                entity Product { id Int @id }
                entity User { id Int @id }
                entity Role { name Text @id  values Member }
                entity LineItem { quantity Int }
                entity Order { id Int @id }
                entity OrderStatus { name Text @id  values Draft }
                entity Country { code Text @id }
                entity Category { name Text @id }
                """);

        int exit = new CommandLine(new SkyCli()).execute("check", file.toString());

        assertEquals(0, exit);
        String transcript = out.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains(
                "entities: Product, User, Role, LineItem, Order, OrderStatus,\n            Country, Category"),
                "the entity list wraps with a continuation aligned under the first name:\n" + transcript);
    }
}
