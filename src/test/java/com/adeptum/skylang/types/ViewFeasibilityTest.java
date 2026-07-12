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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewFeasibilityTest {

    private static final String LOGIN = """
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
            """;

    @Test
    void flagsAnOptionalShowsWithAPresentOnlyExpectation() {
        Ast.Module module = Parsing.parse(LOGIN, "identity.sky");

        List<String> found = ViewFeasibility.contradictions(module);

        assertEquals(1, found.size(), found.toString());
        String message = found.get(0);
        assertTrue(message.contains("page Login cannot be verified as written"), message);
        assertTrue(message.contains("Session.current()"), message);
        assertTrue(message.contains("Maybe<UserAccount>"), message);
        assertTrue(message.contains("action \"Sign out\" is a button"), message);
        assertTrue(message.contains("Split Login"), message);
    }

    @Test
    void pageLevelActionsAreNotBoundToTheOptionalRecord() {
        Ast.Module module = Parsing.parse("""
                module identity
                entity UserAccount { id Int @id  displayName Text  email Email }
                service Session uses db {
                  current() -> Maybe<UserAccount>
                    intent "The account for the current session, if any."
                  signIn() -> UserAccount
                    intent "Start the sign-in flow."
                }
                page Login at "/" {
                  shows  Session.current() as a summary of (displayName, email)
                  action "Sign in" -> Session.signIn()
                  expect action "Sign in" is a button
                }
                """, "identity.sky");

        assertTrue(ViewFeasibility.contradictions(module).isEmpty(),
                "a page-level control renders whether or not the optional value is present");
    }

    @Test
    void authBackedOptionalShowsIsNotFlagged() {
        Ast.Module module = Parsing.parse("""
                module identity
                entity UserAccount { id Int @id  displayName Text  email Email }
                service Session uses auth, db {
                  current() -> Maybe<UserAccount>
                    intent "The account for the signed-in principal, if any."
                  signOut(account UserAccount) -> UserAccount
                    intent "End the session."
                }
                page Login at "/" {
                  shows  Session.current() as a summary of (displayName, email)
                  action "Sign out" on the account -> Session.signOut(account)
                  expect action "Sign out" is a button
                }
                """, "identity.sky");

        assertTrue(ViewFeasibility.contradictions(module).isEmpty(),
                "a uses-auth query is seedable during verification: the pinned principal supplies"
                        + " the present state, so the signed-in half is verifiable");
    }

    @Test
    void theLiftRequiresTheAuthEffect() {
        Ast.Module module = Parsing.parse(LOGIN, "identity.sky");
        assertEquals(1, ViewFeasibility.contradictions(module).size(),
                "without the auth effect nothing supplies the value; the page stays flagged");
    }

    @Test
    void controlsBindToTheirOwnShowsOnAMultiSectionPage() {
        Ast.Module module = Parsing.parse("""
                module identity
                entity Tenant { id Int @id  name Text }
                entity UserAccount { id Int @id  displayName Text  email Email }
                service Tenants uses db {
                  current() -> Maybe<Tenant>  intent "The tenant for this request."
                }
                service Session uses auth, db {
                  current() -> Maybe<UserAccount>  intent "The signed-in account."
                  signOut(account UserAccount) -> UserAccount  intent "End the session."
                }
                page Login at "/" {
                  shows  Tenants.current() as a summary of (name)
                  shows  Session.current() as a summary of (displayName, email)
                  action "Sign out" on the useraccount -> Session.signOut(useraccount)
                  expect action "Sign out" is a button
                }
                """, "identity.sky");

        assertTrue(ViewFeasibility.contradictions(module).isEmpty(),
                "the sign-out control binds to the auth-backed session section, not the branding"
                        + " section, so the unseedable tenant summary must not flag it");
    }

    @Test
    void theCheckerRejectsTheContradictionBeforeAnySynthesis() {
        Ast.Module module = Parsing.parse(LOGIN, "identity.sky");

        CheckException thrown = assertThrows(CheckException.class, () -> new TypeChecker().check(module));
        assertTrue(thrown.getMessage().contains("page Login cannot be verified as written"),
                thrown.getMessage());
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

        assertTrue(ViewFeasibility.contradictions(module).isEmpty());
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

        assertTrue(ViewFeasibility.contradictions(module).isEmpty());
    }
}
