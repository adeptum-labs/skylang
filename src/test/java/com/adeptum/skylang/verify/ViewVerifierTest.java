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

package com.adeptum.skylang.verify;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.types.CheckException;
import com.adeptum.skylang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewVerifierTest {

    private final ViewVerifier verifier = new ViewVerifier();

    private static final String MARKUP = """
            <h:form>
              <h:dataTable id="t" styleClass="compact" value="#{bean.rows}" var="row">
                <h:column><h:outputText value="#{row.name}"/></h:column>
                <h:column><h:outputText value="#{row.stock}"/></h:column>
                <h:column><h:panelGroup styleClass="toolbar"><h:commandButton value="Restock"/></h:panelGroup></h:column>
              </h:dataTable>
            </h:form>
            """;

    private static Ast.View viewWith(String appearsClauses) {
        String src = """
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  all() -> [Product]  intent "all"
                  restock(id Int, units Int) -> Product  intent "restock"
                }
                view V {
                  shows Catalog.all() as a table of (name, stock)
                  action "Restock" on row -> Catalog.restock(row.id, ask Int)
                %s
                }
                """.formatted(appearsClauses);
        return Parsing.parse(src, "v.sky").views().get(0);
    }

    @Test
    void acceptsSatisfiedAppears() {
        Ast.View view = viewWith("""
                  appears action "Restock" in toolbar
                  appears rows is compact
                  appears columns (name, stock)
                """);
        assertTrue(verifier.unmetExpectations(view, MARKUP).isEmpty());
    }

    @Test
    void reportsUnmetPlacement() {
        Ast.View view = viewWith("  appears action \"Restock\" in sidebar");
        List<String> unmet = verifier.unmetExpectations(view, MARKUP);
        assertEquals(1, unmet.size());
        assertTrue(unmet.get(0).contains("sidebar"));
    }

    @Test
    void reportsUnmetStyle() {
        Ast.View view = viewWith("  appears rows is spacious");
        assertTrue(verifier.unmetExpectations(view, MARKUP).stream().anyMatch(s -> s.contains("spacious")));
    }

    @Test
    void reportsWrongColumnOrder() {
        Ast.View view = viewWith("  appears columns (stock, name)");
        assertFalse(verifier.unmetExpectations(view, MARKUP).isEmpty());
    }

    private static Ast.View paramView() {
        return Parsing.parse("""
                module t
                entity Account { id Int @id  email Text }
                service Session { current() -> Maybe<Account>  intent "x" }
                page Login at "/" {
                  param   accessDenied Bool
                  shows   Session.current() as a summary of (email)
                  appears the access-denied alert when accessDenied
                }
                """, "t.sky").views().get(0);
    }

    @Test
    void acceptsASatisfiedAppearsWhen() {
        assertTrue(verifier.unmetExpectations(paramView(), """
                <h:panelGroup styleClass="accessDenied" rendered="#{bean.accessDenied}">
                  <h:outputText value="Access denied."/>
                </h:panelGroup>
                """).isEmpty());
    }

    @Test
    void reportsAMissingAppearsWhenBinding() {
        List<String> unmet = verifier.unmetExpectations(paramView(),
                "<h:outputText value=\"Access denied.\"/>");
        assertEquals(1, unmet.size());
        assertTrue(unmet.get(0).contains("accessDenied"), unmet.get(0));
    }

    // The exact shape that sent `sky freeze` into an endless regenerate loop: a
    // single-record summary that also declared a table-only column order.
    private static final String SUMMARY_PAGE_WITH_COLUMNS = """
            module identity
            entity UserAccount { id Int @id  email Text  displayName Text }
            service Session uses db { current() -> Maybe<UserAccount>  intent "x" }
            page Login at "/" {
              shows   Session.current() as a summary of (displayName, email)
              appears columns (email, displayName)
            }
            """;

    private static final String SUMMARY_MARKUP = """
            <h:panelGrid id="branding">
              <h:outputText value="#{loginBean.displayName}"/>
              <h:outputText value="#{loginBean.email}"/>
            </h:panelGrid>
            """;

    @Test
    void reproducesTheColumnOrderLoopThenTheGuardStopsIt() {
        Ast.Module module = Parsing.parse(SUMMARY_PAGE_WITH_COLUMNS, "identity.sky");

        // A rendered summary has no table columns, so this is the unmet the freeze
        // loop kept reporting — byte-for-byte the message the user saw.
        List<String> unmet = verifier.unmetExpectations(module.views().get(0), SUMMARY_MARKUP);
        assertTrue(unmet.contains("expected column order [email, displayName] but got []"),
                unmet.toString());

        // The fix: check now rejects the contract, so it can never reach freeze.
        CheckException rejected = assertThrows(CheckException.class,
                () -> new TypeChecker().check(module));
        assertTrue(rejected.getMessage().contains("table"), rejected.getMessage());
    }

    @Test
    void theCorrectedSummaryPageChecksAndVerifiesClean() {
        Ast.Module module = Parsing.parse("""
                module identity
                entity UserAccount { id Int @id  email Text  displayName Text }
                service Session uses db { current() -> Maybe<UserAccount>  intent "x" }
                page Login at "/" {
                  shows   Session.current() as a summary of (displayName, email)
                }
                """, "identity.sky");

        assertDoesNotThrow(() -> new TypeChecker().check(module));
        assertTrue(verifier.unmetExpectations(module.views().get(0), SUMMARY_MARKUP).isEmpty(),
                "the corrected summary page leaves nothing for the freeze verifier to reject");
    }

    private static final String BRANDING = """
            <h:panelGroup styleClass="branding">
              <h:graphicImage id="logoImage" value="#{bean.logoDataUri}" alt="logo"/>
              <h:outputText value="#{bean.name}"/>
            </h:panelGroup>
            """;

    @Test
    void acceptsABoundImageField() {
        Ast.View view = viewWith("");
        assertTrue(verifier.unmetExpectations(view, BRANDING, java.util.Set.of("logo")).isEmpty());
    }

    @Test
    void reportsAMissingImageBinding() {
        Ast.View view = viewWith("");
        List<String> unmet = verifier.unmetExpectations(view,
                "<h:outputText value=\"#{bean.logo}\"/>", java.util.Set.of("logo"));
        assertEquals(1, unmet.size());
        assertTrue(unmet.get(0).contains("logo") && unmet.get(0).contains("image"), unmet.get(0));
    }

    private static Ast.View navView() {
        return Parsing.parse("""
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  all() -> [Product]  intent "all"
                }
                view ProductList at "/products" {
                  shows Catalog.all() as a table of (name, stock)
                }
                view Home at "/" {
                  shows Catalog.all() as a table of (name, stock)
                  action "Products" -> page ProductList
                }
                """, "shop.sky").views().get(1);
    }

    @Test
    void reportsAMissingNavigationControl() {
        List<String> unmet = verifier.unmetExpectations(navView(), MARKUP);
        assertEquals(1, unmet.size());
        assertTrue(unmet.get(0).contains("Products") && unmet.get(0).contains("ProductList"),
                unmet.get(0));
    }

    @Test
    void acceptsANavigationControlWithTheTargetOutcome() {
        String markup = MARKUP + "<h:button value=\"Products\" outcome=\"ProductList\"/>\n";
        assertTrue(verifier.unmetExpectations(navView(), markup).isEmpty());
    }

    private static Ast.Module flowModule() {
        return Parsing.parse("""
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  all() -> [Product]  intent "all"
                }
                view Cart at "/cart" {
                  shows Catalog.all() as a table of (name, stock)
                }
                flow Checkout {
                  step Cart -> page Cart
                }
                view Home at "/" {
                  shows Catalog.all() as a table of (name, stock)
                  action "Check out" -> flow Checkout
                }
                """, "shop.sky");
    }

    private static Ast.View signView() {
        return Parsing.parse("""
                module id
                entity Account { id Int  email Text }
                service Session uses auth {
                  current() -> Maybe<Account>  intent "Who is signed in."
                }
                view Dashboard at "/home" {
                  shows Session.current() as a summary of (email)
                }
                page Login at "/" {
                  shows Session.current() as a summary of (email)
                  action "Logga in med Google" -> sign in then page Dashboard
                }
                """, "id.sky").views().get(1);
    }

    @Test
    void aSignActionIsSatisfiedByACommandButtonNotAnOutcome() {
        String markup = "<h:commandButton value=\"Logga in med Google\" action=\"#{bean.signIn}\"/>";
        assertTrue(verifier.unmetExpectations(signView(), markup).isEmpty());

        List<String> unmet = verifier.unmetExpectations(signView(), "<h:outputText value=\"x\"/>");
        assertEquals(1, unmet.size());
        assertTrue(unmet.get(0).contains("Logga in med Google"), unmet.get(0));
    }

    @Test
    void aFlowActionMustNavigateToTheFlowsEntryPage() {
        Ast.Module m = flowModule();
        List<String> unmet = verifier.unmetExpectations(m, m.views().get(1), MARKUP, java.util.Set.of());
        assertEquals(1, unmet.size());
        assertTrue(unmet.get(0).contains("Check out") && unmet.get(0).contains("Cart"), unmet.get(0));

        String markup = MARKUP + "<h:button value=\"Check out\" outcome=\"Cart\"/>\n";
        assertTrue(verifier.unmetExpectations(m, m.views().get(1), markup, java.util.Set.of()).isEmpty());
    }
}
