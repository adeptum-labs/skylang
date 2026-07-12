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

package com.adeptum.skylang.synth;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    private static final String BANK = """
            module bank
            type Sku = Text matching /^[A-Z0-9-]{4,16}$/
            type Quantity = Int(1..)
            entity Account {
              id      Int   @id
              owner   Email @unique
              balance Money
              pin     Secret<Text>
              opened  Instant
            }
            service Accounts {
              deposit(a Account, amount Money, times Quantity) -> Account
                intent  "Add the amount that many times."
                ensures result.balance == a.balance + amount * times
              find(owner Email) -> Maybe<Account>
                intent "Find by owner."
            }
            """;

    private final PromptBuilder prompts = new PromptBuilder();

    private static Ast.Module bank() {
        return Parsing.parse(BANK, "bank.sky");
    }

    @Test
    void userPromptStatesTheUniquenessScope() {
        Ast.Module m = Parsing.parse("""
                module bank
                entity Provider { id Int @id  name Text }
                entity UserAccount {
                  id       Int @id
                  provider Provider
                  email    Email @unique(provider)
                }
                service Accounts uses db {
                  register(email Email) -> UserAccount  intent "Register."
                }
                """, "bank.sky");
        String user = prompts.user(m, m.services().get(0), m.services().get(0).methods().get(0));
        assertTrue(user.contains("unique per provider: email"),
                "the model must know the duplicate check is per-scope, not global:\n" + user);
    }

    @Test
    void systemPromptTeachesTheLoweringVocabulary() {
        String system = prompts.system();
        assertTrue(system.contains("Money"), "Money must be introduced");
        assertTrue(system.contains("plus") && system.contains("times"),
                "the Money API surface must be spelled out");
        assertTrue(system.contains("Optional"), "Maybe lowers to Optional");
        assertTrue(system.contains("Secret") && system.contains("reveal"),
                "Secret handling rules must be stated");
        assertTrue(system.contains("java.time.Instant"), "Instant lowers to java.time.Instant");
    }

    @Test
    void userPromptLowersEntityShapesThroughTheFullMapping() {
        Ast.Module m = bank();
        String user = prompts.user(m, m.services().get(0), m.services().get(0).methods().get(0));
        assertTrue(user.contains("Money balance"), "record shapes must use the lowered types");
        assertTrue(user.contains("Secret<String> pin"));
        assertTrue(user.contains("java.time.Instant opened"));
    }

    @Test
    void userPromptListsDeclaredTypesAndErasesSignatures() {
        Ast.Module m = bank();
        String user = prompts.user(m, m.services().get(0), m.services().get(0).methods().get(0));
        assertTrue(user.contains("type Sku = Text matching"), "declared types must be listed");
        assertTrue(user.contains("type Quantity = Int(1..)"), "declared types must carry their predicate");
        assertTrue(user.contains("deposit(Account a, Money amount, long times)"),
                "named refined types must erase in the Java signature");
    }

    @Test
    void userPromptLowersMaybeReturnTypes() {
        Ast.Module m = bank();
        String user = prompts.user(m, m.services().get(0), m.services().get(0).methods().get(1));
        assertTrue(user.contains("java.util.Optional<Account> find(String owner)"),
                "Maybe and Email must lower in the signature");
    }

    // ----- the chapter-4 surface: effects and values ---------------------------

    private static final String STORE = """
            module store
            entity Status { name Text @id  values Open, Closed }
            entity Order { id Int @id  status Status  total Money }
            service Orders uses db, clock {
              place(total Money) -> Order
                intent "Persist a new open order."
            }
            service Pure {
              square(x Int) -> Int
                intent "x times x."
            }
            """;

    private static Ast.Module store() {
        return Parsing.parse(STORE, "store.sky");
    }

    @Test
    void systemPromptStatesTheEffectsDiscipline() {
        String system = prompts.system();
        assertTrue(system.contains("effect"), "the budget rule must be stated");
        assertTrue(system.contains("clock.instant()"), "time comes from the clock handle");
        assertTrue(system.contains("Instant.now") && system.contains("System.currentTimeMillis"),
                "the raw time APIs must be named as forbidden");
    }

    @Test
    void userPromptListsTheBudgetAndTheStore() {
        Ast.Module m = store();
        String user = prompts.user(m, m.services().get(0), m.services().get(0).methods().get(0));
        assertTrue(user.contains("db.save(") && user.contains("db.findOrder(")
                        && user.contains("db.allOrders()") && user.contains("db.deleteOrder("),
                "the db handle's store surface must be spelled out");
        assertTrue(user.contains("clock.instant()"), "the clock handle must be listed");
        assertFalse(user.contains("http.get"), "undeclared effects must not be offered");
        assertTrue(user.contains("Status.Open") && user.contains("Status.Closed"),
                "value constants must be listed");
    }

    private static final String SHELF = """
            module library
            entity Book { id Int @id  copies Int @min(0) }
            entity NotFound { }
            service Shelf uses db {
              lend(id Int, copies Int) -> Book
                intent  "Decrease the stored book's copies."
                ensures result.copies == old(result.copies) - copies
                raises  NotFound when no book has that id
            }
            """;

    @Test
    void userPromptCarriesTheFailureContracts() {
        Ast.Module m = Parsing.parse(SHELF, "library.sky");
        String user = prompts.user(m, m.services().get(0), m.services().get(0).methods().get(0));
        assertTrue(user.contains("raises NotFound when no book has that id"),
                "the failure contract must reach the generator");
        assertTrue(user.contains("old(result.copies)"), "old() must reach the generator verbatim");
    }

    @Test
    void systemPromptTeachesRaisesAndOld() {
        String system = prompts.system();
        assertTrue(system.contains("raises") && system.contains("throw"),
                "the generator must throw exactly the named errors");
        assertTrue(system.contains("old("), "old() semantics must be explained");
    }

    private static final String LEDGER = """
            module ledger
            entity Account { id Int @id  balance Money }
            entity Overdrawn { }
            service Bank uses db {
              drain(a Account) -> Account
                spec "rejects an empty account" {
                  given a.balance == 0eur
                  when  drain(a)
                  then  raises Overdrawn
                        a.balance == 0eur
                }
            }
            """;

    @Test
    void userPromptRendersSpecsAsScenarios() {
        Ast.Module m = Parsing.parse(LEDGER, "ledger.sky");
        String user = prompts.user(m, m.services().get(0), m.services().get(0).methods().get(0));
        assertTrue(user.contains("\"rejects an empty account\""), "the spec title must reach the generator");
        assertTrue(user.contains("given a.balance == 0eur"), "the given state must reach the generator");
        assertTrue(user.contains("raises Overdrawn"), "the then outcome must reach the generator");
    }

    @Test
    void pureServicesAreToldTheyHaveNoEffects() {
        Ast.Module m = store();
        String user = prompts.user(m, m.services().get(1), m.services().get(1).methods().get(0));
        assertTrue(user.contains("no effects"), "a service without a budget is pure computation");
        assertFalse(user.contains("db.save"), "no handles may be offered to a pure service");
    }
}
