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
}
