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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewFeasibilityTest {

    @Test
    void flagsAnOptionalShowsWithAPresentOnlyExpectation() {
        Ast.Module module = Parsing.parse("""
                module identity
                entity UserAccount { id Int @id  displayName Text  email Email }
                service Session uses db {
                  current() -> Maybe<UserAccount>
                    intent "The account for the current session, if any."
                  signOut(account UserAccount) -> UserAccount
                    intent "End the session."
                }
                page Login at "/" {
                  shows  Session.current() as a summary of (displayName, email)
                  action "Sign out" on the account -> Session.signOut(account)
                  expect action "Sign out" is a button
                }
                """, "identity.sky");

        List<String> warnings = ViewFeasibility.warnings(module);

        assertEquals(1, warnings.size(), warnings.toString());
        String warning = warnings.get(0);
        assertTrue(warning.contains("page Login may not pass render verification"), warning);
        assertTrue(warning.contains("Session.current()"), warning);
        assertTrue(warning.contains("Maybe<UserAccount>"), warning);
        assertTrue(warning.contains("action \"Sign out\" is a button"), warning);
        assertTrue(warning.contains("Split Login"), warning);
    }

    @Test
    void staysQuietWhenTheShownValueIsNotOptional() {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Product { id Int @id  name Text  stock Int }
                service Catalog uses db {
                  all() -> [Product]
                    intent "Every product."
                  restock(p Product, units Int) -> Product
                    intent "Add stock."
                }
                page Products at "/products" {
                  shows  Catalog.all() as a table of (name, stock)
                  action "Restock" on row -> Catalog.restock(row.id, ask Int)
                  expect action "Restock" is a button
                }
                """, "shop.sky");

        assertTrue(ViewFeasibility.warnings(module).isEmpty());
    }

    @Test
    void staysQuietWhenAnOptionalPageExpectsNoPresentOnlyControl() {
        Ast.Module module = Parsing.parse("""
                module identity
                entity UserAccount { id Int @id  displayName Text  email Email }
                service Session uses db {
                  current() -> Maybe<UserAccount>
                    intent "The account for the current session, if any."
                }
                page Landing at "/" {
                  shows  Session.current() as a summary of (displayName, email)
                }
                """, "identity.sky");

        assertTrue(ViewFeasibility.warnings(module).isEmpty());
    }
}
