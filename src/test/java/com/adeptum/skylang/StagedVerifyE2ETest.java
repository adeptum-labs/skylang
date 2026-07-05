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
import com.adeptum.skylang.verify.MavenVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end proof that a synthesized body actually compiles and its generated ensures/example
 * tests pass, by staging the project and running the REAL Maven verifier. Opt-in (needs Maven on
 * PATH and network for JUnit): run with {@code SKY_E2E=1}. Excluded from the normal test run.
 */
@EnabledIfEnvironmentVariable(named = "SKY_E2E", matches = "1")
class StagedVerifyE2ETest {

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

    private static final String CORRECT_BODY = "return new Product(p.id(), p.name(), p.stock() + units);";

    @Test
    void synthesizedCodeCompilesAndPasses(@TempDir Path root) {
        Ast.Module module = Parsing.parse(SHOP, "shop.sky");
        new TypeChecker().check(module);

        var out = new ByteArrayOutputStream();
        int code = new Pipeline(new StubLlm(CORRECT_BODY), new MavenVerifier())
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        new PrintStream(out), new PrintStream(out));

        assertEquals(0, code, () -> "staged verification failed:\n" + out.toString(StandardCharsets.UTF_8));
    }
}
