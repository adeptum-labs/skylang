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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeCheckerTest {

    private static void check(String source) {
        Ast.Module m = Parsing.parse(source, "test.sky");
        new TypeChecker().check(m);
    }

    private static String service(String method) {
        return """
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                %s
                }
                """.formatted(method);
    }

    @Test
    void acceptsWellFormedMethod() {
        assertDoesNotThrow(() -> check(service("""
                  restock(p Product, units Int) -> Product
                    intent  "Increase stock."
                    requires units > 0
                    ensures  result.stock == p.stock + units
                    example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
                """)));
    }

    @Test
    void rejectsUnknownType() {
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  f(x Widget) -> Int
                    intent "x"
                """)));
        assertTrue(e.getMessage().contains("unknown type"));
    }

    @Test
    void rejectsNonBooleanEnsures() {
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  f(x Int) -> Int
                    intent  "id"
                    ensures result + x
                """)));
        assertTrue(e.getMessage().contains("boolean"));
    }

    @Test
    void rejectsExampleArgTypeMismatch() {
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  f(x Int) -> Int
                    example f("nope") -> 1
                """)));
        assertTrue(e.getMessage().contains("argument"));
    }

    @Test
    void rejectsMethodWithNoDriver() {
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  f(x Int) -> Int
                    requires x > 0
                """)));
        assertTrue(e.getMessage().contains("no driver"));
    }

    @Test
    void rejectsMinOnNonIntField() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { name Text @min(0) }
                service S { f(x Int) -> Int  intent "x" }
                """));
        assertTrue(e.getMessage().contains("@min"));
    }

    // ----- views -------------------------------------------------------------

    private static String withView(String view) {
        return """
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  all() -> [Product]  intent "all"
                  one() -> Product     intent "one"
                  restock(id Int, units Int) -> Product  intent "restock"
                }
                %s
                """.formatted(view);
    }

    @Test
    void acceptsWellFormedView() {
        assertDoesNotThrow(() -> check(withView("""
                view ProductList at "/products" {
                  shows  Catalog.all() as a table of (name, stock)
                  action "Restock" on row -> Catalog.restock(row.id, ask Int)
                  expect table has columns (name, stock)
                }
                """)));
    }

    @Test
    void rejectsViewUnknownColumn() {
        CheckException e = assertThrows(CheckException.class, () -> check(withView("""
                view V {
                  shows Catalog.all() as a table of (name, bogus)
                }
                """)));
        assertTrue(e.getMessage().contains("not a field"));
    }

    @Test
    void rejectsViewUnknownActionMethod() {
        CheckException e = assertThrows(CheckException.class, () -> check(withView("""
                view V {
                  shows  Catalog.all() as a table of (name)
                  action "X" on row -> Catalog.nope(row.id)
                }
                """)));
        assertTrue(e.getMessage().contains("no method"));
    }

    @Test
    void rejectsViewActionArgTypeMismatch() {
        CheckException e = assertThrows(CheckException.class, () -> check(withView("""
                view V {
                  shows  Catalog.all() as a table of (name)
                  action "Restock" on row -> Catalog.restock(row.id, ask Text)
                }
                """)));
        assertTrue(e.getMessage().contains("Int"));
    }

    @Test
    void shippedShopExampleParsesAndChecks() throws Exception {
        Ast.Module module = Parsing.parseFile(Path.of("examples/shop.sky"));
        assertEquals(1, module.views().size(), "the example should carry the ProductList view");
        assertDoesNotThrow(() -> new TypeChecker().check(module));
    }

    @Test
    void rejectsViewQueryNotList() {
        CheckException e = assertThrows(CheckException.class, () -> check(withView("""
                view V {
                  shows Catalog.one() as a table of (name)
                }
                """)));
        assertTrue(e.getMessage().contains("list"));
    }
}
