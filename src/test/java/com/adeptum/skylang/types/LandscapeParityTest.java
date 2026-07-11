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

package com.adeptum.skylang.types;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cross-reference for the constructs SkyLang inherits from the traditions it stands on, proving
 * the borrowed surface is real and composes in one module. Each construct is tagged with the
 * lineage it comes from: design by contract ({@code requires}/{@code ensures}/type invariants),
 * refinement types and no-null ({@code Int(0..100)}, {@code Text matching}, {@code Maybe<T>}),
 * and property-based / behaviour-driven testing ({@code example}, {@code spec} given/when/then).
 * The individual enforcement of each lives in {@code TypeCheckerTest}; this test pins that the
 * whole borrowed vocabulary holds together at once.
 */
class LandscapeParityTest {

    private static void check(String source) {
        Ast.Module module = Parsing.parse(source, "bank.sky");
        new TypeChecker().check(module);
    }

    /** Every construct the landscape chapter attributes to a tradition, coexisting in one service. */
    private static final String BORROWED_SURFACE = """
            module bank
            type Rating = Int(0..100)                       // refinement type: an invariant as a type
            type Iban   = Text matching /^[A-Z]{2}[0-9]{18}$/
            entity Account {
              id      Int   @id
              owner   Email @unique
              iban    Iban
              balance Money
              rating  Rating
            }
            entity Wallet { id Int @id  balance Money }
            entity Receipt { id Int @id  amount Money }
            entity InsufficientFunds { }
            service Bank uses db {
              deposit(a Account, amount Money) -> Account
                intent   "Add the amount to the balance."
                requires amount > 0eur
                ensures  result.balance == old(a.balance) + amount
                example  deposit(Account(1, "a@b.com", "GB000000000000000000", 0eur, 50), 30eur)
                         -> a Account with balance 30eur
              find(id Int) -> Maybe<Account>
                intent  "The account with that id, or nothing — absence is a type, not a null."
                example find(999) -> nothing
              transfer(from Wallet, to Wallet, amount Money) -> Receipt
                intent "Move money between two wallets atomically."
                spec "moves money atomically" {
                  given from.balance == 100eur and to.balance == 0eur
                  when  transfer(from, to, 30eur)
                  then  from.balance == 70eur
                        to.balance   == 30eur
                }
            }
            """;

    @Test
    void theBorrowedConstructsComposeInOneModule() {
        assertDoesNotThrow(() -> check(BORROWED_SURFACE));
    }

    @Test
    void absenceIsATypeSoNothingNeedsAMaybeReturn() {
        // The no-null stance: a bare (non-Maybe) return cannot yield nothing — there is no null to lean on.
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module bank
                entity Account { id Int @id  balance Money }
                service Bank uses db {
                  find(id Int) -> Account
                    intent  "The account with that id."
                    example find(999) -> nothing
                }
                """));
        assertTrue(e.getMessage().contains("Maybe"), e.getMessage());
    }
}
