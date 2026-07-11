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

import com.adeptum.skylang.cli.SkyCli;
import com.adeptum.skylang.freeze.Lock;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.StubLlm;
import com.adeptum.skylang.types.CheckException;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operating chapter's promises, proven against the shop's discount incident: a production
 * bug that let a discount code drive an order total negative. The fix is a specification, not a
 * patch — a strengthened flooring contract plus the example that reproduced the bug. This suite
 * pins the language and tooling that chapter relies on: the flooring contract type-checks, the
 * reproducing example is guarded forever in a generated test, only the touched method regenerates,
 * a re-freeze regenerates every body, and {@code sky why} still tells you what a method guarantees.
 */
class OperatingParityTest {

    /** The shop after the incident: the discount total is floored at zero by a contract. */
    private static final String INCIDENT = """
            module shop
            entity Order { id Int @id  total Money = 0eur }
            service Pricing {
              discountFor(code Text, order Order) -> Money
                intent "The discount a code grants against an order's total."
            }
            service Discounts uses db {
              applyDiscount(id Int, code Text) -> Order
                intent  "Apply a discount code to an order, flooring the total at zero."
                ensures result.total == max(0eur, old(result.total) - discountFor(code, result))
                example applyDiscount(1, "HALF50") on an Order with total 5eur -> total 0eur
            }
            """;

    /** The same shop before the incident: the specification never said the total floors at zero. */
    private static final String BEFORE_FIX = """
            module shop
            entity Order { id Int @id  total Money = 0eur }
            service Pricing {
              discountFor(code Text, order Order) -> Money
                intent "The discount a code grants against an order's total."
            }
            service Discounts uses db {
              applyDiscount(id Int, code Text) -> Order
                intent  "Apply a discount code to an order."
            }
            """;

    private static final Verifier ALWAYS_PASS = dir -> VerificationResult.pass();

    private static Ast.Module checked(String source) {
        Ast.Module module = Parsing.parse(source, "shop.sky");
        new TypeChecker().check(module);
        return module;
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    @Test
    void theFlooringContractChecksMaxOldMoneyAndHelper() {
        // max over Money, old() of the pre-call total, and an effect-free helper call all compose.
        assertDoesNotThrow(() -> checked(INCIDENT));

        // The floor's two arms must share an ordered type; a Money floor over an Int is rejected.
        CheckException mismatch = assertThrows(CheckException.class, () -> checked(
                INCIDENT.replace("max(0eur, old(result.total) - discountFor(code, result))",
                        "max(0eur, 5)")));
        assertTrue(mismatch.getMessage().contains("ordered"), mismatch.getMessage());
    }

    @Test
    void theFlooringExampleLowersIntoAPermanentTest(@TempDir Path root) throws IOException {
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(checked(INCIDENT), root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String tests = Files.readString(buildDir.resolve("src/test/java/shop/DiscountsTest.java"));
        assertTrue(tests.contains("\"HALF50\""),
                "the reproducing example calls the method with its code:\n" + tests);
        assertTrue(tests.contains("Money.of(\"5\", \"EUR\")"),
                "the seeded order carries the total the bug was found on:\n" + tests);
        assertTrue(tests.contains("Money.of(\"0\", \"EUR\")"),
                "the flooring expectation is asserted, so the bug is guarded forever:\n" + tests);
    }

    @Test
    void buildRegeneratesOnlyTheMethodWhoseSpecChanged(@TempDir Path root) {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(checked(BEFORE_FIX), lock, buildDir, quiet(), quiet());

        // Closing the gap touches only applyDiscount; the unchanged helper stays frozen.
        StubLlm afterFix = new StubLlm("return null;");
        int code = new Pipeline(afterFix, ALWAYS_PASS).build(checked(INCIDENT), lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(1, afterFix.calls(),
                "the blast radius is the one method whose specification changed");
    }

    @Test
    void freezeRegeneratesEveryBody(@TempDir Path root) throws IOException {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(checked(INCIDENT), lock, buildDir, quiet(), quiet());

        // sky freeze discards the lock and rebuilds; every body regenerates against the current model.
        Files.delete(lock);
        StubLlm reFrozen = new StubLlm("return null;");
        int code = new Pipeline(reFrozen, ALWAYS_PASS).build(checked(INCIDENT), lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(2, reFrozen.calls(),
                "a re-freeze regenerates every body: the helper and the discount method both");
    }

    @Test
    void whyReportsTheFrozenContractsOfTheIncidentMethod(@TempDir Path root) throws IOException {
        Path file = root.resolve("shop.sky");
        Files.writeString(file, INCIDENT);
        Ast.Module module = checked(INCIDENT);
        Ast.Method method = module.services().get(1).methods().get(0);

        Lock lock = new Lock();
        lock.put("shop.Discounts.applyDiscount",
                new Lock.Entry(Pipeline.methodSpecHash(module, method), "return null;"));
        lock.save(root.resolve("sky.lock"));

        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            new CommandLine(new SkyCli()).execute("why", file.toString(), "Discounts.applyDiscount");
        } finally {
            System.setOut(original);
        }

        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(
                        "ensures   result.total == max(0eur, old(result.total) - discountFor(code, result))"),
                "sky why states the guarantee the method was proven to satisfy:\n" + output);
        assertTrue(output.contains("frozen    @"), "a matching hash reports the method as frozen:\n" + output);
        assertTrue(output.contains("verified  ✓ 1 contract   ✓ 1 example"),
                "the flooring contract and its reproducing example are counted:\n" + output);
    }
}
