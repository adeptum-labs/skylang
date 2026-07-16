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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static Ast.Result exampleResult(String result) {
        Ast.Module m = Parsing.parse("""
                module t
                entity Provider { id Int @id  name Text }
                service S {
                  upgrade(id Int) -> Provider
                    intent  "Upgrade."
                    example upgrade(1) -> %s
                }
                """.formatted(result), "t.sky");
        return m.services().get(0).methods().get(0).examples().get(0).result();
    }

    @Test
    void parsesExampleProseResult() {
        Ast.ProseResult prose = assertInstanceOf(Ast.ProseResult.class,
                exampleResult("a Provider on the Free tier"));
        assertEquals("a Provider on the Free tier", prose.text());
        Ast.ProseResult keyworded = assertInstanceOf(Ast.ProseResult.class,
                exampleResult("the provider's tier is upgraded"));
        assertEquals("the provider's tier is upgraded", keyworded.text());
    }

    @Test
    void entityResultStillWinsOverProse() {
        Ast.EntityResult entity = assertInstanceOf(Ast.EntityResult.class,
                exampleResult("a Provider with name \"x\""));
        assertEquals("Provider", entity.typeName());
    }

    @Test
    void whoseNearMissFallsBackToProse() {
        Ast.ProseResult prose = assertInstanceOf(Ast.ProseResult.class,
                exampleResult("a provider payment method is declined"));
        assertEquals("a provider payment method is declined", prose.text());
    }

    @Test
    void entityIntroducerNearMissFallsBackToProse() {
        Ast.ProseResult prose = assertInstanceOf(Ast.ProseResult.class,
                exampleResult("a provider payment method declined"));
        assertEquals("a provider payment method declined", prose.text());
    }

    @Test
    void entityDeadEndFallsBackToProse() {
        Ast.ProseResult prose = assertInstanceOf(Ast.ProseResult.class,
                exampleResult("a Post owned by that user"));
        assertEquals("a Post owned by that user", prose.text());
    }

    @Test
    void whoseResultsKeepTheirLaxArticle() {
        assertInstanceOf(Ast.WhoseResult.class,
                exampleResult("an Provider whose name is \"x\""));
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
        assertEquals("row", action.rowVar().orElseThrow());
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
    void parsesPageLevelActionWithoutSubject() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Account { id Int @id  email Text }
                service Session {
                  signIn(email Text) -> Account  intent "Sign in."
                }
                page Login at "/" {
                  shows  Session.signIn("x") as a table of (email)
                  action "Sign in" -> Session.signIn(ask Text)
                }
                """, "t.sky");
        Ast.Action action = m.views().get(0).actions().get(0);
        assertTrue(action.rowVar().isEmpty());
        assertEquals("Session", action.service());
        assertEquals("signIn", action.method());
    }

    @Test
    void parsesZeroArgumentActionTarget() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Account { id Int @id  email Text }
                service Session {
                  signOut() -> Account  intent "Sign out."
                }
                page Login at "/" {
                  shows  Session.signOut() as a table of (email)
                  action "Sign out" -> Session.signOut()
                }
                """, "t.sky");
        Ast.Action action = m.views().get(0).actions().get(0);
        assertTrue(action.rowVar().isEmpty());
        assertTrue(action.args().isEmpty());
    }

    @Test
    void actionToStringPinsThePresentSubjectForm() {
        assertEquals("Action[label=Restock, rowVar=row, service=Catalog, method=restock, args=[]]",
                new Ast.Action("Restock", java.util.Optional.of("row"), "Catalog", "restock",
                        List.of()).toString());
        assertEquals("Action[label=Sign out, service=Session, method=signOut, args=[]]",
                new Ast.Action("Sign out", java.util.Optional.empty(), "Session", "signOut",
                        List.of()).toString());
    }

    @Test
    void parsesPageNavigationActionTarget() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Product { id Int @id  name Text }
                service Catalog {
                  all() -> [Product]  intent "Every product."
                }
                view ProductList at "/products" {
                  shows  Catalog.all() as a table of (name)
                }
                page Home at "/" {
                  shows  Catalog.all() as a table of (name)
                  action "Products" -> page ProductList
                }
                """, "t.sky");
        Ast.Action action = m.views().get(1).actions().get(0);
        assertEquals("Products", action.label());
        assertTrue(action.rowVar().isEmpty());
        assertEquals("ProductList", action.pageTarget().orElseThrow());
        assertTrue(action.args().isEmpty());
    }

    @Test
    void actionToStringShowsAPageTargetOnlyWhenPresent() {
        assertEquals("Action[label=Products, page=ProductList]",
                new Ast.Action("Products", java.util.Optional.empty(), "", "",
                        List.of(), java.util.Optional.of("ProductList")).toString());
        assertEquals("Action[label=Sign out, service=Session, method=signOut, args=[]]",
                new Ast.Action("Sign out", java.util.Optional.empty(), "Session", "signOut",
                        List.of(), java.util.Optional.empty()).toString());
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
    void parsesDurationLiterals() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Plan {
                  retention Duration = 30d
                  grace     Duration = 2h
                  slot      Duration = 15m
                  timeout   Duration = 45s
                }
                """, "t.sky");

        List<Ast.Field> fields = m.entities().get(0).fields();
        Ast.DurationLit retention =
                assertInstanceOf(Ast.DurationLit.class, fields.get(0).defaultValue().orElseThrow());
        assertEquals(30, retention.amount());
        assertEquals("d", retention.unit());
        assertEquals("h", assertInstanceOf(Ast.DurationLit.class,
                fields.get(1).defaultValue().orElseThrow()).unit());
        assertEquals("m", assertInstanceOf(Ast.DurationLit.class,
                fields.get(2).defaultValue().orElseThrow()).unit());
        assertEquals("s", assertInstanceOf(Ast.DurationLit.class,
                fields.get(3).defaultValue().orElseThrow()).unit());
    }

    @Test
    void moneySuffixOutmatchesADurationSuffix() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Account { fee Money = 30sek }
                """, "t.sky");
        Ast.MoneyLit fee = assertInstanceOf(Ast.MoneyLit.class,
                m.entities().get(0).fields().get(0).defaultValue().orElseThrow());
        assertEquals("SEK", fee.currency());
        assertEquals("30", fee.amount().toPlainString());
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

    private static Ast.View loginWith(String clauses) {
        return Parsing.parse("""
                module t
                entity Account { id Int @id  email Text }
                service Session {
                  current() -> Maybe<Account>  intent "x"
                }
                page Login at "/" {
                  shows  Session.current() as a summary of (email)
                %s
                }
                """.formatted(clauses), "t.sky").views().get(0);
    }

    @Test
    void parsesAViewParamClause() {
        Ast.View view = loginWith("  param accessDenied Bool");
        assertEquals(1, view.params().size());
        assertEquals("accessDenied", view.params().get(0).name());
        assertEquals("Bool", assertInstanceOf(Ast.TypeRef.class, view.params().get(0).type()).name());
    }

    @Test
    void parsesAnAppearsWhenOverADeclaredParam() {
        Ast.View view = loginWith("""
                  param  accessDenied Bool
                  appears the access-denied alert when accessDenied
                """);
        Ast.AppearsWhen when = assertInstanceOf(Ast.AppearsWhen.class, view.appears().get(0));
        assertTrue(when.subject().contains("alert"), when.subject().toString());
        assertEquals("accessDenied", assertInstanceOf(Ast.NameExpr.class, when.when()).name());
    }

    @Test
    void degradesAppearsWhenToProseWithoutParams() {
        Ast.View view = loginWith("  appears the badge when stock");
        Ast.AppearsProse prose = assertInstanceOf(Ast.AppearsProse.class, view.appears().get(0));
        assertEquals("the badge when stock", prose.text());
    }

    @Test
    void keepsProseAppearsWithKeywordTails() {
        Ast.View view = loginWith("  appears the Google sign - in button when nobody is signed in");
        assertInstanceOf(Ast.AppearsProse.class, view.appears().get(0));
    }

    @Test
    void rejectsAMisspelledParamKeyword() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loginWith("  params accessDenied Bool"));
        assertTrue(e.getMessage().contains("param"), e.getMessage());
    }

    @Test
    void viewParamsToStringIsAppendOnly() {
        assertTrue(loginWith("  param accessDenied Bool").toString().contains(", params=["));
        assertFalse(loginWith("").toString().contains("params="),
                "a param-less view keeps its original string form");
    }

    private static Ast.Entity companyWith(String field) {
        return Parsing.parse("""
                module t
                entity Company {
                  id   Int @id
                  name Text
                  %s
                }
                entity Permission { id Int @id  name Text  owner Company }
                """.formatted(field), "t.sky").entities().get(0);
    }

    @Test
    void parsesMappedByAnnotation() {
        Ast.Field permissions = companyWith("permissions [Permission] @mappedBy(owner)")
                .fields().get(2);
        assertEquals("owner", permissions.mappedBy().orElseThrow());
        assertTrue(permissions.type() instanceof Ast.TypeRef ref && ref.list());
    }

    @Test
    void mappedByToStringIsAppendOnly() {
        Ast.Field permissions = companyWith("permissions [Permission] @mappedBy(owner)")
                .fields().get(2);
        assertTrue(permissions.toString().endsWith(", mappedBy=owner]"), permissions.toString());
        Ast.Field plain = companyWith("labels [Text]").fields().get(2);
        assertFalse(plain.toString().contains("mappedBy"), plain.toString());
    }

    @Test
    void mappedByRejectsAMissingOrIntegerArgument() {
        IllegalArgumentException bare = assertThrows(IllegalArgumentException.class,
                () -> companyWith("permissions [Permission] @mappedBy"));
        assertTrue(bare.getMessage().contains("back-reference"), bare.getMessage());
        IllegalArgumentException numbered = assertThrows(IllegalArgumentException.class,
                () -> companyWith("permissions [Permission] @mappedBy(3)"));
        assertTrue(numbered.getMessage().contains("back-reference"), numbered.getMessage());
    }

    @Test
    void parsesTodayDefaultAsName() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Course { day Date = today }
                """, "t.sky");
        Ast.NameExpr def = assertInstanceOf(Ast.NameExpr.class,
                m.entities().get(0).fields().get(0).defaultValue().orElseThrow());
        assertEquals("today", def.name());
    }

    @Test
    void legacyFieldToStringIsUnchanged() {
        Ast.Field legacy = new Ast.Field("stock", new Ast.TypeRef("Int"), false, java.util.OptionalLong.of(0));
        assertEquals("Field[name=stock, type=TypeRef[name=Int, list=false], id=false, min=OptionalLong[0]]",
                legacy.toString());
    }

    @Test
    void parsesScopedUniqueAnnotation() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Provider { id Int @id  name Text }
                entity UserAccount {
                  id       Int @id
                  provider Provider
                  email    Email @unique(provider)
                }
                """, "t.sky");
        Ast.Field email = m.entities().get(1).fields().get(2);
        assertTrue(email.unique());
        assertEquals("provider", email.uniqueScope().orElseThrow());
    }

    @Test
    void scopedUniqueToStringIsAppendOnly() {
        Ast.Module m = Parsing.parse("""
                module t
                entity E {
                  scoped Text @unique(owner)
                  bare   Text @unique
                  owner  Text
                }
                """, "t.sky");
        assertTrue(m.entities().get(0).fields().get(0).toString()
                .endsWith(", unique=true, uniqueScope=owner]"));
        assertTrue(m.entities().get(0).fields().get(1).toString().endsWith(", unique=true]"));
    }

    @Test
    void uniqueRejectsAnIntegerArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parsing.parse("""
                module t
                entity E { email Email @unique(3) }
                """, "t.sky"));
        assertTrue(e.getMessage().contains("field name"), e.getMessage());
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
        assertEquals(List.of("Member", "Admin"), role.valueNames());
        assertTrue(role.values().stream().allMatch(v -> v.pins().isEmpty()));
        assertEquals(1, role.fields().size());
    }

    @Test
    void parsesPinnedValues() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Tier {
                  name  Text @id
                  label Text
                  price Money
                  values Free with label "Free" and price 0eur, Pro with label "Pro" and price 399sek, Hidden
                }
                """, "t.sky");
        Ast.Entity tier = m.entities().get(0);
        assertEquals(List.of("Free", "Pro", "Hidden"), tier.valueNames());
        Ast.ValueDef free = tier.values().get(0);
        assertEquals("label", free.pins().get(0).field());
        assertEquals("Free", assertInstanceOf(Ast.StrLit.class, free.pins().get(0).expected()).value());
        assertEquals("price", free.pins().get(1).field());
        Ast.MoneyLit price = assertInstanceOf(Ast.MoneyLit.class, free.pins().get(1).expected());
        assertEquals("EUR", price.currency());
        assertEquals(2, tier.values().get(1).pins().size());
        assertTrue(tier.values().get(2).pins().isEmpty());
    }

    @Test
    void pinnedValuesAcceptConstantReferences() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Tier {
                  name Text @id
                  role Role
                  values Free with role Role.Member
                }
                """, "t.sky");
        Ast.FieldExpect pin = m.entities().get(0).values().get(0).pins().get(0);
        Ast.MemberExpr ref = assertInstanceOf(Ast.MemberExpr.class, pin.expected());
        assertEquals("Role", assertInstanceOf(Ast.NameExpr.class, ref.target()).name());
        assertEquals("Member", ref.field());
    }

    @Test
    void valuePinsRequireTheWithKeyword() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parsing.parse("""
                module t
                entity Tier {
                  name  Text @id
                  label Text
                  values Free having label "Free"
                }
                """, "t.sky"));
        assertTrue(e.getMessage().contains("with"), e.getMessage());
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
        Ast.Entity role = Parsing.parse("""
                module t
                entity Role { name Text @id  values Member, Admin }
                """, "t.sky").entities().get(0);
        assertTrue(role.toString().endsWith(", values=[Member, Admin]]"),
                "pin-less values keep the original bare-name rendering: " + role);
    }

    @Test
    void pinnedValuesToStringIsAppendOnly() {
        Ast.Entity tier = Parsing.parse("""
                module t
                entity Tier {
                  name  Text @id
                  label Text
                  values Free with label "Free", Hidden
                }
                """, "t.sky").entities().get(0);
        String values = tier.toString().substring(tier.toString().indexOf("values="));
        assertTrue(values.contains("Free with "),
                "a pinned value appends its pins after the name: " + values);
        assertTrue(values.contains("Hidden") && !values.contains("Hidden with"),
                "a pin-less value stays a bare name: " + values);
    }

    // ----- the chapter-5 surface: raises, old, not, aggregates -----------------

    @Test
    void parsesRaisesConditions() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Product { id Int @id  stock Int }
                entity NotFound { }
                entity BadInput { }
                entity Duplicate { }
                service S {
                  restock(id Int, units Int) -> Product
                    intent "x"
                    raises NotFound  when no product has that id
                    raises BadInput  when units <= 0
                    raises Duplicate when email already registered
                }
                """, "t.sky");
        List<Ast.Raise> raises = m.services().get(0).methods().get(0).raises();
        assertEquals(3, raises.size());

        Ast.Raise notFound = raises.get(0);
        assertEquals("NotFound", notFound.error());
        Ast.NoSuch noSuch = assertInstanceOf(Ast.NoSuch.class, notFound.condition());
        assertEquals("product", noSuch.entityWord());
        assertEquals("id", noSuch.fieldWord());

        Ast.Raise badInput = raises.get(1);
        Ast.CondExpr cond = assertInstanceOf(Ast.CondExpr.class, badInput.condition());
        assertInstanceOf(Ast.BinExpr.class, cond.expr());

        Ast.AlreadyRegistered dup = assertInstanceOf(Ast.AlreadyRegistered.class, raises.get(2).condition());
        assertEquals("email", assertInstanceOf(Ast.NameExpr.class, dup.value()).name());
    }

    @Test
    void parsesOldNotAndIsForms() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Product { id Int @id  stock Int }
                service S {
                  restock(id Int, units Int) -> Product
                    intent   "x"
                    requires not (units <= 0)
                    ensures  result.stock == old(result.stock) + units
                  check(p Product, tags [Text]) -> Bool
                    intent   "x"
                    requires tags is empty
                    ensures  result is not false
                }
                """, "t.sky");
        Ast.Method restock = m.services().get(0).methods().get(0);
        assertInstanceOf(Ast.NotExpr.class, restock.requires().get(0));
        Ast.BinExpr ensures = assertInstanceOf(Ast.BinExpr.class, restock.ensures().get(0));
        Ast.BinExpr plus = assertInstanceOf(Ast.BinExpr.class, ensures.right());
        assertInstanceOf(Ast.OldExpr.class, plus.left());

        Ast.Method check = m.services().get(0).methods().get(1);
        Ast.EmptyCheck empty = assertInstanceOf(Ast.EmptyCheck.class, check.requires().get(0));
        assertEquals("tags", assertInstanceOf(Ast.NameExpr.class, empty.value()).name());
        Ast.BinExpr isNot = assertInstanceOf(Ast.BinExpr.class, check.ensures().get(0));
        assertEquals("!=", isNot.op());
    }

    @Test
    void parsesAggregates() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Product { id Int @id  stock Int }
                service S uses db {
                  totalStock() -> Int
                    intent  "x"
                    ensures result == sum of (p.stock for p in all products)
                  emptyCount(products [Product]) -> Int
                    intent  "x"
                    ensures result == count of (p for p in products where p.stock == 0)
                }
                """, "t.sky");
        Ast.BinExpr total = assertInstanceOf(Ast.BinExpr.class,
                m.services().get(0).methods().get(0).ensures().get(0));
        Ast.AggExpr sum = assertInstanceOf(Ast.AggExpr.class, total.right());
        assertEquals("sum", sum.kind());
        assertEquals("p", sum.var());
        assertInstanceOf(Ast.MemberExpr.class, sum.value());
        Ast.AllOf all = assertInstanceOf(Ast.AllOf.class, sum.source());
        assertEquals("products", all.word());
        assertTrue(sum.where().isEmpty());

        Ast.AggExpr count = assertInstanceOf(Ast.AggExpr.class,
                ((Ast.BinExpr) m.services().get(0).methods().get(1).ensures().get(0)).right());
        assertEquals("count", count.kind());
        assertTrue(count.where().isPresent());
        assertInstanceOf(Ast.SourceExpr.class, count.source());
    }

    // ----- the chapter-6 surface: spec blocks and extended examples ------------

    @Test
    void parsesSpecBlocks() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Account { id Int @id  balance Money }
                entity Receipt { id Int @id  amount Money }
                entity InsufficientFunds { }
                service Bank uses db {
                  transfer(from Account, to Account, amount Money) -> Receipt
                    spec "moves money atomically" {
                      given from.balance == 100eur and to.balance == 0eur
                      when  transfer(from, to, 30eur)
                      then  from.balance == 70eur
                            to.balance   == 30eur
                    }
                    spec "rejects overdraft" {
                      given from.balance == 10eur
                      when  transfer(from, to, 50eur)
                      then  raises InsufficientFunds
                            from.balance == 10eur
                    }
                }
                """, "t.sky");
        Ast.Method transfer = m.services().get(0).methods().get(0);
        assertEquals(2, transfer.specs().size());

        Ast.Spec atomic = transfer.specs().get(0);
        assertEquals("moves money atomically", atomic.title());
        assertInstanceOf(Ast.BinExpr.class, atomic.given().orElseThrow());
        assertEquals("transfer", atomic.when().callee());
        assertEquals(3, atomic.when().args().size());
        assertEquals(2, atomic.then().size());
        assertInstanceOf(Ast.ThenExpr.class, atomic.then().get(0));

        Ast.Spec overdraft = transfer.specs().get(1);
        Ast.ThenRaises raises = assertInstanceOf(Ast.ThenRaises.class, overdraft.then().get(0));
        assertEquals("InsufficientFunds", raises.error());
        assertInstanceOf(Ast.ThenExpr.class, overdraft.then().get(1));
    }

    @Test
    void parsesSeededExamplesAndRaisesResults() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Product { id Int @id  stock Int }
                entity BadInput { }
                service Catalog uses db {
                  restock(id Int, units Int) -> Product
                    example restock(7, 3) on a Product with stock 5 -> stock 8
                    example restock(7, 0) -> raises BadInput
                }
                """, "t.sky");
        Ast.Method restock = m.services().get(0).methods().get(0);

        Ast.Example seeded = restock.examples().get(0);
        Ast.Seed seed = seeded.seed().orElseThrow();
        assertEquals("Product", seed.entityName());
        assertEquals("stock", seed.fields().get(0).field());
        Ast.FieldsResult fields = assertInstanceOf(Ast.FieldsResult.class, seeded.result());
        assertEquals("stock", fields.fields().get(0).field());
        assertInstanceOf(Ast.IntLit.class, fields.fields().get(0).expected());

        Ast.RaisesResult raised = assertInstanceOf(Ast.RaisesResult.class, restock.examples().get(1).result());
        assertEquals("BadInput", raised.error());
    }

    // ----- the chapter-7 surface: policies --------------------------------------

    @Test
    void parsesPolicies() {
        Ast.Module m = Parsing.parse("""
                module t
                type Password = Text(12..128)
                entity WeakPassword { }
                policy StrongPasswords {
                  whenever a Password is constructed
                  require  length >= 12 and contains a symbol
                  else     raise WeakPassword
                }
                policy NoSecretsInLogs {
                  whenever a Secret is passed to a logger
                  forbid
                }
                """, "t.sky");
        assertEquals(2, m.policies().size());

        Ast.Policy strong = m.policies().get(0);
        assertEquals("StrongPasswords", strong.name());
        Ast.Constructed constructed = assertInstanceOf(Ast.Constructed.class, strong.whenever());
        assertEquals("Password", constructed.typeWord());
        Ast.RequireRule rule = assertInstanceOf(Ast.RequireRule.class, strong.rule());
        assertEquals(2, rule.terms().size());
        Ast.TermExpr length = assertInstanceOf(Ast.TermExpr.class, rule.terms().get(0));
        assertInstanceOf(Ast.BinExpr.class, length.expr());
        Ast.Contains contains = assertInstanceOf(Ast.Contains.class, rule.terms().get(1));
        assertEquals("symbol", contains.what());
        assertEquals("WeakPassword", rule.raise().orElseThrow());

        Ast.Policy noLogs = m.policies().get(1);
        Ast.PassedToLogger passed = assertInstanceOf(Ast.PassedToLogger.class, noLogs.whenever());
        assertEquals("Secret", passed.typeWord());
        assertInstanceOf(Ast.ForbidRule.class, noLogs.rule());
    }

    @Test
    void rejectsMalformedPolicyPhrases() {
        assertThrows(IllegalArgumentException.class, () -> Parsing.parse("""
                module t
                policy Vague {
                  whenever a Thing is misplaced
                  forbid
                }
                """, "t.sky"));
    }

    // ----- the chapter-8 surface: native blocks ---------------------------------

    @Test
    void parsesNativeJavaBlocks() {
        Ast.Module m = Parsing.parse("""
                module t
                service Crypto {
                  hash(input Bytes) -> Bytes
                    ensures result.length == 32
                    java {
                      var md = java.security.MessageDigest.getInstance("SHA-256");
                      if (input.size() > 0) { /* nested braces stay balanced */ }
                      return Bytes.of(md.digest(input.toByteArray()));
                    }
                  plain(x Int) -> Int
                    intent "No native body here."
                }
                """, "t.sky");
        Ast.Method hash = m.services().get(0).methods().get(0);
        String body = hash.nativeBody().orElseThrow();
        assertTrue(body.contains("MessageDigest.getInstance(\"SHA-256\")"));
        assertTrue(body.contains("nested braces"), "nested braces must stay inside the block");
        assertEquals(1, hash.ensures().size(), "contracts ride along with the native body");
        assertTrue(m.services().get(0).methods().get(1).nativeBody().isEmpty());
    }

    @Test
    void ensuresContinuationLinesJoinOneKeyword() {
        Ast.Module m = Parsing.parse("""
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  add(name Text, price Int) -> Product
                    intent  "Create a product with zero initial stock."
                    ensures result.stock == 0
                            result.name  == name
                            result.price == price
                }
                """, "shop.sky");
        Ast.Method add = m.services().get(0).methods().get(0);
        assertEquals(3, add.ensures().size(),
                "continuation lines under one ensures keyword are separate clauses");
    }

    @Test
    void nothingAndWhoseResultsParse() {
        Ast.Module m = Parsing.parse("""
                module shop
                entity Product { id Int  name Text }
                service Catalog uses db {
                  find(id Int) -> Maybe<Product>
                    intent  "The product with that id, if one exists."
                    example find(999) -> nothing
                  first() -> Product
                    intent  "The first product."
                    example first() -> a Product whose name is "Notebook" and whose id is not 0
                }
                """, "shop.sky");
        Ast.Method find = m.services().get(0).methods().get(0);
        assertInstanceOf(Ast.NothingResult.class, find.examples().get(0).result());
        Ast.Method first = m.services().get(0).methods().get(1);
        Ast.WhoseResult whose = (Ast.WhoseResult) first.examples().get(0).result();
        assertEquals("Product", whose.typeName());
        assertEquals(Ast.WhoseKind.EQUALS, whose.expects().get(0).kind());
        assertEquals(Ast.WhoseKind.NOT_EQUALS, whose.expects().get(1).kind());
    }

    @Test
    void whosePlacedAtIsSetParsesAsPresence() {
        Ast.Module m = Parsing.parse("""
                module shop
                entity Order { id Int  placedAt Maybe<Instant> }
                service Orders uses db {
                  place(o Order) -> Order
                    intent  "Place it."
                    example place(Order(1, Maybe.nothing)) -> an Order whose placedAt is set
                }
                """, "shop.sky");
        Ast.WhoseResult whose = (Ast.WhoseResult)
                m.services().get(0).methods().get(0).examples().get(0).result();
        assertEquals(Ast.WhoseKind.IS_SET, whose.expects().get(0).kind());
    }

    @Test
    void parsesNativeTsBlocksWithTheirKeyword() {
        Ast.Module m = Parsing.parse("""
                module t
                service Ids {
                  next(seed Int) -> Int
                    intent "Advance the seed."
                    ts {
                      return seed + 1n;
                    }
                }
                """, "t.sky");
        Ast.Method next = m.services().get(0).methods().get(0);
        assertEquals("ts", next.nativeKeyword());
        assertTrue(next.nativeBody().orElseThrow().contains("seed + 1n"));
    }

    @Test
    void javaBlocksCarryTheJavaKeyword() {
        Ast.Module m = Parsing.parse("""
                module t
                service Crypto {
                  hash(x Int) -> Int
                    intent "x"
                    java { return x; }
                }
                """, "t.sky");
        assertEquals("java", m.services().get(0).methods().get(0).nativeKeyword());
    }

    @Test
    void nativeMethodToStringOnlyNamesAForeignKeyword() {
        Ast.Module ts = Parsing.parse("""
                module t
                service S { f(x Int) -> Int  intent "x"  ts { return x; } }
                """, "t.sky");
        Ast.Module java = Parsing.parse("""
                module t
                service S { f(x Int) -> Int  intent "x"  java { return x; } }
                """, "t.sky");
        assertTrue(ts.services().get(0).methods().get(0).toString().contains("nativeKeyword=ts"));
        assertFalse(java.services().get(0).methods().get(0).toString().contains("nativeKeyword"),
                "a java block must print exactly as it always has — frozen hashes depend on it");
    }

    @Test
    void legacyExampleToStringIsUnchanged() {
        Ast.Example legacy = new Ast.Example(new Ast.CallExpr("f", List.of()),
                new Ast.ExprResult(new Ast.IntLit(1)));
        assertEquals("Example[call=CallExpr[callee=f, args=[]], result=ExprResult[value=IntLit[value=1]]]",
                legacy.toString());
    }

    @Test
    void legacyMethodToStringIsUnchanged() {
        Ast.Method legacy = new Ast.Method("f", List.of(), new Ast.TypeRef("Int"),
                java.util.Optional.of("x"), List.of(), List.of(), List.of());
        assertEquals("Method[name=f, params=[], returnType=TypeRef[name=Int, list=false], "
                + "intent=Optional[x], requires=[], ensures=[], examples=[]]", legacy.toString());
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
