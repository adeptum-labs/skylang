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

package com.adeptum.skylang;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.StubLlm;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the synth -> stage -> freeze loop offline: a stub model supplies a body and a stub
 * verifier accepts it, so the test needs no network and no Maven. The second build must reuse
 * the frozen body and never call the model again.
 */
class PipelineTest {

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

    private static final String BODY = "return new Product(p.id(), p.name(), p.stock() + units);";

    private static final Verifier ALWAYS_PASS = dir -> VerificationResult.pass();

    private Ast.Module checkedModule() {
        Ast.Module m = Parsing.parse(SHOP, "shop.sky");
        new TypeChecker().check(m);
        return m;
    }

    @Test
    void firstBuildSynthesizesAndFreezes(@TempDir Path root) {
        Ast.Module module = checkedModule();
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        StubLlm stub = new StubLlm(BODY);

        int code = new Pipeline(stub, ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(1, stub.calls(), "model should be called once for the single unfrozen method");
        assertTrue(Files.exists(lock), "sky.lock should be written");
        assertTrue(Files.exists(buildDir.resolve("src/main/java/shop/Catalog.java")));
        assertTrue(Files.exists(buildDir.resolve("src/main/java/shop/Product.java")));
        assertTrue(Files.exists(buildDir.resolve("src/test/java/shop/CatalogTest.java")));
    }

    @Test
    void secondBuildReusesFrozenBodyWithoutCallingModel(@TempDir Path root) {
        Ast.Module module = checkedModule();
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(new StubLlm(BODY), ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        // Rebuild with a fresh stub; the frozen body must be reused, so the stub is never called.
        StubLlm second = new StubLlm(BODY);
        int code = new Pipeline(second, ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(0, second.calls(), "rebuild with an unchanged spec must not call the model");
    }

    @Test
    void changingIntentRegenerates(@TempDir Path root) {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(new StubLlm(BODY), ALWAYS_PASS)
                .build(checkedModule(), lock, buildDir, quiet(), quiet());

        Ast.Module changed = Parsing.parse(SHOP.replace("Increase stock.", "Bump the stock up."), "shop.sky");
        new TypeChecker().check(changed);
        StubLlm after = new StubLlm(BODY);
        new Pipeline(after, ALWAYS_PASS).build(changed, lock, buildDir, quiet(), quiet());

        assertEquals(1, after.calls(), "changing the intent should re-synthesize that method");
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
