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

import com.adeptum.skylang.backend.TsProfile;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.StubLlm;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.NodeVerifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the retarget: the same specification stages a real Node project, tsc
 * compiles it and node:test runs the generated contracts. Opt-in ({@code SKY_E2E=1}) and
 * skipped with a visible assumption when the TypeScript toolchain is not resolvable
 * ({@code SKY_TSC}/{@code SKY_NODE} or PATH).
 */
@EnabledIfEnvironmentVariable(named = "SKY_E2E", matches = "1")
class TsStagedVerifyE2ETest {

    private static final String SHOP = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog uses db {
              restock(p Product, units Int) -> Product
                intent  "Increase stock and persist the product."
                requires units > 0
                ensures  result.stock == p.stock + units
                example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
            }
            """;

    private static final String CORRECT_BODY = """
            if (units <= 0n) throw new RangeError("units must be > 0");
            const updated = new Product(p.id, p.name, p.stock + units);
            return this.db.save(updated);""";

    private static void assumeToolchain() {
        Assumptions.assumeTrue(runs(System.getenv().getOrDefault("SKY_TSC", "tsc"), "--version"),
                "tsc not resolvable (set SKY_TSC)");
        Assumptions.assumeTrue(runs(System.getenv().getOrDefault("SKY_NODE", "node"), "--version"),
                "node not resolvable (set SKY_NODE)");
    }

    private static boolean runs(String command, String arg) {
        try {
            Process p = new ProcessBuilder(command, arg).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Ast.Module checkedModule() {
        Ast.Module module = Parsing.parse(SHOP, "shop.sky");
        new TypeChecker().check(module);
        return module;
    }

    @Test
    void aCorrectTypeScriptBodyCompilesAndPasses(@TempDir Path root) {
        assumeToolchain();
        var out = new ByteArrayOutputStream();
        int code = new Pipeline(new StubLlm(CORRECT_BODY), new NodeVerifier(), 0, TsProfile.INSTANCE)
                .build(checkedModule(), root.resolve("sky.lock"), root.resolve("build/ts-node"),
                        new PrintStream(out), new PrintStream(out));
        assertEquals(0, code, out.toString());
        assertTrue(out.toString().contains("▸ synthesized (ts) ▸ verified ▸ frozen @"),
                out.toString());
    }

    @Test
    void aWrongTypeScriptBodyFailsTheGeneratedTests(@TempDir Path root) {
        assumeToolchain();
        String wrong = CORRECT_BODY.replace("p.stock + units", "p.stock - units");
        var err = new ByteArrayOutputStream();
        int code = new Pipeline(new StubLlm(wrong), new NodeVerifier(), 0, TsProfile.INSTANCE)
                .build(checkedModule(), root.resolve("sky.lock"), root.resolve("build/ts-node"),
                        new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertEquals(2, code, "wrong arithmetic must fail the ensures test — a verification failure");
    }
}
