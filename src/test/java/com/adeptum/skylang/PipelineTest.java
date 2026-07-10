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

package com.adeptum.skylang;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.StubLlm;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the synth -> stage -> freeze loop offline: a stub model supplies a body and a stub
 * verifier accepts it, so the test needs no network and no Maven. The second build must reuse
 * the frozen body and never call the model again.
 */
class PipelineTest {

    private static final String SHOP = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog {
              restock(p Product, units Int) -> Product
                intent  "Increase stock."
                requires units > 0
                ensures  result.stock == p.stock + units
                example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
            }
            """;

    private static final String BODY = "return new Product(p.id(), p.name(), p.stock() + units);";

    private static final Verifier ALWAYS_PASS = dir -> VerificationResult.pass();

    private static final String SHOP_VIEW = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog {
              all() -> [Product]  intent "Every product."
              restock(id Int, units Int) -> Product
                intent "Increase stock."
            }
            view ProductList at "/products" {
              shows  Catalog.all() as a table of (name, stock)
              action "Restock" on row -> Catalog.restock(row.id, ask Int)
              expect table has columns (name, stock)
              expect action "Restock" is button
            }
            """;

    private static final String VIEW_REPLY = """
            ```xhtml
            <h:dataTable id="products" value="#{bean.rows}" var="row">
              <h:column id="c_name"><f:facet name="header"><h:outputText value="Name"/></f:facet><h:outputText value="#{row.name}"/></h:column>
              <h:column id="c_stock"><f:facet name="header"><h:outputText value="Stock"/></f:facet><h:outputText value="#{row.stock}"/></h:column>
              <h:column id="c_act"><h:commandButton id="restock" value="Restock" action="#{bean.restock(row.id)}"/></h:column>
            </h:dataTable>
            ```
            ```java
            public class ProductListBean {}
            ```
            """;

    private static final String VIEW_REPLY_BAD = """
            ```xhtml
            <h:dataTable id="products" value="#{bean.rows}" var="row">
              <h:column id="c_name"><h:outputText value="#{row.name}"/></h:column>
            </h:dataTable>
            ```
            ```java
            public class ProductListBean {}
            ```
            """;

    /** Routes UI-synthesis calls to a canned view reply and every other call to a throwaway body. */
    private static StubLlm routingStub(String viewReply) {
        return new StubLlm((system, user) -> system.contains("UI-synthesis") ? viewReply : "return null;");
    }

    private static Ast.Module checkedViewModule() {
        Ast.Module m = Parsing.parse(SHOP_VIEW, "shop.sky");
        new TypeChecker().check(m);
        return m;
    }

    private Ast.Module checkedModule() {
        Ast.Module m = Parsing.parse(SHOP, "shop.sky");
        new TypeChecker().check(m);
        return m;
    }

    @Test
    void firstBuildSynthesizesAndFreezes(@TempDir Path root) {
        Ast.Module module = checkedModule();
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        StubLlm stub = new StubLlm(BODY);

        int code = new Pipeline(stub, ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(1, stub.calls(), "model should be called once for the single unfrozen method");
        assertTrue(Files.exists(lock), "sky.lock should be written");
        assertTrue(Files.exists(buildDir.resolve("src/main/java/shop/Catalog.java")));
        assertTrue(Files.exists(buildDir.resolve("src/main/java/shop/Product.java")));
        assertTrue(Files.exists(buildDir.resolve("src/test/java/shop/CatalogTest.java")));
    }

    @Test
    void secondBuildReusesFrozenBodyWithoutCallingModel(@TempDir Path root) {
        Ast.Module module = checkedModule();
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(new StubLlm(BODY), ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        // Rebuild with a fresh stub; the frozen body must be reused, so the stub is never called.
        StubLlm second = new StubLlm(BODY);
        int code = new Pipeline(second, ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(0, second.calls(), "rebuild with an unchanged spec must not call the model");
    }

    @Test
    void changingIntentRegenerates(@TempDir Path root) {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(new StubLlm(BODY), ALWAYS_PASS)
                .build(checkedModule(), lock, buildDir, quiet(), quiet());

        Ast.Module changed = Parsing.parse(SHOP.replace("Increase stock.", "Bump the stock up."), "shop.sky");
        new TypeChecker().check(changed);
        StubLlm after = new StubLlm(BODY);
        new Pipeline(after, ALWAYS_PASS).build(changed, lock, buildDir, quiet(), quiet());

        assertEquals(1, after.calls(), "changing the intent should re-synthesize that method");
    }

    @Test
    void firstBuildSynthesizesAndFreezesView(@TempDir Path root) throws Exception {
        Ast.Module module = checkedViewModule();
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        StubLlm stub = routingStub(VIEW_REPLY);

        int code = new Pipeline(stub, ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(3, stub.calls(), "two methods plus one view should each be synthesized once");
        String frozen = Files.readString(lock);
        assertTrue(frozen.contains("shop.ProductList"), "the view should be frozen in sky.lock");
        assertTrue(frozen.contains("h:dataTable"), "the frozen view should carry its markup");

        assertTrue(Files.exists(buildDir.resolve("src/main/webapp/ProductList.xhtml")), "the view page should be staged");
        assertTrue(Files.exists(buildDir.resolve("src/main/webapp/WEB-INF/web.xml")), "web.xml should be staged");
        assertTrue(Files.exists(buildDir.resolve("src/main/webapp/WEB-INF/beans.xml")), "beans.xml should be staged");
        assertTrue(Files.exists(buildDir.resolve("src/main/java/shop/ProductListBean.java")), "the backing bean should be staged");
        assertTrue(Files.readString(buildDir.resolve("src/main/java/shop/Catalog.java"))
                .contains("@jakarta.enterprise.context.ApplicationScoped"), "the service should be a CDI bean");
        assertTrue(Files.readString(buildDir.resolve("src/main/java/shop/Product.java")).contains("getName"),
                "web-profile entities need JavaBean getters so Faces EL can read them");
        assertTrue(Files.readString(buildDir.resolve("pom.xml")).contains("tomee-embedded"), "a web POM should be staged");
    }

    @Test
    void secondBuildReusesFrozenView(@TempDir Path root) {
        Ast.Module module = checkedViewModule();
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        StubLlm second = routingStub(VIEW_REPLY);
        int code = new Pipeline(second, ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(0, second.calls(), "an unchanged view and methods must not call the model");
    }

    @Test
    void buildWithOnlyAFreshViewStillRunsTheVerifier(@TempDir Path root) {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS).build(checkedViewModule(), lock, buildDir, quiet(), quiet());

        // Change only the view (its route): methods stay frozen, the view is re-synthesized.
        Ast.Module changed = Parsing.parse(SHOP_VIEW.replace("\"/products\"", "\"/inventory\""), "shop.sky");
        new TypeChecker().check(changed);
        var verified = new java.util.concurrent.atomic.AtomicBoolean();
        Verifier recording = dir -> {
            verified.set(true);
            return VerificationResult.pass();
        };

        int code = new Pipeline(routingStub(VIEW_REPLY), recording).build(changed, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertTrue(verified.get(), "a freshly synthesized view must go through the staged verification");
    }

    @Test
    void recheckVerifiesEvenWhenEverythingIsFrozen(@TempDir Path root) {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS).build(checkedViewModule(), lock, buildDir, quiet(), quiet());

        var verified = new java.util.concurrent.atomic.AtomicBoolean();
        Verifier recording = dir -> {
            verified.set(true);
            return VerificationResult.pass();
        };
        StubLlm stub = routingStub(VIEW_REPLY);

        int code = new Pipeline(stub, recording)
                .build(checkedViewModule(), lock, buildDir, quiet(), quiet(), true);

        assertEquals(0, code);
        assertEquals(0, stub.calls(), "a recheck must stay offline — no model call");
        assertTrue(verified.get(), "a recheck must run the staged verification despite the frozen lock");
    }

    @Test
    void stagedProjectCarriesTheVisualGate(@TempDir Path root) throws Exception {
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS)
                .build(checkedViewModule(), root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertTrue(Files.exists(buildDir.resolve("src/test/java/shop/VisualGate.java")),
                "the visual gate helper should be staged");
        assertTrue(Files.readString(buildDir.resolve("src/test/java/shop/ViewsRenderTest.java"))
                .contains("VisualGate.check(\"ProductList\", html)"), "each view should pass the visual gate");
        assertTrue(Files.readString(buildDir.resolve("pom.xml")).contains("flying-saucer-core"),
                "the pinned rasterizer should be a staged test dependency");
    }

    /** A verifier that behaves like the staged visual gate: it leaves a capture behind, then passes. */
    private static Verifier capturing(byte[] png) {
        return dir -> {
            try {
                Path captures = dir.resolve("target/sky-visual");
                Files.createDirectories(captures);
                Files.write(captures.resolve("ProductList.png"), png);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return VerificationResult.pass();
        };
    }

    @Test
    void verifiedBuildFreezesTheVisualCapture(@TempDir Path root) throws Exception {
        Path lock = root.resolve("sky.lock");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        int code = new Pipeline(routingStub(VIEW_REPLY), capturing(png))
                .build(checkedViewModule(), lock, root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code);
        assertTrue(Files.readString(lock).contains(java.util.Base64.getEncoder().encodeToString(png)),
                "the visual capture should be frozen into sky.lock");
    }

    @Test
    void frozenBaselineIsStagedForComparison(@TempDir Path root) throws Exception {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 9, 8, 7};

        new Pipeline(routingStub(VIEW_REPLY), capturing(png)).build(checkedViewModule(), lock, buildDir, quiet(), quiet());
        int code = new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS).build(checkedViewModule(), lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertArrayEquals(png, Files.readAllBytes(buildDir.resolve("src/test/resources/sky-visual/ProductList.png")),
                "the frozen baseline should be staged so the visual gate can compare against it");
    }

    @Test
    void reSynthesizedViewDropsItsStaleBaseline(@TempDir Path root) throws Exception {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        byte[] first = {(byte) 0x89, 'P', 'N', 'G', 1};
        byte[] second = {(byte) 0x89, 'P', 'N', 'G', 2};

        new Pipeline(routingStub(VIEW_REPLY), capturing(first)).build(checkedViewModule(), lock, buildDir, quiet(), quiet());

        // A view-spec change re-synthesizes the view: the old baseline no longer describes it.
        Ast.Module changed = Parsing.parse(SHOP_VIEW.replace("\"/products\"", "\"/inventory\""), "shop.sky");
        new TypeChecker().check(changed);
        Verifier gate = dir -> {
            assertFalse(Files.exists(dir.resolve("src/test/resources/sky-visual/ProductList.png")),
                    "a re-synthesized view must not be compared against its stale baseline");
            return capturing(second).verify(dir);
        };
        int code = new Pipeline(routingStub(VIEW_REPLY), gate).build(changed, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertTrue(Files.readString(lock).contains(java.util.Base64.getEncoder().encodeToString(second)),
                "the fresh capture should replace the stale baseline in sky.lock");
    }

    @Test
    void viewFailingItsExpectationsFailsTheBuild(@TempDir Path root) {
        Ast.Module module = checkedViewModule();
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        // The bad reply omits the stock column, so `expect ... columns (name, stock)` cannot hold.
        int code = new Pipeline(routingStub(VIEW_REPLY_BAD), ALWAYS_PASS)
                .build(module, lock, buildDir, quiet(), quiet());

        assertEquals(1, code);
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    // ----- the chapter-3 type surface -----------------------------------------

    private static final String BANK = """
            module bank
            type Sku = Text matching /^[A-Z0-9-]{4,16}$/
            entity Account {
              id      Int   @id
              owner   Email @unique
              name    Text(1..120)
              balance Money
              active  Bool = true
            }
            service Accounts {
              open(owner Email, name Text(1..120)) -> Account
                intent "Open an account with a zero balance."
              deposit(a Account, amount Money) -> Account
                intent  "Return a copy with the amount added."
                ensures result.balance == a.balance + amount
                example deposit(Account(1, "a@example.com", "Main", 0eur, true), 9.99eur) -> a Account with balance 9.99eur
            }
            """;

    private static Ast.Module checkedBankModule() {
        Ast.Module m = Parsing.parse(BANK, "bank.sky");
        new TypeChecker().check(m);
        return m;
    }

    @Test
    void stagedProjectCarriesTheTypeSystem(@TempDir Path root) throws Exception {
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(checkedBankModule(), root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String account = Files.readString(buildDir.resolve("src/main/java/bank/Account.java"));
        assertTrue(account.contains(
                        "public record Account(long id, String owner, String name, Money balance, boolean active)"),
                "the entity should lower field types through the full mapping");
        assertTrue(account.contains("name.length()"), "the Text range should be checked at construction");
        assertTrue(account.contains("matches"), "the Email shape should be checked at construction");
        assertTrue(account.contains("@unique"), "the advisory @unique constraint should be documented");
        assertTrue(account.contains("public Account(long id, String owner, String name, Money balance)"),
                "trailing defaulted fields should get an omitting constructor");

        assertTrue(Files.exists(buildDir.resolve("src/main/java/bank/Money.java")),
                "the Money support class should be staged when used");
        assertFalse(Files.exists(buildDir.resolve("src/main/java/bank/Secret.java")),
                "unused support classes must not be staged");
        assertFalse(Files.exists(buildDir.resolve("src/main/java/bank/Bytes.java")),
                "unused support classes must not be staged");

        String accounts = Files.readString(buildDir.resolve("src/main/java/bank/Accounts.java"));
        assertTrue(accounts.contains("matches") && accounts.contains("IllegalArgumentException"),
                "refined parameters should be guarded at the top of the staged method");

        String tests = Files.readString(buildDir.resolve("src/test/java/bank/AccountsTest.java"));
        assertTrue(tests.contains("Money.of(\"9.99\", \"EUR\")"), "money literals should lower exactly");
        assertTrue(tests.contains("plus(") && tests.contains("eq("),
                "contract operators should lower through the overloaded helpers");
    }

    private static final String PAY_VIEW = """
            module shop
            entity Order { id Int  total Money }
            service Orders {
              all() -> [Order]  intent "all"
              pay(id Int, amount Money, when Instant) -> Order  intent "pay"
            }
            view Pay at "/pay" {
              shows  Orders.all() as a table of (id)
              action "Pay" on row -> Orders.pay(row.id, ask Money, ask Instant)
            }
            """;

    @Test
    void promptedMoneyAndInstantInputsStageTheirConverters(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(PAY_VIEW, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String money = Files.readString(buildDir.resolve("src/main/java/shop/MoneyConverter.java"));
        assertTrue(money.contains("sky.money"), "the Money converter must register under sky.money");
        String instant = Files.readString(buildDir.resolve("src/main/java/shop/InstantConverter.java"));
        assertTrue(instant.contains("sky.instant"), "the Instant converter must register under sky.instant");
        assertTrue(Files.exists(buildDir.resolve("src/main/java/shop/Money.java")),
                "an ask Money input needs the Money support class");

        String interaction = Files.readString(buildDir.resolve("src/test/java/shop/ViewsInteractionTest.java"));
        assertTrue(interaction.contains("9.99 EUR"), "the interaction lane needs a valid Money sample");
        assertTrue(interaction.contains("2026-01-01T00:00:00Z"), "the interaction lane needs a valid Instant sample");
    }

    @Test
    void viewsWithoutConvertedInputsStageNoConverters(@TempDir Path root) {
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS)
                .build(checkedViewModule(), root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertFalse(Files.exists(buildDir.resolve("src/main/java/shop/MoneyConverter.java")),
                "a view prompting only for Int must not stage converters");
        assertFalse(Files.exists(buildDir.resolve("src/main/java/shop/InstantConverter.java")),
                "a view prompting only for Int must not stage converters");
    }

    @Test
    void secretFieldsStayOutOfTheWebSurface(@TempDir Path root) throws Exception {
        String source = SHOP_VIEW.replace("stock Int @min(0)", "stock Int @min(0)  password Secret<Text>");
        Ast.Module module = Parsing.parse(source, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String product = Files.readString(buildDir.resolve("src/main/java/shop/Product.java"));
        assertTrue(product.contains("getName"), "ordinary fields keep their Faces getters");
        assertFalse(product.contains("getPassword"), "Secret fields must not expose a Faces getter");
        assertTrue(Files.exists(buildDir.resolve("src/main/java/shop/Secret.java")),
                "the Secret support class should be staged when used");
    }
}
