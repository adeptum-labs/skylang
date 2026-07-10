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
    void shippedBankExampleParsesAndChecks() throws Exception {
        Ast.Module module = Parsing.parseFile(Path.of("examples/bank.sky"));
        assertEquals(2, module.types().size(), "the example should declare Iban and PositiveMoney");
        assertEquals(1, module.views().size(), "the example should carry the AccountList view");
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

    @Test
    void acceptsWellFormedAppears() {
        assertDoesNotThrow(() -> check(withView("""
                view V {
                  shows  Catalog.all() as a table of (name, stock)
                  action "Restock" on row -> Catalog.restock(row.id, ask Int)
                  appears action "Restock" in toolbar
                  appears rows is compact
                  appears columns (name, stock)
                }
                """)));
    }

    @Test
    void rejectsAppearsUnknownAction() {
        CheckException e = assertThrows(CheckException.class, () -> check(withView("""
                view V {
                  shows Catalog.all() as a table of (name)
                  appears action "Nope" in toolbar
                }
                """)));
        assertTrue(e.getMessage().contains("no action"));
    }

    @Test
    void rejectsAppearsColumnOrderUnknownField() {
        CheckException e = assertThrows(CheckException.class, () -> check(withView("""
                view V {
                  shows Catalog.all() as a table of (name)
                  appears columns (name, bogus)
                }
                """)));
        assertTrue(e.getMessage().contains("not a field"));
    }

    @Test
    void rejectsAppearsUnknownStyleSubject() {
        CheckException e = assertThrows(CheckException.class, () -> check(withView("""
                view V {
                  shows Catalog.all() as a table of (name)
                  appears sidebar is compact
                }
                """)));
        assertTrue(e.getMessage().contains("sidebar"));
    }

    // ----- the chapter-3 type surface -----------------------------------------

    @Test
    void acceptsTheFullTypeSurface() {
        assertDoesNotThrow(() -> check("""
                module bank
                type Slug     = Text matching /^[a-z0-9-]{1,64}$/
                type Quantity = Int(1..)
                type PositiveMoney = Money where amount > 0
                entity Account {
                  id        Int             @id
                  owner     Email           @unique
                  name      Text(1..120)
                  balance   Money
                  secret    Secret<Bytes>
                  opened    Instant
                  active    Bool            = true
                  tags      Set<Text>
                  aliases   Map<Text, Text>
                }
                service Accounts {
                  find(slug Slug) -> Maybe<Account>
                    intent "Find by slug."
                  fee(units Quantity) -> Money
                    intent   "The fee for that many units."
                    requires units > 0
                    example  fee(3) -> 9.99eur
                }
                """));
    }

    @Test
    void rejectsRangeLiteralOutOfBounds() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                type Percentage = Int(0..100)
                service S {
                  f(p Percentage) -> Int
                    intent  "x"
                    example f(150) -> 1
                }
                """));
        assertTrue(e.getMessage().contains("0..100"), e.getMessage());
    }

    @Test
    void rejectsTextLengthLiteralOutOfBounds() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                service S {
                  f(s Text(1..3)) -> Int
                    intent  "x"
                    example f("abcd") -> 1
                }
                """));
        assertTrue(e.getMessage().contains("1..3"), e.getMessage());
    }

    @Test
    void checksRegexLiteralsAtCompileTime() {
        assertDoesNotThrow(() -> check("""
                module m
                type Slug = Text matching /^[a-z0-9-]{1,64}$/
                service S {
                  f(s Slug) -> Int  intent "x"  example f("good-slug") -> 1
                }
                """));
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                type Slug = Text matching /^[a-z0-9-]{1,64}$/
                service S {
                  f(s Slug) -> Int  intent "x"  example f("Bad Slug!") -> 1
                }
                """));
        assertTrue(e.getMessage().contains("Slug"), e.getMessage());
    }

    @Test
    void namedTypesAreNominal() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                type Percentage = Int(0..100)
                type Quantity   = Int(1..)
                service S {
                  f(p Percentage, q Quantity) -> Bool
                    intent   "x"
                    requires p == q
                }
                """));
        assertTrue(e.getMessage().contains("Percentage"), e.getMessage());
    }

    @Test
    void refinedTypesWidenToTheirBase() {
        assertDoesNotThrow(() -> check("""
                module m
                type Quantity = Int(1..)
                service S {
                  f(q Quantity) -> Int
                    intent   "x"
                    requires q > 0
                    ensures  result == q + 1
                }
                """));
    }

    @Test
    void moneyArithmeticIsRestricted() {
        assertDoesNotThrow(() -> check("""
                module m
                service S {
                  f(price Money, fee Money, times Int) -> Money
                    intent   "x"
                    requires price < fee
                    ensures  result == price + fee * times
                }
                """));
        CheckException plus = assertThrows(CheckException.class, () -> check("""
                module m
                service S {
                  f(price Money) -> Money  intent "x"  ensures result == price + 1
                }
                """));
        assertTrue(plus.getMessage().contains("Money"), plus.getMessage());
        CheckException times = assertThrows(CheckException.class, () -> check("""
                module m
                service S {
                  f(price Money, fee Money) -> Money  intent "x"  ensures result == price * fee
                }
                """));
        assertTrue(times.getMessage().contains("Money"), times.getMessage());
    }

    @Test
    void rejectsCrossCurrencyLiterals() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                service S {
                  f() -> Bool  intent "x"  ensures 9.99eur == 9.99usd
                }
                """));
        assertTrue(e.getMessage().contains("currenc"), e.getMessage());
    }

    @Test
    void instantsCompareButDoNotAdd() {
        assertDoesNotThrow(() -> check("""
                module m
                service S {
                  f(a Instant, b Instant) -> Bool  intent "x"  ensures result == (a < b)
                }
                """));
        assertThrows(CheckException.class, () -> check("""
                module m
                service S {
                  f(a Instant, b Instant) -> Instant  intent "x"  ensures result == a + b
                }
                """));
    }

    @Test
    void rejectsArithmeticOnMaybe() {
        assertThrows(CheckException.class, () -> check("""
                module m
                service S {
                  f(x Maybe<Int>) -> Int  intent "x"  ensures result == x + 1
                }
                """));
    }

    @Test
    void rejectsSecretInViewColumnsAndAsk() {
        CheckException column = assertThrows(CheckException.class, () -> check("""
                module m
                entity User { id Int  password Secret<Text> }
                service Users { all() -> [User]  intent "all" }
                view V {
                  shows Users.all() as a table of (id, password)
                }
                """));
        assertTrue(column.getMessage().contains("Secret"), column.getMessage());
        CheckException ask = assertThrows(CheckException.class, () -> check("""
                module m
                entity User { id Int  password Secret<Text> }
                service Users {
                  all() -> [User]  intent "all"
                  set(id Int, password Secret<Text>) -> User  intent "set"
                }
                view V {
                  shows  Users.all() as a table of (id)
                  action "Set" on row -> Users.set(row.id, ask Secret<Text>)
                }
                """));
        assertTrue(ask.getMessage().contains("prompt"), ask.getMessage());
    }

    @Test
    void rejectsNestedSecret() {
        assertThrows(CheckException.class, () -> check("""
                module m
                entity E { x Secret<Secret<Text>> }
                service S { f() -> Int  intent "x" }
                """));
    }

    @Test
    void acceptsAskWithConvertedAndRefinedTypes() {
        assertDoesNotThrow(() -> check("""
                module m
                type Quantity = Int(1..)
                entity Order { id Int }
                service Orders {
                  all() -> [Order]  intent "all"
                  pay(id Int, amount Money, units Quantity, contact Email) -> Order  intent "pay"
                }
                view V {
                  shows  Orders.all() as a table of (id)
                  action "Pay" on row -> Orders.pay(row.id, ask Money, ask Quantity, ask Email)
                }
                """));
    }

    @Test
    void rejectsAskThatWouldNeedARuntimeProof() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                type Quantity = Int(1..)
                entity Order { id Int }
                service Orders {
                  all() -> [Order]  intent "all"
                  pay(id Int, units Quantity) -> Order  intent "pay"
                }
                view V {
                  shows  Orders.all() as a table of (id)
                  action "Pay" on row -> Orders.pay(row.id, ask Int)
                }
                """));
        assertTrue(e.getMessage().contains("Quantity"), e.getMessage());
    }

    @Test
    void rejectsUniqueOnUnsuitableFields() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { pw Secret<Text> @unique }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(e.getMessage().contains("@unique"), e.getMessage());
    }

    @Test
    void checksFieldDefaults() {
        assertDoesNotThrow(() -> check("""
                module m
                entity E { active Bool = true  fee Money = 9.99eur  note Text = "none" }
                service S { f() -> Int  intent "x" }
                """));
        CheckException range = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { stock Int(0..10) = 20 }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(range.getMessage().contains("0..10"), range.getMessage());
        CheckException mismatch = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { name Text = 5 }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(mismatch.getMessage().contains("Text"), mismatch.getMessage());
        CheckException member = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { role Text = Role.Member }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(member.getMessage().contains("Role"), member.getMessage());
        CheckException call = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { note Text = pick("a") }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(call.getMessage().contains("literal"), call.getMessage());
    }

    @Test
    void acceptsNowDefaultOnInstantFields() {
        assertDoesNotThrow(() -> check("""
                module m
                entity E { createdAt Instant = now }
                service S { f() -> Int  intent "x" }
                """));
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { x Int = now }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(e.getMessage().contains("Instant"), e.getMessage());
    }

    @Test
    void rejectsNowInContracts() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                service S {
                  f(a Instant) -> Bool  intent "x"  requires a < now
                }
                """));
        assertTrue(e.getMessage().contains("deterministic"), e.getMessage());
    }

    // ----- the chapter-4 surface: effects and values ---------------------------

    @Test
    void acceptsTheFullEffectsBudget() {
        assertDoesNotThrow(() -> check("""
                module m
                service S uses db, http, clock, mail {
                  f() -> Int  intent "x"
                }
                """));
    }

    @Test
    void rejectsUnknownAndDuplicateEffects() {
        CheckException unknown = assertThrows(CheckException.class, () -> check("""
                module m
                service S uses payments {
                  f() -> Int  intent "x"
                }
                """));
        assertTrue(unknown.getMessage().contains("payments"), unknown.getMessage());
        CheckException duplicate = assertThrows(CheckException.class, () -> check("""
                module m
                service S uses db, db {
                  f() -> Int  intent "x"
                }
                """));
        assertTrue(duplicate.getMessage().contains("duplicate"), duplicate.getMessage());
    }

    private static final String ROLES = """
            module m
            entity Role {
              name Text @id
              values Member, Admin
            }
            entity User {
              id   Int  @id
              role Role = Role.Member
            }
            service Users {
              promote(u User) -> User
                intent   "Return a copy with the admin role."
                ensures  result.role == Role.Admin
            %s
            }
            """;

    @Test
    void acceptsValuesEntitiesAndMemberReferences() {
        assertDoesNotThrow(() -> check(ROLES.formatted("")));
    }

    @Test
    void rejectsUnknownValueMember() {
        CheckException e = assertThrows(CheckException.class, () -> check(ROLES.formatted("""
              demote(u User) -> User
                intent  "x"
                ensures result.role == Role.Bogus
            """)));
        assertTrue(e.getMessage().contains("Bogus"), e.getMessage());
    }

    @Test
    void rejectsConstructingAClosedValueSet() {
        CheckException e = assertThrows(CheckException.class, () -> check(ROLES.formatted("""
              f(r Role) -> Bool
                intent  "x"
                example f(Role("Member")) -> true
            """)));
        assertTrue(e.getMessage().contains("closed"), e.getMessage());
    }

    @Test
    void valuesEntitiesNeedASingleTextIdField() {
        CheckException shape = assertThrows(CheckException.class, () -> check("""
                module m
                entity Role { name Text @id  rank Int  values Member }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(shape.getMessage().contains("values"), shape.getMessage());
        CheckException base = assertThrows(CheckException.class, () -> check("""
                module m
                entity Role { code Int @id  values Member }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(base.getMessage().contains("Text"), base.getMessage());
    }

    @Test
    void constructorsMayOmitTrailingDefaultedFields() {
        assertDoesNotThrow(() -> check("""
                module m
                entity Note { id Int @id  text Text  createdAt Instant = now  active Bool = true }
                service S {
                  f(n Note) -> Int
                    intent  "x"
                    example f(Note(1, "hello")) -> 1
                }
                """));
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                entity Note { id Int @id  text Text  createdAt Instant = now }
                service S {
                  f(n Note) -> Int
                    intent  "x"
                    example f(Note(1)) -> 1
                }
                """));
        assertTrue(e.getMessage().contains("field"), e.getMessage());
    }

    @Test
    void persistedModulesAcceptTheSupportedFieldShapes() {
        assertDoesNotThrow(() -> check("""
                module shop
                entity Status { name Text @id  values Open, Closed }
                entity User { id Int @id  email Email @unique  pin Secret<Text> }
                entity LineItem { product User  quantity Int(1..) }
                entity Order {
                  id       Int @id
                  customer User
                  approved Maybe<User>
                  status   Status
                  items    [LineItem]
                  tags     [Text]
                  placed   Instant
                  total    Money
                }
                service Orders uses db {
                  all() -> [Order]  intent "all"
                }
                """));
    }

    @Test
    void persistedModulesRejectUnsupportedFieldShapes() {
        CheckException map = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { id Int @id  extras Map<Text, Text> }
                service S uses db { f() -> Int  intent "x" }
                """));
        assertTrue(map.getMessage().contains("Map"), map.getMessage());
        CheckException refList = assertThrows(CheckException.class, () -> check("""
                module m
                entity User { id Int @id  name Text }
                entity Team { id Int @id  members [User] }
                service S uses db { f() -> Int  intent "x" }
                """));
        assertTrue(refList.getMessage().contains("members"), refList.getMessage());
        CheckException nested = assertThrows(CheckException.class, () -> check("""
                module m
                entity Inner { note Text }
                entity Part  { inner Inner  n Int }
                entity Whole { id Int @id  parts [Part] }
                service S uses db { f() -> Int  intent "x" }
                """));
        assertTrue(nested.getMessage().contains("Part"), nested.getMessage());
    }

    @Test
    void unpersistedModulesKeepTheirFreedom() {
        assertDoesNotThrow(() -> check("""
                module m
                entity E { id Int @id  extras Map<Text, Text> }
                service S { f() -> Int  intent "x" }
                """));
    }

    @Test
    void memberDefaultsMustMatchTheFieldType() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                entity Role   { name Text @id  values Member }
                entity Status { name Text @id  values Open }
                entity User   { role Role = Status.Open }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(e.getMessage().contains("Role"), e.getMessage());
    }

    @Test
    void nowRemainsAValidParameterName() {
        assertDoesNotThrow(() -> check("""
                module m
                service S {
                  f(now Int) -> Int  intent "x"  requires now > 0
                }
                """));
    }

    @Test
    void rejectsEntityShadowingABuiltinType() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                entity Money { id Int }
                service S { f() -> Int  intent "x" }
                """));
        assertTrue(e.getMessage().contains("Money"), e.getMessage());
    }

    @Test
    void validatesTypeDeclarations() {
        assertTrue(assertThrows(CheckException.class, () -> check("""
                module m
                type X = Int(0..100)
                type X = Int(1..)
                service S { f() -> Int  intent "x" }
                """)).getMessage().contains("duplicate"));
        assertTrue(assertThrows(CheckException.class, () -> check("""
                module m
                type X = Widget(0..100)
                service S { f() -> Int  intent "x" }
                """)).getMessage().contains("Widget"));
        assertTrue(assertThrows(CheckException.class, () -> check("""
                module m
                type X = Text matching /[unclosed/
                service S { f() -> Int  intent "x" }
                """)).getMessage().contains("regular expression"));
        assertTrue(assertThrows(CheckException.class, () -> check("""
                module m
                type X = Money(0..100)
                service S { f() -> Int  intent "x" }
                """)).getMessage().contains("Money"));
        assertTrue(assertThrows(CheckException.class, () -> check("""
                module m
                type X = Int matching /a/
                service S { f() -> Int  intent "x" }
                """)).getMessage().contains("matching"));
    }

    @Test
    void validatesWherePredicates() {
        assertDoesNotThrow(() -> check("""
                module m
                type PositiveMoney = Money where amount > 0
                service S { f(p PositiveMoney) -> Money  intent "x" }
                """));
        assertThrows(CheckException.class, () -> check("""
                module m
                type Bad = Money where bogus > 0
                service S { f() -> Int  intent "x" }
                """));
    }

    @Test
    void acceptsGenericListAsViewQuerySource() {
        assertDoesNotThrow(() -> check("""
                module m
                entity Order { id Int }
                service Orders { all() -> List<Order>  intent "all" }
                view V {
                  shows Orders.all() as a table of (id)
                }
                """));
    }

    @Test
    void minRequiresAnIntBase() {
        assertDoesNotThrow(() -> check("""
                module m
                entity E { stock Int(0..100) @min(0) }
                service S { f() -> Int  intent "x" }
                """));
        assertThrows(CheckException.class, () -> check("""
                module m
                entity E { balance Money @min(0) }
                service S { f() -> Int  intent "x" }
                """));
    }
}
