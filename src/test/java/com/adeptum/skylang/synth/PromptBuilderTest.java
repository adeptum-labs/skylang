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
}
