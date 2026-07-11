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

package com.adeptum.skylang.preview;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A structured change is a pure, deterministic source rewrite: apply it, re-parse, and the view's
 * {@code appears} set is exactly what the panel asked for — no model, and a stable canonical order.
 */
class StructuredChangeTest {

    private static final String SRC = """
            module shop

            entity Product { id Int  name Text  stock Int @min(0) }

            service Catalog {
              all() -> [Product]  intent "all"
              restock(p Product, n Int) -> Product  intent "r"
            }

            view ProductList at "/products" {
              shows Catalog.all() as a table of (name, stock)
              action "Restock" on row -> Catalog.restock(row, ask Int)
              appears action "Restock" in toolbar
            }
            """;

    private static List<Ast.Appears> appearsOf(String source) {
        return Parsing.parse(source, "shop.sky").views().get(0).appears();
    }

    @Test
    void setColumnOrderRoundTrips() {
        String edited = new StructuredChange.SetColumnOrder("ProductList", List.of("stock", "name")).applyTo(SRC);

        Ast.AppearsColumnOrder co = (Ast.AppearsColumnOrder) appearsOf(edited).stream()
                .filter(a -> a instanceof Ast.AppearsColumnOrder).findFirst().orElseThrow();
        assertEquals(List.of("stock", "name"), co.columns());
    }

    @Test
    void setActionRegionReplacesTheExistingPlacement() {
        String edited = new StructuredChange.SetActionRegion("ProductList", "Restock", "footer").applyTo(SRC);

        List<Ast.Appears> appears = appearsOf(edited);
        assertEquals(1, appears.stream().filter(a -> a instanceof Ast.AppearsPlacement).count(),
                "the placement is replaced, not duplicated");
        Ast.AppearsPlacement p = (Ast.AppearsPlacement) appears.stream()
                .filter(a -> a instanceof Ast.AppearsPlacement).findFirst().orElseThrow();
        assertEquals("footer", p.region());
    }

    @Test
    void clearActionRegionRemovesIt() {
        String edited = new StructuredChange.ClearActionRegion("ProductList", "Restock").applyTo(SRC);
        assertTrue(appearsOf(edited).stream().noneMatch(a -> a instanceof Ast.AppearsPlacement),
                "the placement is gone");
    }

    @Test
    void setActionStateRoundTripsWithItsCondition() {
        String edited = new StructuredChange
                .SetActionState("ProductList", "Restock", "disabled", Optional.of("stock is 0")).applyTo(SRC);

        Ast.AppearsActionState s = (Ast.AppearsActionState) appearsOf(edited).stream()
                .filter(a -> a instanceof Ast.AppearsActionState).findFirst().orElseThrow();
        assertEquals("disabled", s.state());
        assertEquals(Optional.of("stock is 0"), s.when());
    }

    @Test
    void canonicalOrderMakesSourceIndependentOfEditSequence() {
        StructuredChange cols = new StructuredChange.SetColumnOrder("ProductList", List.of("stock", "name"));
        StructuredChange style = new StructuredChange.SetTableStyle("ProductList", "compact");

        String colsThenStyle = style.applyTo(cols.applyTo(SRC));
        String styleThenCols = cols.applyTo(style.applyTo(SRC));

        assertEquals(colsThenStyle, styleThenCols,
                "canonical ordering keeps the source (and freeze hash) stable across equivalent edits");
    }

    @Test
    void fromFormBuildsEachOpAndRejectsBadInput() {
        assertTrue(StructuredChange.fromForm(
                Map.of("op", "setColumnOrder", "view", "V", "columns", "a, b")) instanceof StructuredChange.SetColumnOrder);
        StructuredChange region = StructuredChange.fromForm(
                Map.of("op", "setActionRegion", "view", "V", "label", "Restock", "region", "toolbar"));
        assertEquals("\"Restock\" in toolbar", region.describe());

        assertThrows(IllegalArgumentException.class,
                () -> StructuredChange.fromForm(Map.of("op", "nope", "view", "V")), "unknown op");
        assertThrows(IllegalArgumentException.class,
                () -> StructuredChange.fromForm(Map.of("op", "setTableStyle", "view", "V")), "missing value");
    }
}
