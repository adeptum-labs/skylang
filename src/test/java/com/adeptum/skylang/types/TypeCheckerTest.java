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
    void aValuesConstantDefaultMayReferenceALaterEntity() {
        assertDoesNotThrow(() -> check("""
                module shop
                entity User { id Int @id  role Role = Role.Member }
                entity Role { name Text @id  values Member, Staff, Admin }
                """));
    }

    @Test
    void rejectsADefaultNamingAMissingValueConstant() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module shop
                entity User { id Int @id  role Role = Role.Owner }
                entity Role { name Text @id  values Member, Staff, Admin }
                """));
        assertTrue(e.getMessage().contains("no value 'Owner'"), e.getMessage());
    }

    @Test
    void currencyAndPercentageAreBuiltinRefinements() {
        assertDoesNotThrow(() -> check("""
                module shop
                entity Country { code Text @id  name Text(1..80)  currency Currency  vatRate Percentage }
                """));
    }

    @Test
    void aPercentageDefaultOutsideTheRangeIsRejected() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module shop
                entity Country { code Text @id  vatRate Percentage = 250 }
                """));
        assertTrue(e.getMessage().contains("Percentage"), e.getMessage());
        assertDoesNotThrow(() -> check("""
                module shop
                entity Country { code Text @id  vatRate Percentage = 25 }
                """));
    }

    @Test
    void maybeReturnsAcceptNothingAndInnerValues() {
        assertDoesNotThrow(() -> check("""
                module shop
                entity Review { id Int @id  rating Int }
                service Reviews uses db {
                  averageRating(id Int) -> Maybe<Int>
                    intent  "The average star rating, or nothing if unreviewed."
                    example averageRating(1) -> 3
                    example averageRating(2) -> nothing
                }
                """));
    }

    @Test
    void nothingNeedsAMaybeReturn() {
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  f(x Int) -> Int
                    example f(1) -> nothing
                """)));
        assertTrue(e.getMessage().contains("only a Maybe can be absent"), e.getMessage());
    }

    @Test
    void whoseResultsTypeAgainstTheReturnedEntity() {
        assertDoesNotThrow(() -> check("""
                module shop
                entity OrderStatus { name Text @id  values Draft, Placed }
                entity Order { id Int @id  status OrderStatus = OrderStatus.Draft  placedAt Maybe<Instant> }
                service Orders uses db, clock {
                  place(id Int) -> Order
                    intent  "Place the order."
                    example place(1) -> an order whose status is Placed and whose placedAt is set
                }
                """));
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module shop
                entity Order { id Int @id  total Int }
                service Orders uses db {
                  place(id Int) -> Order
                    intent  "Place."
                    example place(1) -> an order whose total is set
                }
                """));
        assertTrue(e.getMessage().contains("'is set' needs a Maybe field"), e.getMessage());
    }

    @Test
    void everyClausesQuantifyOverListResults() {
        assertDoesNotThrow(() -> check("""
                module shop
                entity Category { name Text @id }
                entity Product { id Int @id  name Text  category Maybe<Category> }
                service Catalog uses db {
                  inCategory(category Category) -> [Product]
                    intent  "Every product in the given category."
                    ensures every product in result has category == category
                }
                """));
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module shop
                entity Product { id Int @id  name Text }
                service Catalog uses db {
                  all() -> [Product]
                    intent "x"
                    ensures every product in result has price == 1
                }
                """));
        assertTrue(e.getMessage().contains("has no field 'price'"), e.getMessage());
    }

    @Test
    void statusPhraseAndProseRaisesConditionsCheck() {
        assertDoesNotThrow(() -> check("""
                module shop
                entity OrderStatus { name Text @id  values Draft, Placed, Shipped, Cancelled }
                entity Order { id Int @id  status OrderStatus = OrderStatus.Draft }
                entity AlreadyShipped { }
                entity PaymentFailed { }
                service Orders uses db {
                  cancel(id Int) -> Order
                    intent  "Cancel a placed order."
                    raises  AlreadyShipped when the order's status is Shipped
                    raises  PaymentFailed when the provider declines the charge
                }
                """));
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module shop
                entity OrderStatus { name Text @id  values Draft, Placed }
                entity Order { id Int @id  status OrderStatus = OrderStatus.Draft }
                entity Nope { }
                service Orders uses db {
                  cancel(id Int) -> Order
                    intent  "x"
                    raises  Nope when the order's status is Vanished
                }
                """));
        assertTrue(e.getMessage().contains("no value 'Vanished'"), e.getMessage());
    }

    @Test
    void bcryptHashEnsuresAppliesToSecretsAndText() {
        assertDoesNotThrow(() -> check("""
                module shop
                entity User { id Int @id  email Email @unique  password Secret<Bytes> }
                service AccountService uses db {
                  register(email Email, password Text(12..)) -> User
                    intent  "Create a user, storing only a hash of the password."
                    ensures result.email == email
                            result.password is a bcrypt hash
                }
                """));
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  f(x Int) -> Int
                    intent "x"
                    ensures x is a bcrypt hash
                """)));
        assertTrue(e.getMessage().contains("'is a bcrypt hash' applies to"), e.getMessage());
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
    void anUnknownFieldSuggestsTheNearestName() {
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  restock(p Product, units Int) -> Product
                    intent  "Increase stock."
                    ensures result.stok == p.stock + units
                """)));
        assertTrue(e.getMessage().contains("has no field 'stok'"), e.getMessage());
        assertTrue(e.getMessage().contains("-> did you mean 'stock'?"), e.getMessage());
    }

    @Test
    void anUnknownExampleFieldSuggestsTheNearestName() {
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  restock(p Product, units Int) -> Product
                    intent  "Increase stock."
                    example restock(Product(1, "Notebook", 5), 3) -> a Product with stocks 8
                """)));
        assertTrue(e.getMessage().contains("unknown field 'Product.stocks'"), e.getMessage());
        assertTrue(e.getMessage().contains("-> did you mean 'stock'?"), e.getMessage());
    }

    @Test
    void aFieldNothingLikeAnyOtherGetsNoSuggestion() {
        CheckException e = assertThrows(CheckException.class, () -> check(service("""
                  restock(p Product, units Int) -> Product
                    intent  "Increase stock."
                    ensures result.zzzzzzz == 1
                """)));
        assertTrue(e.getMessage().contains("has no field 'zzzzzzz'"), e.getMessage());
        assertTrue(!e.getMessage().contains("did you mean"), e.getMessage());
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
        assertEquals(3, module.types().size(), "the example should declare Iban, PositiveMoney and Password");
        assertEquals(2, module.policies().size(), "the example should carry the two book policies");
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
                type Ratio = Int(0..100)
                service S {
                  f(p Ratio) -> Int
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
                type Ratio    = Int(0..100)
                type Quantity = Int(1..)
                service S {
                  f(p Ratio, q Quantity) -> Bool
                    intent   "x"
                    requires p == q
                }
                """));
        assertTrue(e.getMessage().contains("Ratio"), e.getMessage());
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
                  tip      Maybe<Money>
                  receipt  Maybe<Bytes>
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
        CheckException maybeList = assertThrows(CheckException.class, () -> check("""
                module m
                entity E { id Int @id  scores Maybe<[Int]> }
                service S uses db { f() -> Int  intent "x" }
                """));
        assertTrue(maybeList.getMessage().contains("not persistable yet"), maybeList.getMessage());
    }

    @Test
    void unpersistedModulesKeepTheirFreedom() {
        assertDoesNotThrow(() -> check("""
                module m
                entity E { id Int @id  extras Map<Text, Text> }
                service S { f() -> Int  intent "x" }
                """));
    }

    // ----- the chapter-5 surface: raises, old, aggregates, helper calls --------

    private static final String CATALOG = """
            module shop
            entity Product { id Int @id  name Text  stock Int @min(0) }
            entity User { id Int @id  email Email @unique }
            entity NotFound { }
            entity BadInput { }
            entity DuplicateEmail { }
            service Catalog uses db {
            %s
            }
            """;

    @Test
    void acceptsTheChapterFiveContracts() {
        assertDoesNotThrow(() -> check(CATALOG.formatted("""
              restock(id Int, units Int) -> Product
                intent   "Increase stock."
                requires units > 0 and not (units > 10000)
                ensures  result.stock == old(result.stock) + units
                raises   NotFound when no product has that id
                raises   BadInput when units <= 0
              register(email Email) -> User
                intent "Create the user."
                raises DuplicateEmail when email already registered
              totalStock() -> Int
                intent  "The total stock."
                ensures result == sum of (p.stock for p in all products)
              emptyCount(products [Product]) -> Int
                intent  "How many are out of stock."
                ensures result == count of (p for p in products where p.stock == 0)
            """)));
    }

    @Test
    void raisesNamesMustBeDeclaredEntities() {
        CheckException e = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f(id Int) -> Product
                intent "x"
                raises Mystery when id <= 0
            """)));
        assertTrue(e.getMessage().contains("Mystery"), e.getMessage());
    }

    @Test
    void errorEntitiesCannotBeUsedAsData() {
        CheckException e = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f(id Int) -> NotFound
                intent "x"
                raises NotFound when id <= 0
            """)));
        assertTrue(e.getMessage().contains("error"), e.getMessage());
    }

    @Test
    void unresolvablePhrasesAreRejectedWithAHint() {
        CheckException entity = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f(id Int) -> Product
                intent "x"
                raises NotFound when no widget has that id
            """)));
        assertTrue(entity.getMessage().contains("widget"), entity.getMessage());
        CheckException unique = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f(name Text) -> Product
                intent "x"
                raises BadInput when name already registered
            """)));
        assertTrue(unique.getMessage().contains("@unique"), unique.getMessage());
    }

    @Test
    void existencePhrasesNeedTheDbEffect() {
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                entity Product { id Int @id }
                entity NotFound { }
                service S {
                  f(id Int) -> Product
                    intent "x"
                    raises NotFound when no product has that id
                }
                """));
        assertTrue(e.getMessage().contains("db"), e.getMessage());
    }

    @Test
    void oldIsOnlyForEnsures() {
        CheckException e = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f(units Int) -> Product
                intent   "x"
                requires old(units) > 0
            """)));
        assertTrue(e.getMessage().contains("ensures"), e.getMessage());
    }

    @Test
    void oldOverResultNeedsDbAndAnIdParameter() {
        assertDoesNotThrow(() -> check(CATALOG.formatted("""
              touch(p Product) -> Product
                intent  "x"
                ensures result.stock == old(p.stock)
            """)));
        CheckException e = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f(units Int) -> Product
                intent  "x"
                ensures result.stock == old(result.stock) + units
            """)));
        assertTrue(e.getMessage().contains("id"), e.getMessage());
    }

    @Test
    void aggregatesTypeTheirComprehension() {
        CheckException sum = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f(products [Product]) -> Int
                intent  "x"
                ensures result == sum of (p.name for p in products)
            """)));
        assertTrue(sum.getMessage().contains("sum"), sum.getMessage());
        CheckException source = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f(p Product) -> Int
                intent  "x"
                ensures result == sum of (x.stock for x in p)
            """)));
        assertTrue(source.getMessage().contains("collection"), source.getMessage());
        CheckException all = assertThrows(CheckException.class, () -> check(CATALOG.formatted("""
              f() -> Int
                intent  "x"
                ensures result == sum of (w.stock for w in all widgets)
            """)));
        assertTrue(all.getMessage().contains("widgets"), all.getMessage());
    }

    @Test
    void contractsMayCallEffectFreeHelpers() {
        assertDoesNotThrow(() -> check("""
                module m
                service Math {
                  double(x Int) -> Int
                    intent  "x times two."
                    example double(2) -> 4
                }
                service S {
                  quadruple(x Int) -> Int
                    intent  "x times four."
                    ensures result == double(double(x))
                }
                """));
        CheckException e = assertThrows(CheckException.class, () -> check("""
                module m
                entity Product { id Int @id  stock Int }
                service Store uses db {
                  lookup(id Int) -> Product
                    intent "x"
                }
                service S {
                  f(id Int) -> Int
                    intent  "x"
                    ensures result == lookup(id).stock
                }
                """));
        assertTrue(e.getMessage().contains("effect"), e.getMessage());
    }

    // ----- the chapter-6 surface: spec blocks and seeded examples --------------

    private static final String BANKING = """
            module m
            entity Account { id Int @id  balance Money }
            entity Receipt { id Int @id  amount Money }
            entity InsufficientFunds { }
            entity BadInput { }
            entity NotFound { }
            entity Product { id Int @id  stock Int @min(0) }
            service Bank uses db {
            %s
            }
            """;

    @Test
    void acceptsSpecBlocksAndSeededExamples() {
        assertDoesNotThrow(() -> check(BANKING.formatted("""
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
              restock(id Int, units Int) -> Product
                example restock(7, 3) on a Product with stock 5 -> stock 8
                example restock(7, 0) -> raises BadInput
                example restock(999, 1) -> raises NotFound
            """)));
    }

    @Test
    void specsCountAsDrivers() {
        assertDoesNotThrow(() -> check(BANKING.formatted("""
              zero(a Account) -> Receipt
                spec "zeroes" {
                  when transfer2(a)
                  then result.amount == 0eur
                }
            """.replace("transfer2", "zero"))));
    }

    @Test
    void specWhenMustCallItsOwnMethod() {
        CheckException e = assertThrows(CheckException.class, () -> check(BANKING.formatted("""
              f(a Account) -> Receipt
                spec "x" {
                  when g(a)
                  then result.amount == 0eur
                }
            """)));
        assertTrue(e.getMessage().contains("f"), e.getMessage());
    }

    @Test
    void specGivenMustPinStateWithEqualities() {
        CheckException e = assertThrows(CheckException.class, () -> check(BANKING.formatted("""
              f(a Account) -> Receipt
                spec "x" {
                  given a.balance > 10eur
                  when  f(a)
                  then  result.amount == 0eur
                }
            """)));
        assertTrue(e.getMessage().contains("given"), e.getMessage());
    }

    @Test
    void specResultIsUnavailableAfterRaises() {
        CheckException e = assertThrows(CheckException.class, () -> check(BANKING.formatted("""
              f(a Account) -> Receipt
                spec "x" {
                  given a.balance == 10eur
                  when  f(a)
                  then  raises InsufficientFunds
                        result.amount == 0eur
                }
            """)));
        assertTrue(e.getMessage().contains("result"), e.getMessage());
    }

    @Test
    void seededExamplesNeedDbAndAnIdSource() {
        CheckException db = assertThrows(CheckException.class, () -> check("""
                module m
                entity Product { id Int @id  stock Int }
                service S {
                  restock(id Int, units Int) -> Product
                    example restock(7, 3) on a Product with stock 5 -> stock 8
                }
                """));
        assertTrue(db.getMessage().contains("db"), db.getMessage());
        CheckException id = assertThrows(CheckException.class, () -> check(BANKING.formatted("""
              touch(units Int) -> Product
                example touch(3) on a Product with stock 5 -> stock 8
            """)));
        assertTrue(id.getMessage().contains("id"), id.getMessage());
    }

    @Test
    void extendedResultsAreValidated() {
        CheckException unknown = assertThrows(CheckException.class, () -> check(BANKING.formatted("""
              f(id Int) -> Product
                example f(1) -> raises Mystery
            """)));
        assertTrue(unknown.getMessage().contains("Mystery"), unknown.getMessage());
        CheckException field = assertThrows(CheckException.class, () -> check(BANKING.formatted("""
              f(id Int) -> Product
                example f(7) on a Product with stock 5 -> bogus 8
            """)));
        assertTrue(field.getMessage().contains("bogus"), field.getMessage());
    }

    @Test
    void specWitnessesMustBeDerivableOrPinned() {
        String source = """
                module m
                type Iban = Text matching /^[A-Z]{2}[0-9]{22}$/
                entity Account { id Int @id  iban Iban @unique  balance Money }
                entity Overdrawn { }
                service Bank uses db {
                  drain(a Account) -> Account
                    spec "drains" {
                      %s
                      when  drain(a)
                      then  a.balance == 0eur
                    }
                }
                """;
        CheckException e = assertThrows(CheckException.class,
                () -> check(source.formatted("given a.balance == 10eur")));
        assertTrue(e.getMessage().contains("iban"), e.getMessage());
        assertDoesNotThrow(() -> check(source.formatted(
                "given a.balance == 10eur and a.iban == \"SE3550000000054910000003\"")));
    }

    // ----- the chapter-7 surface: policies --------------------------------------

    private static final String POLICIES = """
            module m
            type Password = Text(12..128)
            entity WeakPassword { }
            %s
            service S { f(x Int) -> Int  intent "x" }
            """;

    @Test
    void acceptsTheBookPolicies() {
        assertDoesNotThrow(() -> check(POLICIES.formatted("""
                policy StrongPasswords {
                  whenever a Password is constructed
                  require  length >= 12 and contains a symbol
                  else     raise WeakPassword
                }
                policy NoSecretsInLogs {
                  whenever a Secret is passed to a logger
                  forbid
                }
                """)));
    }

    @Test
    void policyWheneverTypesMustResolve() {
        CheckException e = assertThrows(CheckException.class, () -> check(POLICIES.formatted("""
                policy P {
                  whenever a Widget is constructed
                  require length >= 1
                }
                """)));
        assertTrue(e.getMessage().contains("Widget"), e.getMessage());
    }

    @Test
    void policyRequirePredicatesTypeAgainstTheValue() {
        CheckException name = assertThrows(CheckException.class, () -> check(POLICIES.formatted("""
                policy P {
                  whenever a Password is constructed
                  require balance >= 12
                }
                """)));
        assertTrue(name.getMessage().contains("balance"), name.getMessage());
        CheckException contains = assertThrows(CheckException.class, () -> check("""
                module m
                type Pin = Int(4..8)
                policy P {
                  whenever a Pin is constructed
                  require contains a symbol
                }
                service S { f(x Int) -> Int  intent "x" }
                """));
        assertTrue(contains.getMessage().contains("contains"), contains.getMessage());
    }

    @Test
    void policyShapesMatchTheirWhenever() {
        CheckException e = assertThrows(CheckException.class, () -> check(POLICIES.formatted("""
                policy P {
                  whenever a Password is constructed
                  forbid
                }
                """)));
        assertTrue(e.getMessage().contains("forbid"), e.getMessage());
    }

    @Test
    void policyErrorsAreErrorEntities() {
        CheckException e = assertThrows(CheckException.class, () -> check(POLICIES.formatted("""
                policy StrongPasswords {
                  whenever a Password is constructed
                  require length >= 12
                  else    raise WeakPassword
                }
                """).replace("f(x Int) -> Int", "f(x WeakPassword) -> Int")));
        assertTrue(e.getMessage().contains("error"), e.getMessage());
    }

    // ----- the chapter-9 surface: list length and fixture arguments ------------

    @Test
    void collectionsExposeTheirLengthToContracts() {
        assertDoesNotThrow(() -> check("""
                module m
                service S {
                  keep(input [Text]) -> [Text]
                    intent  "Same size out as in."
                    ensures result.length == input.length
                }
                """));
    }

    private static final String WALLETS = """
            module m
            entity Wallet { id Int @id  owner Email @unique  balance Money }
            entity Purse { id Int @id  cash Money  reserve Money }
            service Bank uses db {
            %s
            }
            """;

    @Test
    void fixtureArgumentsConstructWitnesses() {
        assertDoesNotThrow(() -> check(WALLETS.formatted("""
              withdraw(w Wallet, amount Money) -> Wallet
                intent  "Take the amount out."
                ensures result.balance == old(w.balance) - amount
                example withdraw(wallet_with(100eur), 30eur) -> balance 70eur
            """)));
    }

    @Test
    void fixtureFieldsMustResolveUniquely() {
        CheckException ambiguous = assertThrows(CheckException.class, () -> check(WALLETS.formatted("""
              drain(p Purse) -> Purse
                intent  "x"
                example drain(purse_with(5eur)) -> cash 0eur
            """)));
        assertTrue(ambiguous.getMessage().contains("cash") && ambiguous.getMessage().contains("reserve"),
                ambiguous.getMessage());
        CheckException unknown = assertThrows(CheckException.class, () -> check(WALLETS.formatted("""
              f(w Wallet) -> Wallet
                intent  "x"
                example f(widget_with(5eur)) -> balance 0eur
            """)));
        assertTrue(unknown.getMessage().contains("widget"), unknown.getMessage());
        CheckException noField = assertThrows(CheckException.class, () -> check(WALLETS.formatted("""
              f(w Wallet) -> Wallet
                intent  "x"
                example f(wallet_with(true)) -> balance 0eur
            """)));
        assertTrue(noField.getMessage().contains("Bool"), noField.getMessage());
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
