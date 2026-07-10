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
import com.adeptum.skylang.freeze.Hashing;
import com.adeptum.skylang.freeze.Lock;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
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
 * {@code sky why} is the audit view: it must answer "why does this code exist" from the
 * specification and the lock alone, offline, without ever calling the model.
 */
class WhyCommandTest {

    private static final String SHOP = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog {
              restock(p Product, units Int) -> Product
                intent  "Increase stock."
                requires units > 0
                ensures  result.stock == p.stock + units
                example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
            }
            """;

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

    private Path shopFile() throws IOException {
        Path file = dir.resolve("shop.sky");
        Files.writeString(file, SHOP);
        return file;
    }

    private String run(String... args) {
        new CommandLine(new SkyCli()).execute(args);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void explainsTheSpecificationOfAnUnfrozenMethod() throws IOException {
        String output = run("why", shopFile().toString(), "Catalog.restock");
        assertTrue(output.contains("Catalog.restock(p Product, units Int) -> Product"),
                "the signature should head the explanation:\n" + output);
        assertTrue(output.contains("intent    \"Increase stock.\""), output);
        assertTrue(output.contains("requires  units > 0"), output);
        assertTrue(output.contains("ensures   result.stock == p.stock + units"), output);
        assertTrue(output.contains("example   restock(Product(1, \"Notebook\", 5), 3)"), output);
        assertTrue(output.contains("unfrozen"), "without a lock the method is unfrozen:\n" + output);
    }

    @Test
    void showsTheFrozenBodyAndShortHashWhenTheSpecStillMatches() throws IOException {
        Path file = shopFile();
        Ast.Module module = Parsing.parseFile(file);
        Ast.Method method = module.services().get(0).methods().get(0);
        String hash = Pipeline.methodSpecHash(module, method);

        Lock lock = new Lock();
        lock.put("shop.Catalog.restock", new Lock.Entry(hash,
                "return new Product(p.id(), p.name(), p.stock() + units);"));
        lock.save(dir.resolve("sky.lock"));

        String output = run("why", file.toString(), "Catalog.restock");
        assertTrue(output.contains("frozen @ " + Hashing.shortHash(hash)),
                "a matching hash means frozen:\n" + output);
        assertTrue(output.contains("body (synthesized, verified):"), output);
        assertTrue(output.contains("    return new Product(p.id(), p.name(), p.stock() + units);"),
                "the frozen body should be printed indented:\n" + output);
    }

    @Test
    void flagsAFrozenBodyAsStaleWhenTheSpecificationChanged() throws IOException {
        Path file = shopFile();
        Lock lock = new Lock();
        lock.put("shop.Catalog.restock", new Lock.Entry("0".repeat(64), "return null;"));
        lock.save(dir.resolve("sky.lock"));

        String output = run("why", file.toString(), "Catalog.restock");
        assertTrue(output.contains("stale — the specification changed since the freeze"),
                "a hash mismatch means stale:\n" + output);
    }

    @Test
    void failsClearlyForAnUnknownMethod() throws IOException {
        int exit = new CommandLine(new SkyCli()).execute("why", shopFile().toString(), "Catalog.vanish");
        assertEquals(1, exit, "an unknown method should exit non-zero");
    }
}
