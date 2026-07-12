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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
