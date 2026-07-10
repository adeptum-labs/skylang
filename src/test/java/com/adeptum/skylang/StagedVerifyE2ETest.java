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

    private static final String SHELF = """
            module shelf
            entity Book { id Int @id  title Text  copies Int @min(0) }
            entity Member { id Int @id  email Email @unique }
            entity NotFound { }
            entity BadInput { }
            entity DuplicateEmail { requested Email }
            service Library uses db {
              lend(id Int, copies Int) -> Book
                intent  "Decrease the stored book's copies by the amount."
                raises  BadInput when copies <= 0
                raises  NotFound when no book has that id
              reserve(id Int, days Int) -> Book
                intent   "The stored book, reserved for that many days."
                requires days > 0
              register(email Email) -> Member
                intent "Create the member unless the address is taken."
                raises DuplicateEmail when email already registered
              donate(book Book, copies Int) -> Book
                intent  "Add donated copies and persist the book."
                ensures result.copies == old(book.copies) + copies
                example donate(Book(1, "Odyssey", 5), 3) -> a Book with copies 8
              totalCopies() -> Int
                intent  "Total copies across the shelf."
                ensures result == sum of (b.copies for b in all books)
                example totalCopies() -> 0
            }
            """;

    private static StubLlm shelfStub() {
        return new StubLlm((system, user) -> {
            if (user.contains("public Book lend")) {
                return """
                        if (copies <= 0) throw new BadInput();
                        Book book = db.findBook(id).orElseThrow(NotFound::new);
                        return db.save(new Book(book.id(), book.title(), book.copies() - copies));
                        """;
            }
            if (user.contains("public Book reserve")) {
                return "return db.findBook(id).orElseThrow(NotFound::new);";
            }
            if (user.contains("public Member register")) {
                return """
                        if (db.allMembers().stream().anyMatch(m -> m.email().equals(email))) {
                            throw new DuplicateEmail(email);
                        }
                        return db.save(new Member(db.allMembers().size() + 1, email));
                        """;
            }
            if (user.contains("public Book donate")) {
                return "return db.save(new Book(book.id(), book.title(), book.copies() + copies));";
            }
            return "return db.allBooks().stream().mapToLong(Book::copies).sum();";
        });
    }

    @Test
    void chapterFiveContractsPassEndToEnd(@TempDir Path root) {
        Ast.Module module = Parsing.parse(SHELF, "shelf.sky");
        new TypeChecker().check(module);

        var out = new ByteArrayOutputStream();
        int code = new Pipeline(shelfStub(), new MavenVerifier())
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        new PrintStream(out), new PrintStream(out));

        assertEquals(0, code, () -> "staged verification failed:\n" + out.toString(StandardCharsets.UTF_8));
    }

    private static final String LEDGER = """
            module ledger
            entity Account { id Int @id  balance Money }
            entity Receipt { id Int @id  amount Money }
            entity InsufficientFunds { }
            service Bank uses db {
              transfer(from Account, to Account, amount Money) -> Receipt
                spec "moves money atomically" {
                  given from.balance == 100eur and to.balance == 0eur
                  when  transfer(from, to, 30eur)
                  then  from.balance == 70eur
                        to.balance   == 30eur
                }
                spec "rejects overdraft" {
                  given from.balance == 10eur
                  when  transfer(from, to, 50eur)
                  then  raises InsufficientFunds
                        from.balance == 10eur
                }
              topUp(id Int, units Int) -> Account
                intent  "Add that many euros to the stored account."
                example topUp(7, 3) on a Account with balance 5eur -> balance 8eur
            }
            """;

    private static final String TRANSFER_BODY = """
            Account src = db.findAccount(from.id()).orElseThrow();
            Account dst = db.findAccount(to.id()).orElseThrow();
            if (amount.compareTo(src.balance()) > 0) throw new InsufficientFunds();
            db.save(new Account(src.id(), src.balance().minus(amount)));
            db.save(new Account(dst.id(), dst.balance().plus(amount)));
            return db.save(new Receipt(src.id(), amount));
            """;

    private static final String TOP_UP_BODY = """
            Account account = db.findAccount(id).orElseThrow();
            return db.save(new Account(account.id(),
                    account.balance().plus(Money.of(String.valueOf(units), "EUR"))));
            """;

    @Test
    void specsPassEndToEnd(@TempDir Path root) {
        Ast.Module module = Parsing.parse(LEDGER, "ledger.sky");
        new TypeChecker().check(module);

        StubLlm stub = new StubLlm((system, user) ->
                user.contains("public Receipt transfer") ? TRANSFER_BODY : TOP_UP_BODY);
        var out = new ByteArrayOutputStream();
        int code = new Pipeline(stub, new MavenVerifier())
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        new PrintStream(out), new PrintStream(out));

        assertEquals(0, code, () -> "staged verification failed:\n" + out.toString(StandardCharsets.UTF_8));
    }

    private static final String VAULT = """
            module vault
            type Password = Text(12..128)
            entity WeakPassword { }
            entity Login { id Int @id  owner Email  password Password }
            policy StrongPasswords {
              whenever a Password is constructed
              require  length >= 12 and contains a symbol
              else     raise WeakPassword
            }
            service Registry uses db {
              register(id Int, password Password) -> Login
                intent  "Store a login for the standard owner."
                example register(1, "with-symbol-123!") -> a Login with id 1
                example register(2, "weakpassword") -> raises WeakPassword
            }
            """;

    @Test
    void policiesFireAtConstructionEndToEnd(@TempDir Path root) {
        Ast.Module module = Parsing.parse(VAULT, "vault.sky");
        new TypeChecker().check(module);

        var out = new ByteArrayOutputStream();
        int code = new Pipeline(
                new StubLlm("return db.save(new Login(id, \"a@example.com\", password));"),
                new MavenVerifier())
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
