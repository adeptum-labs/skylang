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

package com.adeptum.skylang.front;

import com.adeptum.skylang.front.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsingTest {

    private static final String SHOP = """
            module shop

            entity Product {
              id    Int
              name  Text
              stock Int @min(0)
            }

            service Catalog {
              restock(p Product, units Int) -> Product
                intent  "Increase stock."
                requires units > 0
                ensures  result.stock == p.stock + units
                example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
            }
            """;

    @Test
    void parsesModuleShape() {
        Ast.Module m = Parsing.parse(SHOP, "shop.sky");

        assertEquals("shop", m.name());
        assertEquals(1, m.entities().size());
        assertEquals(1, m.services().size());

        Ast.Entity product = m.entities().get(0);
        assertEquals("Product", product.name());
        assertEquals(3, product.fields().size());
        Ast.Field stock = product.fields().get(2);
        assertEquals("stock", stock.name());
        assertTrue(stock.min().isPresent());
        assertEquals(0, stock.min().getAsLong());

        Ast.Method restock = m.services().get(0).methods().get(0);
        assertEquals("restock", restock.name());
        assertEquals(2, restock.params().size());
        assertTrue(restock.intent().isPresent());
        assertEquals(1, restock.requires().size());
        assertEquals(1, restock.ensures().size());
        assertEquals(1, restock.examples().size());
    }

    @Test
    void parsesExampleEntityResult() {
        Ast.Module m = Parsing.parse(SHOP, "shop.sky");
        Ast.Example ex = m.services().get(0).methods().get(0).examples().get(0);
        Ast.EntityResult result = assertInstanceOf(Ast.EntityResult.class, ex.result());
        assertEquals("Product", result.typeName());
        assertEquals(1, result.fields().size());
        assertEquals("stock", result.fields().get(0).field());
        assertInstanceOf(Ast.IntLit.class, result.fields().get(0).expected());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(SkyParseException.class, () -> Parsing.parse("module 123 entity", "bad.sky"));
    }

    private static final String SHOP_VIEW = """
            module shop

            entity Product {
              id    Int
              name  Text
              stock Int @min(0)
            }

            service Catalog {
              all() -> [Product]
                intent "Every product."
            }

            view ProductList at "/products" {
              shows  Catalog.all() as a table of (name, stock)
              action "Restock" on row -> Catalog.restock(row.id, ask Int)
              expect table has columns (name, stock)
              appears action "Restock" in toolbar
              appears rows is compact
              appears columns (name, stock)
            }
            """;

    @Test
    void parsesViewShape() {
        Ast.Module m = Parsing.parse(SHOP_VIEW, "shop.sky");

        assertEquals(1, m.views().size());
        Ast.View view = m.views().get(0);
        assertEquals("ProductList", view.name());
        assertTrue(view.route().isPresent());
        assertEquals("/products", view.route().get());

        Ast.Shows shows = view.shows();
        assertEquals("Catalog", shows.query().service());
        assertEquals("all", shows.query().method());
        assertTrue(shows.query().args().isEmpty());
        assertTrue(shows.projection().isPresent());
        assertEquals("table", shows.projection().get().kind());
        assertEquals(List.of("name", "stock"), shows.projection().get().columns());

        assertEquals(1, view.actions().size());
        Ast.Action action = view.actions().get(0);
        assertEquals("Restock", action.label());
        assertEquals("row", action.rowVar());
        assertEquals("Catalog", action.service());
        assertEquals("restock", action.method());
        assertEquals(2, action.args().size());
        Ast.ExprArg first = assertInstanceOf(Ast.ExprArg.class, action.args().get(0));
        assertInstanceOf(Ast.MemberExpr.class, first.value());
        Ast.AskArg second = assertInstanceOf(Ast.AskArg.class, action.args().get(1));
        assertEquals("Int", second.type().name());

        assertEquals(1, view.expects().size());
        Ast.ExpectColumns cols = assertInstanceOf(Ast.ExpectColumns.class, view.expects().get(0));
        assertEquals("table", cols.subject());
        assertEquals(List.of("name", "stock"), cols.columns());
    }

    @Test
    void parsesListReturnType() {
        Ast.Module m = Parsing.parse(SHOP_VIEW, "shop.sky");
        Ast.Method all = m.services().get(0).methods().get(0);
        assertTrue(all.returnType().list());
        assertEquals("Product", all.returnType().name());
    }

    @Test
    void parsesAppears() {
        Ast.Module m = Parsing.parse(SHOP_VIEW, "shop.sky");
        Ast.View view = m.views().get(0);

        assertEquals(3, view.appears().size());
        Ast.AppearsPlacement placement = assertInstanceOf(Ast.AppearsPlacement.class, view.appears().get(0));
        assertEquals("Restock", placement.label());
        assertEquals("toolbar", placement.region());
        Ast.AppearsStyle style = assertInstanceOf(Ast.AppearsStyle.class, view.appears().get(1));
        assertEquals("rows", style.subject());
        assertEquals("compact", style.value());
        Ast.AppearsColumnOrder order = assertInstanceOf(Ast.AppearsColumnOrder.class, view.appears().get(2));
        assertEquals(List.of("name", "stock"), order.columns());
    }
}
