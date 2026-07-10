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
        assertEquals("Int", assertInstanceOf(Ast.TypeRef.class, second.type()).name());

        assertEquals(1, view.expects().size());
        Ast.ExpectColumns cols = assertInstanceOf(Ast.ExpectColumns.class, view.expects().get(0));
        assertEquals("table", cols.subject());
        assertEquals(List.of("name", "stock"), cols.columns());
    }

    @Test
    void parsesListReturnType() {
        Ast.Module m = Parsing.parse(SHOP_VIEW, "shop.sky");
        Ast.Method all = m.services().get(0).methods().get(0);
        Ast.TypeRef returnType = assertInstanceOf(Ast.TypeRef.class, all.returnType());
        assertTrue(returnType.list());
        assertEquals("Product", returnType.name());
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

    // ----- the chapter-3 type surface -----------------------------------------

    @Test
    void parsesTypeDeclarations() {
        Ast.Module m = Parsing.parse("""
                module t
                type Slug        = Text matching /^[a-z0-9-]{1,64}$/
                type Percentage  = Int(0..100)
                type Quantity    = Int(1..)
                type PositiveMoney = Money where amount > 0
                """, "t.sky");

        assertEquals(4, m.types().size());

        Ast.TypeDecl slug = m.types().get(0);
        assertEquals("Slug", slug.name());
        assertEquals("Text", slug.base());
        Ast.Matching matching = assertInstanceOf(Ast.Matching.class, slug.refinement());
        assertEquals("^[a-z0-9-]{1,64}$", matching.regex());

        Ast.TypeDecl percentage = m.types().get(1);
        assertEquals("Int", percentage.base());
        Ast.Range range = assertInstanceOf(Ast.Range.class, percentage.refinement());
        assertEquals(0, range.lo().getAsLong());
        assertEquals(100, range.hi().getAsLong());

        Ast.Range open = assertInstanceOf(Ast.Range.class, m.types().get(2).refinement());
        assertEquals(1, open.lo().getAsLong());
        assertTrue(open.hi().isEmpty());

        Ast.TypeDecl positive = m.types().get(3);
        assertEquals("Money", positive.base());
        Ast.Where where = assertInstanceOf(Ast.Where.class, positive.refinement());
        Ast.BinExpr pred = assertInstanceOf(Ast.BinExpr.class, where.predicate());
        assertEquals(">", pred.op());
    }

    @Test
    void parsesRefinedGenericAndCollectionFieldTypes() {
        Ast.Module m = Parsing.parse("""
                module t
                entity User {
                  name     Text(1..120)
                  email    Email @unique
                  password Secret<Bytes>
                  tags     Set<Text>
                  prices   Map<Text, Money>
                  friend   Maybe<User>
                }
                """, "t.sky");

        List<Ast.Field> fields = m.entities().get(0).fields();

        Ast.RangedType name = assertInstanceOf(Ast.RangedType.class, fields.get(0).type());
        assertEquals("Text", name.base());
        assertEquals(1, name.lo().getAsLong());
        assertEquals(120, name.hi().getAsLong());

        Ast.Field email = fields.get(1);
        assertEquals("Email", assertInstanceOf(Ast.TypeRef.class, email.type()).name());
        assertTrue(email.unique());

        Ast.GenericType password = assertInstanceOf(Ast.GenericType.class, fields.get(2).type());
        assertEquals("Secret", password.name());
        assertEquals("Bytes", assertInstanceOf(Ast.TypeRef.class, password.args().get(0)).name());

        assertEquals("Set", assertInstanceOf(Ast.GenericType.class, fields.get(3).type()).name());

        Ast.GenericType prices = assertInstanceOf(Ast.GenericType.class, fields.get(4).type());
        assertEquals("Map", prices.name());
        assertEquals(2, prices.args().size());

        assertEquals("Maybe", assertInstanceOf(Ast.GenericType.class, fields.get(5).type()).name());
    }

    @Test
    void parsesFieldDefaults() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Account {
                  active    Bool    = true
                  fee       Money   = 9.99eur
                  note      Text    = "none"
                  createdAt Instant = now
                }
                """, "t.sky");

        List<Ast.Field> fields = m.entities().get(0).fields();
        Ast.BoolLit active = assertInstanceOf(Ast.BoolLit.class, fields.get(0).defaultValue().orElseThrow());
        assertTrue(active.value());
        Ast.MoneyLit fee = assertInstanceOf(Ast.MoneyLit.class, fields.get(1).defaultValue().orElseThrow());
        assertEquals("9.99", fee.amount().toPlainString());
        assertEquals("EUR", fee.currency());
        assertInstanceOf(Ast.StrLit.class, fields.get(2).defaultValue().orElseThrow());
        Ast.NameExpr now = assertInstanceOf(Ast.NameExpr.class, fields.get(3).defaultValue().orElseThrow());
        assertEquals("now", now.name());
    }

    @Test
    void parsesGeneralizedListShorthand() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Box {
                  labels [Text]
                  finds  [Maybe<Box>]
                }
                """, "t.sky");

        Ast.TypeRef labels = assertInstanceOf(Ast.TypeRef.class, m.entities().get(0).fields().get(0).type());
        assertTrue(labels.list());
        assertEquals("Text", labels.name());

        Ast.GenericType finds = assertInstanceOf(Ast.GenericType.class, m.entities().get(0).fields().get(1).type());
        assertEquals("List", finds.name());
        assertEquals("Maybe", assertInstanceOf(Ast.GenericType.class, finds.args().get(0)).name());
    }

    @Test
    void parsesMoneyAndBoolLiteralsInClauses() {
        Ast.Module m = Parsing.parse("""
                module t
                service Billing {
                  fee(active Bool) -> Money
                    intent   "The flat fee."
                    requires active == true
                    example  fee(true) -> 9.99eur
                }
                """, "t.sky");

        Ast.Method fee = m.services().get(0).methods().get(0);
        assertEquals("Money", assertInstanceOf(Ast.TypeRef.class, fee.returnType()).name());
        Ast.BinExpr requires = assertInstanceOf(Ast.BinExpr.class, fee.requires().get(0));
        assertInstanceOf(Ast.BoolLit.class, requires.right());
        Ast.Example example = fee.examples().get(0);
        assertInstanceOf(Ast.BoolLit.class, example.call().args().get(0));
        Ast.ExprResult result = assertInstanceOf(Ast.ExprResult.class, example.result());
        Ast.MoneyLit lit = assertInstanceOf(Ast.MoneyLit.class, result.value());
        assertEquals("9.99", lit.amount().toPlainString());
        assertEquals("EUR", lit.currency());
    }

    @Test
    void parsesAskWithRefinedType() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Product {
                  id Int
                }
                service Billing {
                  pay(id Int, amount Money) -> Product
                    intent "Pay."
                }
                view Pay at "/pay" {
                  shows  Billing.all() as a table of (id)
                  action "Pay" on row -> Billing.pay(row.id, ask Money)
                }
                """, "t.sky");

        Ast.AskArg ask = assertInstanceOf(Ast.AskArg.class, m.views().get(0).actions().get(0).args().get(1));
        assertEquals("Money", assertInstanceOf(Ast.TypeRef.class, ask.type()).name());
    }

    @Test
    void legacyFieldToStringIsUnchanged() {
        Ast.Field legacy = new Ast.Field("stock", new Ast.TypeRef("Int"), false, java.util.OptionalLong.of(0));
        assertEquals("Field[name=stock, type=TypeRef[name=Int, list=false], id=false, min=OptionalLong[0]]",
                legacy.toString());
    }

    // ----- the chapter-4 surface: effects and values ---------------------------

    @Test
    void parsesTheEffectsBudget() {
        Ast.Module m = Parsing.parse("""
                module t
                service S uses db, clock, mail {
                  f() -> Int  intent "x"
                }
                service T {
                  g() -> Int  intent "x"
                }
                """, "t.sky");
        assertEquals(List.of("db", "clock", "mail"), m.services().get(0).uses());
        assertTrue(m.services().get(1).uses().isEmpty());
    }

    @Test
    void parsesEntityValues() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Role {
                  name Text @id
                  values Member, Admin
                }
                """, "t.sky");
        Ast.Entity role = m.entities().get(0);
        assertEquals(List.of("Member", "Admin"), role.values());
        assertEquals(1, role.fields().size());
    }

    @Test
    void parsesMemberDefaults() {
        Ast.Module m = Parsing.parse("""
                module t
                entity User {
                  role Role = Role.Member
                }
                """, "t.sky");
        Ast.MemberExpr def = assertInstanceOf(Ast.MemberExpr.class,
                m.entities().get(0).fields().get(0).defaultValue().orElseThrow());
        assertEquals("Role", assertInstanceOf(Ast.NameExpr.class, def.target()).name());
        assertEquals("Member", def.field());
    }

    @Test
    void legacyEntityAndServiceToStringAreUnchanged() {
        assertEquals("Entity[name=E, fields=[]]",
                new Ast.Entity("E", List.of()).toString());
        assertEquals("Service[name=S, methods=[]]",
                new Ast.Service("S", List.of()).toString());
    }

    @Test
    void rejectsUnknownAnnotationAndMalformedTypeDecl() {
        assertThrows(IllegalArgumentException.class, () -> Parsing.parse("""
                module t
                entity E { x Int @nope }
                """, "t.sky"));
        assertThrows(SkyParseException.class, () -> Parsing.parse("""
                module t
                type X = Text matching 5
                """, "t.sky"));
    }
}
