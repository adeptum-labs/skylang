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

    private static final String STORE = """
            module store
            entity Status { name Text @id  values Open, Closed }
            entity Customer { id Int @id  email Email @unique }
            entity LineItem { item Text  quantity Int(1..) }
            entity Order {
              id       Int @id
              customer Customer
              status   Status  = Status.Open
              items    [LineItem]
              placed   Instant = now
              total    Money
            }
            service Orders uses db, clock {
              place(c Customer, total Money) -> Order
                intent  "Persist the customer, then a one-line order for them, and return the stored copy."
                ensures result.total == total
                example place(Customer(7, "ada@example.com"), 9.99eur) -> a Order with total 9.99eur
            }
            """;

    private static final String PLACE_BODY = """
            db.save(c);
            Order order = new Order(1, c, Status.Open,
                    java.util.List.of(new LineItem("book", 2)), clock.instant(), total);
            return db.save(order);
            """;

    @Test
    void theDbEffectRoundTripsThroughRealJpa(@TempDir Path root) {
        Ast.Module module = Parsing.parse(STORE, "store.sky");
        new TypeChecker().check(module);

        var out = new ByteArrayOutputStream();
        int code = new Pipeline(new StubLlm(PLACE_BODY), new MavenVerifier())
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        new PrintStream(out), new PrintStream(out));

        assertEquals(0, code, () -> "staged verification failed:\n" + out.toString(StandardCharsets.UTF_8));
    }

    private static final String BANK = """
            module bank
            type Quantity = Int(1..)
            entity Account {
              id      Int   @id
              owner   Email @unique
              name    Text(1..120)
              balance Money
              active  Bool = true
            }
            service Accounts {
              deposit(a Account, amount Money, times Quantity) -> Account
                intent  "Return a copy with the amount added that many times."
                ensures result.balance == a.balance + amount * times
                example deposit(Account(1, "a@example.com", "Main", 0eur, true), 5eur, 2) -> a Account with balance 10eur
              find(a Account, owner Email) -> Maybe<Account>
                intent "The account when the owner matches, otherwise nothing."
            }
            """;

    private static final String DEPOSIT_BODY =
            "return new Account(a.id(), a.owner(), a.name(), a.balance().plus(amount.times(times)), a.active());";
    private static final String FIND_BODY =
            "return owner.equals(a.owner()) ? java.util.Optional.of(a) : java.util.Optional.empty();";

    @Test
    void moneyAndRefinedTypesCompileAndPass(@TempDir Path root) {
        Ast.Module module = Parsing.parse(BANK, "bank.sky");
        new TypeChecker().check(module);

        StubLlm stub = new StubLlm((system, user) ->
                user.contains("public Account deposit") ? DEPOSIT_BODY : FIND_BODY);
        var out = new ByteArrayOutputStream();
        int code = new Pipeline(stub, new MavenVerifier())
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        new PrintStream(out), new PrintStream(out));

        assertEquals(0, code, () -> "staged verification failed:\n" + out.toString(StandardCharsets.UTF_8));
    }
}
