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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTreeExtractorTest {

    private static final String TABLE = """
            <h:form id="f">
              <h:dataTable id="products" styleClass="compact" value="#{bean.rows}" var="row">
                <h:column id="c_name">
                  <f:facet name="header"><h:outputText value="Name"/></f:facet>
                  <h:outputText id="name" value="#{row.name}"/>
                </h:column>
                <h:column id="c_stock">
                  <f:facet name="header"><h:outputText value="Stock"/></f:facet>
                  <h:outputText id="stock" value="#{row.stock}"/>
                </h:column>
                <h:column id="c_actions">
                  <h:panelGroup styleClass="toolbar">
                    <h:commandButton id="restock" value="Restock" action="#{bean.restock(row.id)}"/>
                  </h:panelGroup>
                </h:column>
              </h:dataTable>
            </h:form>
            """;

    private final SemanticTreeExtractor extractor = new SemanticTreeExtractor();

    @Test
    void extractsRenderedConditionals() {
        SemanticTree tree = extractor.extract("""
                <h:panelGroup styleClass="accessDenied alert" rendered="#{bean.accessDenied}">
                  <h:outputText value="Access denied."/>
                </h:panelGroup>
                """);
        assertTrue(tree.hasConditional("accessDenied"));
        assertFalse(tree.hasConditional("sessionExpired"));
    }

    @Test
    void extractsGraphicImageBindings() {
        SemanticTree tree = extractor.extract("""
                <h:panelGroup styleClass="branding">
                  <h:graphicImage id="logoImage" value="#{bean.logoDataUri}" alt="logo"/>
                  <h:outputText value="#{bean.name}"/>
                </h:panelGroup>
                """);
        assertTrue(tree.hasImage("logo"));
        assertFalse(tree.hasImage("name"));
    }

    @Test
    void extractsBoundColumnFields() {
        SemanticTree tree = extractor.extract(TABLE);
        assertEquals(List.of("name", "stock"), tree.columnFields());
        assertTrue(tree.hasColumns(List.of("name", "stock")));
        assertFalse(tree.hasColumns(List.of("name", "bogus")));
    }

    @Test
    void capturesColumnHeaders() {
        SemanticTree tree = extractor.extract(TABLE);
        assertEquals("name", tree.columns().get(0).field());
        assertEquals("Name", tree.columns().get(0).header());
    }

    @Test
    void findsCommandButtonByLabel() {
        SemanticTree tree = extractor.extract(TABLE);
        assertTrue(tree.hasButton("Restock"));
        assertFalse(tree.hasButton("Delete"));
    }

    @Test
    void extractsRegionAndTableStyle() {
        SemanticTree tree = extractor.extract(TABLE);
        assertTrue(tree.tableHasStyle("compact"), "the data table's styleClass should be captured");
        assertTrue(tree.controlInRegion("Restock", "toolbar"), "the button's enclosing region should be captured");
        assertFalse(tree.controlInRegion("Restock", "sidebar"));
    }

    @Test
    void extractsNavigationOutcomes() {
        SemanticTree tree = extractor.extract("""
                <h:button value="Products" outcome="ProductList"/>
                <h:link value="Home" outcome="Home"/>
                """);
        assertTrue(tree.navigatesTo("Products", "ProductList"));
        assertTrue(tree.navigatesTo("Home", "Home"));
        assertFalse(tree.navigatesTo("Products", "Elsewhere"));
        assertFalse(tree.navigatesTo("Missing", "ProductList"));
    }
}
