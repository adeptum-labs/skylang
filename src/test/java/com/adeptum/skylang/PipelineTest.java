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
import java.util.List;

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
    void theDefaultRetryBudgetAllowsFiveCandidates(@TempDir Path root) {
        Ast.Module module = checkedModule();
        StubLlm stub = new StubLlm(BODY);
        var runs = new java.util.concurrent.atomic.AtomicInteger();
        Verifier passesOnFifth = dir -> runs.incrementAndGet() < 5
                ? VerificationResult.fail("[ERROR]   CatalogTest.restock_example_1:20 ensures: x")
                : VerificationResult.pass();

        int code = new Pipeline(stub, passesOnFifth)
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code, "the default budget should allow five candidates per method");
        assertEquals(5, stub.calls(), "four regenerations follow the first candidate");
    }

    private static final String RESTOCK_FAILURE = """
            [ERROR] Failures:\s
            [ERROR]   CatalogTest.restock_example_1:23 ensures: result.stock == p.stock + units [p=Product[id=1], units=3] ==> expected: <true> but was: <false>
            """;

    @Test
    void theTranscriptNamesTheViolatedClausePerCandidate(@TempDir Path root) {
        Ast.Module module = checkedModule();
        var runs = new java.util.concurrent.atomic.AtomicInteger();
        Verifier failsOnce = dir -> runs.incrementAndGet() == 1
                ? VerificationResult.fail(RESTOCK_FAILURE) : VerificationResult.pass();
        var out = new ByteArrayOutputStream();

        int code = new Pipeline(new StubLlm(BODY), failsOnce).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), new PrintStream(out), quiet());

        assertEquals(0, code);
        String transcript = out.toString();
        assertTrue(transcript.contains("Catalog.restock"), transcript);
        assertTrue(transcript.contains("▸ candidate 1: ensures: result.stock == p.stock + units  ✗ FAILED"),
                "the violated clause should be named on the failing candidate:\n" + transcript);
        assertTrue(transcript.contains("regenerating Catalog.restock"), transcript);
        assertTrue(transcript.contains("▸ candidate 2: all contracts ✓"),
                "the passing candidate should be announced:\n" + transcript);
    }

    @Test
    void onlyTheImplicatedMethodRegenerates(@TempDir Path root) {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  restock(p Product, units Int) -> Product
                    intent  "Increase stock."
                    requires units > 0
                  rename(p Product, name Text) -> Product
                    intent  "Rename the product."
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        StubLlm stub = new StubLlm(BODY);
        var runs = new java.util.concurrent.atomic.AtomicInteger();
        Verifier failsOnce = dir -> runs.incrementAndGet() == 1
                ? VerificationResult.fail(RESTOCK_FAILURE) : VerificationResult.pass();

        int code = new Pipeline(stub, failsOnce).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code);
        assertEquals(3, stub.calls(),
                "two first candidates plus one regeneration of the implicated method only");
    }

    @Test
    void synthesisExhaustionReportsTheViolatedClauses(@TempDir Path root) {
        Ast.Module module = checkedModule();
        Verifier alwaysFail = dir -> VerificationResult.fail(RESTOCK_FAILURE);
        var err = new ByteArrayOutputStream();

        int code = new Pipeline(new StubLlm(BODY), alwaysFail).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), quiet(), new PrintStream(err));

        assertEquals(2, code);
        String report = err.toString();
        assertTrue(report.contains("error [synthesis]: shop.Catalog.restock"), report);
        assertTrue(report.contains("could not satisfy all clauses after 5 attempts."), report);
        assertTrue(report.contains("ensures: result.stock == p.stock + units"), report);
    }

    @Test
    void contradictoryExamplesAreCalledOut(@TempDir Path root) {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Order { id Int  status Text }
                entity EmptyOrder { }
                service Orders {
                  place(id Int) -> Order
                    intent  "Place the order."
                    example place(1) -> raises EmptyOrder
                    example place(1) -> status "Placed"
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        Verifier alwaysFail = dir -> VerificationResult.fail("""
                [ERROR]   OrdersTest.place_example_1:10 example: raises EmptyOrder ==> Expected shop.EmptyOrder to be thrown, but nothing was thrown.
                """);
        var err = new ByteArrayOutputStream();

        int code = new Pipeline(new StubLlm("return db.save(new Order(id, \"Placed\"));"), alwaysFail)
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        quiet(), new PrintStream(err));

        assertEquals(2, code);
        String report = err.toString();
        assertTrue(report.contains("unsatisfiable together:"), report);
        assertTrue(report.contains("example place(1) -> raises EmptyOrder"), report);
        assertTrue(report.contains("example place(1) -> status \"Placed\""), report);
        assertTrue(report.contains("-> these two examples contradict each other."), report);
    }

    /** A second target with its own id: staging and prompts borrowed from the reference profile. */
    private static com.adeptum.skylang.backend.Profile fakeTarget() {
        var stager = new com.adeptum.skylang.backend.ProjectStager();
        var prompts = new com.adeptum.skylang.synth.PromptBuilder();
        return new com.adeptum.skylang.backend.Profile() {
            public String id() { return "fake-target"; }
            public String version() { return "0.0.1"; }
            public String nativeKeyword() { return "java"; }
            public String tag() { return "fk"; }
            public void validate(Ast.Module module) { }
            public boolean supportsViews() { return false; }
            public void stage(Ast.Module module, java.util.Map<String, String> bodies,
                              List<com.adeptum.skylang.deps.Resolved> deps, Path dir) {
                stager.stage(module, bodies, deps, dir);
            }
            public Verifier verifier() { return ALWAYS_PASS; }
            public boolean emit(String projectName, Path dir, java.io.PrintStream out) { return true; }
            public String systemPrompt() { return prompts.system(); }
            public String userPrompt(Ast.Module module, Ast.Service service, Ast.Method method,
                                     List<com.adeptum.skylang.deps.Resolved> deps) {
                return prompts.user(module, service, method, deps);
            }
            public String extractBody(String reply) { return prompts.extractBody(reply); }
        };
    }

    @Test
    void retargetingRegeneratesEveryBody(@TempDir Path root) {
        Ast.Module module = checkedModule();
        Path lock = root.resolve("sky.lock");
        new Pipeline(new StubLlm(BODY), ALWAYS_PASS)
                .build(module, lock, root.resolve("build/jvm-jakarta"), quiet(), quiet());

        StubLlm retargeted = new StubLlm(BODY);
        var out = new ByteArrayOutputStream();
        int code = new Pipeline(retargeted, ALWAYS_PASS, 4, fakeTarget())
                .build(module, lock, root.resolve("build/fake-target"), new PrintStream(out), quiet());

        assertEquals(0, code);
        assertEquals(1, retargeted.calls(),
                "switching the profile invalidates the freeze; the unchanged spec regenerates");
        String transcript = out.toString();
        assertTrue(transcript.contains("profile fake-target   (changed from jvm-jakarta; regenerating all bodies)"),
                transcript);
        assertTrue(transcript.contains("▸ synthesized (fk) ▸ verified ▸ frozen @"),
                "the transcript should carry the new target's tag:\n" + transcript);

        StubLlm settled = new StubLlm(BODY);
        assertEquals(0, new Pipeline(settled, ALWAYS_PASS, 4, fakeTarget())
                .build(module, lock, root.resolve("build/fake-target"), quiet(), quiet()));
        assertEquals(0, settled.calls(), "the retargeted lock freezes like any other");
    }

    private static com.adeptum.skylang.deps.Budget bookDeps() {
        var registry = com.adeptum.skylang.deps.Registry
                .forProfile("jvm-jakarta", java.util.Optional.empty());
        return new com.adeptum.skylang.deps.Budget(
                registry.resolve(List.of(new com.adeptum.skylang.config.Manifest.Require("bcrypt", "^4.0"))),
                registry.prefixIndex());
    }

    @Test
    void declaredDependenciesReachThePomAndTheLock(@TempDir Path root) throws IOException {
        Ast.Module module = checkedModule();
        Path lock = root.resolve("sky.lock");
        int code = new Pipeline(new StubLlm(BODY), ALWAYS_PASS, 4,
                com.adeptum.skylang.backend.JvmProfile.INSTANCE, bookDeps())
                .build(module, lock, root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code);
        String pom = Files.readString(root.resolve("build/jvm-jakarta/pom.xml"));
        assertTrue(pom.contains("<artifactId>jbcrypt</artifactId>"),
                "resolved coordinates land in the staged pom:\n" + pom);
        String frozen = Files.readString(lock);
        assertTrue(frozen.contains("org.mindrot:jbcrypt:0.4"),
                "the lock pins the resolved coordinate:\n" + frozen);
        assertTrue(frozen.contains("^4.0"), "the lock records what was requested:\n" + frozen);
    }

    @Test
    void changingTheRequiresBlockRegeneratesBodies(@TempDir Path root) {
        Ast.Module module = checkedModule();
        Path lock = root.resolve("sky.lock");
        new Pipeline(new StubLlm(BODY), ALWAYS_PASS)
                .build(module, lock, root.resolve("build/jvm-jakarta"), quiet(), quiet());

        StubLlm after = new StubLlm(BODY);
        int code = new Pipeline(after, ALWAYS_PASS, 4,
                com.adeptum.skylang.backend.JvmProfile.INSTANCE, bookDeps())
                .build(module, lock, root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code);
        assertEquals(1, after.calls(),
                "a changed requires block changes what a body may draw on — it regenerates");
    }

    @Test
    void anUndeclaredDependencyIsRefusedByName(@TempDir Path root) {
        Ast.Module module = checkedModule();
        var registry = com.adeptum.skylang.deps.Registry
                .forProfile("jvm-jakarta", java.util.Optional.empty());
        var noneDeclared = new com.adeptum.skylang.deps.Budget(List.of(), registry.prefixIndex());
        var err = new ByteArrayOutputStream();
        StubLlm reaches = new StubLlm(
                "return new Product(p.id(), org.mindrot.jbcrypt.BCrypt.hashpw(p.name(), \"s\"), p.stock() + units);");

        int code = new Pipeline(reaches, ALWAYS_PASS, 0,
                com.adeptum.skylang.backend.JvmProfile.INSTANCE, noneDeclared)
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        quiet(), new PrintStream(err));

        assertEquals(2, code, "the registry is the only door; nothing enters through the side");
        assertTrue(err.toString().contains("dependency 'bcrypt' used but not declared in requires"),
                err.toString());
    }

    @Test
    void aDeclaredDependencyMayBeUsed(@TempDir Path root) {
        Ast.Module module = checkedModule();
        StubLlm reaches = new StubLlm(
                "return new Product(p.id(), org.mindrot.jbcrypt.BCrypt.hashpw(p.name(), \"s\"), p.stock() + units);");

        int code = new Pipeline(reaches, ALWAYS_PASS, 0,
                com.adeptum.skylang.backend.JvmProfile.INSTANCE, bookDeps())
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        quiet(), quiet());

        assertEquals(0, code, "what requires lists is exactly what a body may use");
    }

    @Test
    void theTsNodeProfileFreezesTypeScript(@TempDir Path root) throws IOException {
        Ast.Module module = checkedModule();
        String tsBody = "return new Product(p.id, p.name, p.stock + units);";
        var out = new ByteArrayOutputStream();

        int code = new Pipeline(new StubLlm(tsBody), ALWAYS_PASS, 4,
                com.adeptum.skylang.backend.TsProfile.INSTANCE)
                .build(module, root.resolve("sky.lock"), root.resolve("build/ts-node"),
                        new PrintStream(out), quiet());

        assertEquals(0, code);
        assertTrue(out.toString().contains("▸ synthesized (ts) ▸ verified ▸ frozen @"),
                out.toString());
        assertTrue(Files.readString(root.resolve("build/ts-node/src/Catalog.ts")).contains(tsBody),
                "the TypeScript body is spliced into the staged service");
        assertTrue(Files.readString(root.resolve("sky.lock")).contains("p.stock + units"),
                "the frozen body is TypeScript source");
    }

    @Test
    void aFirstBuildSynthesizesIndependentMethodsConcurrently(@TempDir Path root) {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  restock(p Product, units Int) -> Product
                    intent "Increase stock."
                  rename(p Product, name Text) -> Product
                    intent "Rename the product."
                  discontinue(p Product, gone Bool) -> Product
                    intent "Mark the product discontinued."
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var highWater = new java.util.concurrent.atomic.AtomicInteger();
        StubLlm slowStub = new StubLlm((system, user) -> {
            highWater.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return BODY;
        });

        int code = new Pipeline(slowStub, ALWAYS_PASS).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code);
        assertTrue(highWater.get() >= 2,
                "independent first-build methods should synthesize concurrently, saw "
                        + highWater.get() + " in flight");
    }

    @Test
    void builtinRefinementsGuardTheEntityConstructor(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Country { code Text @id  currency Currency  vatRate Percentage }
                service Geo {
                  reprice(c Country, rate Percentage) -> Country
                    intent "Return a copy with the new VAT rate."
                }
                """, "shop.sky");
        new TypeChecker().check(module);

        int code = new Pipeline(new StubLlm("return new Country(c.code(), c.currency(), rate);"),
                ALWAYS_PASS).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code);
        String country = Files.readString(
                root.resolve("build/jvm-jakarta/src/main/java/shop/Country.java"));
        assertTrue(country.contains("matches"),
                "Currency lowers to a validated three-letter code:\n" + country);
        assertTrue(country.contains("vatRate < 0L") && country.contains("vatRate > 100L"),
                "Percentage lowers to a 0..100 guard:\n" + country);
    }

    @Test
    void maybeAndWhoseResultsStageHonestAssertions(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module shop
                entity OrderStatus { name Text @id  values Draft, Placed }
                entity Order { id Int @id  status OrderStatus = OrderStatus.Draft  placedAt Maybe<Instant> }
                entity User { id Int @id  email Email @unique  password Secret<Bytes> }
                service Reviews uses db {
                  averageRating(id Int) -> Maybe<Int>
                    intent  "The average rating, or nothing."
                    example averageRating(1) -> nothing
                  register(email Email, password Text) -> User
                    intent  "Create a user, storing only a hash."
                    example register("a@b.com", "hunter2-longpw!")
                            -> a User whose email is "a@b.com"
                               and whose password is not "hunter2-longpw!"
                  place(id Int) -> Order
                    intent  "Place the order."
                    example place(1) -> an order whose status is Placed and whose placedAt is set
                }
                """, "shop.sky");
        new TypeChecker().check(module);

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code);
        String tests = Files.readString(
                root.resolve("build/jvm-jakarta/src/test/java/shop/ReviewsTest.java"));
        assertTrue(tests.contains("assertTrue(result.isEmpty(), \"example: nothing\")"),
                "-> nothing asserts the absent Maybe:\n" + tests);
        assertTrue(tests.contains("assertEquals(\"a@b.com\", result.email()"),
                "whose email is ... asserts equality:\n" + tests);
        assertTrue(tests.contains(
                "assertNotEquals(Bytes.ofUtf8(\"hunter2-longpw!\"), result.password().reveal()"),
                "whose password is not ... compares the revealed bytes:\n" + tests);
        assertTrue(tests.contains("assertEquals(OrderStatus.Placed, result.status()"),
                "a bare constant qualifies against the field's value set:\n" + tests);
        assertTrue(tests.contains("assertTrue(result.placedAt().isPresent()"),
                "whose placedAt is set asserts presence:\n" + tests);
    }

    @Test
    void maybeMoneyAndBytesStageNullableColumns(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Provider { id Int @id  name Text  logo Maybe<Bytes>  fee Maybe<Money> }
                service Providers uses db {
                  all() -> [Provider]  intent "Every provider."
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String jpa = Files.readString(buildDir.resolve("src/main/java/shop/ProviderJpa.java"));
        assertTrue(jpa.contains("public byte[] logo;"),
                "Maybe<Bytes> persists as a nullable blob column:\n" + jpa);
        assertTrue(jpa.contains("java.util.Optional.ofNullable(logo).map(Bytes::of)"),
                "an absent blob reads back as an empty Maybe:\n" + jpa);
        assertTrue(jpa.contains("public java.math.BigDecimal feeAmount;")
                        && jpa.contains("public String feeCurrency;"),
                "Maybe<Money> persists as nullable amount plus currency:\n" + jpa);
        assertTrue(jpa.contains(
                "java.util.Optional.ofNullable(feeAmount).map(a -> Money.of(a.toPlainString(), feeCurrency))"),
                "an absent amount reads back as an empty Maybe:\n" + jpa);
    }

    @Test
    void mappedByCollectionsLowerToOneToMany(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module iam
                entity Company { id Int @id  name Text  permissions [Permission] @mappedBy(owner) }
                entity Permission { id Int @id  name Text  owner Company }
                service Companies uses db {
                  all() -> [Company]  intent "Every company."
                }
                """, "iam.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String company = Files.readString(buildDir.resolve("src/main/java/iam/CompanyJpa.java"));
        assertTrue(company.contains(
                "@jakarta.persistence.OneToMany(mappedBy = \"owner\", fetch = jakarta.persistence.FetchType.EAGER)"),
                "an owned collection maps to the inverse side of the child's relation:\n" + company);
        assertFalse(company.contains("ElementCollection"),
                "an owned collection is not an embeddable element collection:\n" + company);
        assertFalse(company.contains("PermissionJpa.of("),
                "the inverse side is query-derived; of() writes no children:\n" + company);
        assertTrue(company.contains("toRecordShallow"),
                "the parent exposes a shallow conversion to break the cycle:\n" + company);
        String permission = Files.readString(buildDir.resolve("src/main/java/iam/PermissionJpa.java"));
        assertTrue(permission.contains("owner.toRecordShallow()"),
                "the child's back-reference converts shallowly:\n" + permission);
    }

    @Test
    void proseResultStagesACallWithoutAssertions(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Provider { id Int @id  name Text }
                service Providers uses db {
                  upgrade(id Int) -> Provider
                    intent  "Upgrade the provider."
                    example upgrade(1) -> a Provider on the Free tier
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String tests = Files.readString(buildDir.resolve("src/test/java/shop/ProvidersTest.java"));
        assertTrue(tests.contains("var result = svc.upgrade(id)"),
                "a prose example still calls the method — the call completing is the pin:\n" + tests);
        assertFalse(tests.contains("assertEquals") || tests.contains("assertTrue"),
                "a prose result asserts nothing:\n" + tests);
    }

    @Test
    void scopedUniqueBecomesACompositeTableConstraint(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Provider { id Int @id  name Text }
                entity UserAccount {
                  id       Int @id
                  provider Provider
                  email    Email @unique(provider)
                }
                service Accounts uses db {
                  all() -> [UserAccount]  intent "Every account."
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String jpa = Files.readString(buildDir.resolve("src/main/java/shop/UserAccountJpa.java"));
        assertTrue(jpa.contains("uniqueConstraints = {@jakarta.persistence.UniqueConstraint("
                        + "columnNames = {\"email\", \"provider_id\"})}"),
                "a scoped @unique becomes a composite table constraint:\n" + jpa);
        assertTrue(jpa.contains("@jakarta.persistence.JoinColumn(name = \"provider_id\")"),
                "the scope's join column is pinned by name:\n" + jpa);
        assertTrue(jpa.contains("@jakarta.persistence.Column(name = \"email\")"),
                "the scoped column is pinned by name:\n" + jpa);
        assertFalse(jpa.contains("unique = true"),
                "no single-column constraint remains on a scoped field:\n" + jpa);
    }

    @Test
    void scopedUniquenessRaiseSeedsASameScopeDuplicate(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Provider { id Int @id  name Text }
                entity UserAccount {
                  id       Int @id
                  provider Provider
                  email    Email @unique(provider)
                }
                entity EmailTaken { }
                service Accounts uses db {
                  register(email Email) -> UserAccount
                    intent "Create an account."
                    raises EmailTaken when email already registered
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String tests = Files.readString(buildDir.resolve("src/test/java/shop/AccountsTest.java"));
        assertTrue(tests.contains("db.save(new UserAccount(1L, new Provider(1L, \"x\"), \"a@example.com\"))"),
                "the seed derives from the same defaults as the witness call, so it is a"
                        + " same-scope duplicate:\n" + tests);
        assertTrue(tests.contains("assertThrows(EmailTaken.class, () -> svc.register(\"a@example.com\")"),
                "the witness call registers the seeded email:\n" + tests);
        String account = Files.readString(buildDir.resolve("src/main/java/shop/UserAccount.java"));
        assertTrue(account.contains("email (per provider)"),
                "the advisory javadoc names the uniqueness scope:\n" + account);
    }

    @Test
    void pinnedValuesLowerToFullConstructorsAndLookup(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Tier {
                  name  Text @id
                  label Text
                  price Money
                  values Free with label "Free" and price 0eur, Pro with label "Pro" and price 399sek
                }
                entity Account { id Int @id  tier Tier  fallback Maybe<Tier>  history [Tier] }
                service Accounts uses db {
                  all() -> [Account]  intent "Every account."
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String tier = Files.readString(buildDir.resolve("src/main/java/shop/Tier.java"));
        assertTrue(tier.contains("new Tier(\"Free\", \"Free\", Money.of(\"0\", \"EUR\"))"),
                "a pinned value lowers to its full constructor:\n" + tier);
        assertTrue(tier.contains("new Tier(\"Pro\", \"Pro\", Money.of(\"399\", \"SEK\"))"),
                "every pin lowers in component order:\n" + tier);
        assertTrue(tier.contains("public static Tier of(String name)"),
                "a pinned value set exposes a carrier lookup:\n" + tier);
        String jpa = Files.readString(buildDir.resolve("src/main/java/shop/AccountJpa.java"));
        assertTrue(jpa.contains("Tier.of(tier)"),
                "a stored carrier restores the declared constant:\n" + jpa);
        assertTrue(jpa.contains("map(Tier::of)"),
                "Maybe and collection columns restore through the lookup:\n" + jpa);
        assertFalse(jpa.contains("new Tier("),
                "no carrier-only construction remains:\n" + jpa);
    }

    private static final String INTERFACE_MODULE = """
            module shop
            entity Product { id Int @id  name Text  stock Int @min(0) }
            entity Boom { }
            service Catalog uses db {
              all() -> [Product]  intent "Every product."
              restock(id Int, units Int) -> Product  intent "Increase stock."
            }
            component StockBadge(product Product) {
              shows   product.stock as a badge
              appears amber when product.stock <= 10
              expect  the badge shows the stock
            }
            flow Checkout {
              step Cart -> collect items
              step Pay  -> pay for the cart
              on success -> page Done
              on Boom    -> back to step Pay with message
              expect step Pay is reachable only after Cart
              expect no step follows success
            }
            """;

    private static final String COMPONENT_REPLY = """
            ```xhtml
            <cc:interface><cc:attribute name="product" type="shop.Product"/></cc:interface>
            <cc:implementation>
              <h:outputText id="badge" value="#{cc.attrs.product.stock}"
                            styleClass="#{cc.attrs.product.stock le 10 ? 'amber' : ''}"/>
            </cc:implementation>
            ```
            """;

    private static final String FLOW_REPLY = """
            ```json
            {"steps": ["Cart", "Pay"],
             "transitions": {"success": "page Done", "Boom": "step Pay"}}
            ```
            """;

    private static StubLlm interfaceStub() {
        return new StubLlm((system, user) -> {
            if (user.contains("Component to render")) {
                return COMPONENT_REPLY;
            }
            if (user.contains("Flow to realise")) {
                return FLOW_REPLY;
            }
            if (user.contains("View to render")) {
                return VIEW_REPLY;
            }
            return BODY;
        });
    }

    @Test
    void flowsAndComponentsSynthesizeVerifyAndFreeze(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse(INTERFACE_MODULE, "shop.sky");
        new TypeChecker().check(module);
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        var out = new ByteArrayOutputStream();

        int code = new Pipeline(interfaceStub(), ALWAYS_PASS)
                .build(module, lock, buildDir, new PrintStream(out), quiet());

        assertEquals(0, code, out.toString());
        String transcript = out.toString();
        assertTrue(transcript.contains("component StockBadge") && transcript.contains("✓ 1 expect   ✓ 1 appears"),
                transcript);
        assertTrue(transcript.contains("flow Checkout") && transcript.contains("✓ 2 expect"), transcript);
        assertTrue(transcript.contains("walked: Cart -> Pay -> success"), transcript);
        assertTrue(transcript.contains("walked: Pay -> Boom -> Pay"), transcript);

        assertTrue(Files.readString(lock).contains("\"components\""), "the component freezes");
        assertTrue(Files.readString(lock).contains("\"flows\""), "the flow freezes");
        assertTrue(Files.exists(buildDir.resolve("src/main/resources/components/stockBadge.xhtml")),
                "the composite stages under resources/components");
        String flowClass = Files.readString(
                buildDir.resolve("src/main/java/shop/CheckoutFlow.java"));
        assertTrue(flowClass.contains("List.of(\"Cart\", \"Pay\")"), flowClass);

        StubLlm second = interfaceStub();
        int again = new Pipeline(second, ALWAYS_PASS)
                .build(module, lock, buildDir, quiet(), quiet());
        assertEquals(0, again);
        assertEquals(0, second.calls(), "frozen interface units must not call the model again");
    }

    @Test
    void aFlowGraphSkippingAStepIsRejected(@TempDir Path root) {
        Ast.Module module = Parsing.parse(INTERFACE_MODULE, "shop.sky");
        new TypeChecker().check(module);
        StubLlm badFlow = new StubLlm((system, user) -> {
            if (user.contains("Flow to realise")) {
                return """
                        ```json
                        {"steps": ["Pay"], "transitions": {"success": "page Done"}}
                        ```
                        """;
            }
            if (user.contains("Component to render")) {
                return COMPONENT_REPLY;
            }
            return BODY;
        });
        var err = new ByteArrayOutputStream();

        int code = new Pipeline(badFlow, ALWAYS_PASS, 0).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), quiet(), new PrintStream(err));

        assertEquals(2, code, "a graph that skips a declared step must fail verification");
        assertTrue(err.toString().contains("flow Checkout"), err.toString());
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

        assertEquals(2, code);
    }

    private static final String BRANDING_MODULE = """
            module shop
            entity Tenant { id Int @id  name Text  tagline Text  logo Maybe<Bytes> }
            service Tenants uses db {
              current() -> Maybe<Tenant>  intent "The tenant for this request."
            }
            page Login at "/" {
              shows  Tenants.current() as a summary of (name, tagline, logo)
            }
            """;

    private static final String BRANDING_REPLY = """
            ```xhtml
            <h:panelGroup id="branding" styleClass="branding">
              <h:graphicImage id="logoImage" value="#{bean.logoDataUri}" alt="logo"/>
              <h:outputText id="name" value="#{bean.tenant.name}"/>
              <h:outputText id="tagline" value="#{bean.tenant.tagline}"/>
            </h:panelGroup>
            ```
            ```java
            public class LoginBean {}
            ```
            """;

    @Test
    void bytesSummaryStagesAnImageBindingAndRenderAssertion(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse(BRANDING_MODULE, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(routingStub(BRANDING_REPLY), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String render = Files.readString(buildDir.resolve("src/test/java/shop/ViewsRenderTest.java"));
        assertTrue(render.contains("img[id$=logoImage]"),
                "the render test asserts the logo renders as an image:\n" + render);
    }

    @Test
    void aViewMissingItsImageBindingIsDisposed(@TempDir Path root) {
        Ast.Module module = Parsing.parse(BRANDING_MODULE, "shop.sky");
        new TypeChecker().check(module);

        // The reply binds the logo as text, so the image expectation cannot hold.
        int code = new Pipeline(routingStub("""
                ```xhtml
                <h:outputText id="logo" value="#{bean.logo}"/>
                ```
                ```java
                public class LoginBean {}
                ```
                """), ALWAYS_PASS).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(2, code);
    }

    @Test
    void previewModeInjectsTheSelectionScript(@TempDir Path root) throws Exception {
        Path buildDir = root.resolve("build/jvm-jakarta");
        new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS).preview()
                .build(checkedViewModule(), root.resolve("sky.lock"), buildDir, quiet(), quiet());

        String xhtml = Files.readString(buildDir.resolve("src/main/webapp/ProductList.xhtml"));
        assertTrue(xhtml.contains("sky.select"), "preview pages carry the studio selection script");
        assertTrue(xhtml.contains("CDATA"), "the script is CDATA-wrapped so it stays valid Facelets XML");
    }

    @Test
    void normalStagingCarriesNoSelectionScript(@TempDir Path root) throws Exception {
        Path buildDir = root.resolve("build/jvm-jakarta");
        new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS)
                .build(checkedViewModule(), root.resolve("sky.lock"), buildDir, quiet(), quiet());

        String xhtml = Files.readString(buildDir.resolve("src/main/webapp/ProductList.xhtml"));
        assertFalse(xhtml.contains("sky.select"), "a normal build stays clean — no preview script");
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
              pay(id Int, amount Money, moment Instant) -> Order  intent "pay"
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
    void promptedDateAndDateTimeInputsStageTheirConverters(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Course { id Int  name Text }
                service Courses {
                  all() -> [Course]  intent "all"
                  schedule(id Int, starts Date, opensAt DateTime) -> Course  intent "schedule"
                }
                view Schedule at "/schedule" {
                  shows  Courses.all() as a table of (id)
                  action "Schedule" on row -> Courses.schedule(row.id, ask Date, ask DateTime)
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertTrue(Files.readString(buildDir.resolve("src/main/java/shop/DateConverter.java"))
                .contains("sky.date"), "the Date converter must register under sky.date");
        assertTrue(Files.readString(buildDir.resolve("src/main/java/shop/DateTimeConverter.java"))
                .contains("sky.datetime"), "the DateTime converter must register under sky.datetime");
        String interaction = Files.readString(buildDir.resolve("src/test/java/shop/ViewsInteractionTest.java"));
        assertTrue(interaction.contains("2026-01-01\""),
                "the interaction lane needs a valid Date sample");
        assertTrue(interaction.contains("2026-01-01T00:00:00\""),
                "the interaction lane needs a valid DateTime sample");
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

    private static final String ROLES = """
            module crew
            entity Role {
              name Text @id
              values Member, Admin
            }
            entity User {
              id   Int  @id
              name Text
              role Role = Role.Member
            }
            service Users {
              promote(u User) -> User
                intent  "Return a copy with the admin role."
                ensures result.role == Role.Admin
                example promote(User(1, "Ada", Role.Member)) -> a User with role Role.Admin
            }
            """;

    @Test
    void valuesEntitiesLowerToClosedConstantSets(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(ROLES, "crew.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String role = Files.readString(buildDir.resolve("src/main/java/crew/Role.java"));
        assertTrue(role.contains("public static final Role Member = new Role(\"Member\")"),
                "each declared value should lower to a constant");
        assertTrue(role.contains("public static final Role Admin = new Role(\"Admin\")"));

        String user = Files.readString(buildDir.resolve("src/main/java/crew/User.java"));
        assertTrue(user.contains("public User(long id, String name)") && user.contains("Role.Member"),
                "the member default should feed the omitting constructor");
        assertTrue(user.contains("equals") && user.contains("id == other.id"),
                "an @id entity should compare by its identity");

        String tests = Files.readString(buildDir.resolve("src/test/java/crew/UsersTest.java"));
        assertTrue(tests.contains("Role.Admin"), "value constants should lower into example asserts");
    }

    private static final String NOTIFY = """
            module notify
            entity Note {
              id        Int @id
              text      Text(1..280)
              createdAt Instant = now
            }
            service Notifier uses clock, mail {
              remind(n Note, to Email) -> Bool
                intent  "Send the note text to the address and return true."
                example remind(Note(1, "hello"), "a@example.com") -> true
            }
            """;

    @Test
    void effectsLowerToInjectedHandles(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(NOTIFY, "notify.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return true;"), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String notifier = Files.readString(buildDir.resolve("src/main/java/notify/Notifier.java"));
        assertTrue(notifier.contains("private final java.time.Clock clock;"),
                "the clock effect should be an injected handle");
        assertTrue(notifier.contains("private final Mail mail;"),
                "the mail effect should be an injected handle");
        assertTrue(notifier.contains("public Notifier(java.time.Clock clock, Mail mail)"),
                "declared effects arrive through the constructor");

        assertTrue(Files.exists(buildDir.resolve("src/main/java/notify/Mail.java")),
                "the Mail effect interface should be staged when declared");
        assertFalse(Files.exists(buildDir.resolve("src/main/java/notify/Http.java")),
                "undeclared effects must not exist in the staged project");
        assertTrue(Files.exists(buildDir.resolve("src/main/java/notify/SkyClock.java")),
                "a '= now' default needs the pinnable clock holder");
        assertTrue(Files.readString(buildDir.resolve("src/main/java/notify/Note.java"))
                .contains("SkyClock.now()"), "the omitting constructor should read the pinnable clock");

        String tests = Files.readString(buildDir.resolve("src/test/java/notify/NotifierTest.java"));
        assertTrue(tests.contains("new Notifier(TestEffects.clock(), TestEffects.mail())"),
                "generated tests wire substitute effects");
        assertTrue(Files.readString(buildDir.resolve("src/test/java/notify/TestEffects.java"))
                .contains("Clock.fixed"), "the test clock must be pinned for determinism");
    }

    private static final String STORE = """
            module store
            entity Status { name Text @id  values Open, Closed }
            entity Customer { id Int @id  email Email @unique }
            entity LineItem { item Text  quantity Int(1..) }
            entity Order {
              id       Int @id
              customer Customer
              status   Status  = Status.Open
              items    [LineItem]
              placed   Instant = now
              total    Money
            }
            service Orders uses db, clock {
              place(o Order) -> Order
                intent "Persist the order and return the stored copy."
            }
            """;

    @Test
    void dbEffectStagesTheJpaStore(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(STORE, "store.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return db.save(o);"), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String db = Files.readString(buildDir.resolve("src/main/java/store/Db.java"));
        assertTrue(db.contains("Order save(Order order)") && db.contains("findOrder(long id)")
                        && db.contains("allOrders()") && db.contains("deleteOrder(long id)"),
                "the db effect exposes a typed store per identified entity");
        assertFalse(db.contains("saveLineItem") || db.contains("findStatus"),
                "components and value sets are not store roots");

        String orderJpa = Files.readString(buildDir.resolve("src/main/java/store/OrderJpa.java"));
        assertTrue(orderJpa.contains("@jakarta.persistence.Entity"), "roots map to JPA entities");
        assertTrue(orderJpa.contains("ManyToOne"), "an entity reference maps to a relation");
        assertTrue(orderJpa.contains("ElementCollection"), "a component list maps to an element collection");
        assertTrue(orderJpa.contains("placedMillis"), "an Instant persists as epoch millis");
        assertTrue(orderJpa.contains("totalAmount") && orderJpa.contains("totalCurrency"),
                "Money persists as amount plus currency");
        assertTrue(Files.readString(buildDir.resolve("src/main/java/store/LineItemJpa.java"))
                .contains("@jakarta.persistence.Embeddable"), "components map to embeddables");
        assertTrue(Files.readString(buildDir.resolve("src/main/java/store/CustomerJpa.java"))
                .contains("unique = true"), "@unique becomes a schema constraint when persisted");

        assertTrue(Files.exists(buildDir.resolve("src/main/java/store/JpaDb.java")),
                "the JPA-backed store implementation should be staged");
        String persistence = Files.readString(buildDir.resolve("src/main/resources/META-INF/persistence.xml"));
        assertTrue(persistence.contains("store.OrderJpa") && persistence.contains("eclipse"),
                "the persistence unit lists the mapped classes");
        String pom = Files.readString(buildDir.resolve("pom.xml"));
        assertTrue(pom.contains("jakarta.persistence-api") && pom.contains("eclipselink") && pom.contains("h2"),
                "the staged POM carries the JPA provider and test database");
        assertTrue(Files.readString(buildDir.resolve("src/test/java/store/TestEffects.java"))
                .contains("static Db db()"), "tests get a fresh in-memory store");
    }

    @Test
    void dateAndDateTimePersistAsNativeColumns(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module plan
                entity Course { id Int @id  starts Date  opensAt DateTime  ends Maybe<Date> }
                service Courses uses db {
                  all() -> [Course]  intent "Every course."
                }
                """, "plan.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String jpa = Files.readString(buildDir.resolve("src/main/java/plan/CourseJpa.java"));
        assertTrue(jpa.contains("public java.time.LocalDate starts;"),
                "a Date persists as a native LocalDate column:\n" + jpa);
        assertTrue(jpa.contains("public java.time.LocalDateTime opensAt;"),
                "a DateTime persists as a native LocalDateTime column:\n" + jpa);
        assertTrue(jpa.contains("public java.time.LocalDate ends;"),
                "a Maybe<Date> persists as a nullable LocalDate column:\n" + jpa);
        assertTrue(jpa.contains("java.util.Optional.ofNullable(ends)"),
                "an absent date reads back as an empty Maybe:\n" + jpa);
        assertFalse(jpa.contains("startsMillis"),
                "no epoch conversion for zoneless temporals:\n" + jpa);
    }

    @Test
    void todayDefaultStagesThePinnableClock(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module plan
                entity Course { id Int @id  name Text  starts Date = today }
                service Courses uses db {
                  all() -> [Course]  intent "Every course."
                }
                """, "plan.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String skyClock = Files.readString(buildDir.resolve("src/main/java/plan/SkyClock.java"));
        assertFalse(skyClock.contains("LocalDate.now(") || skyClock.contains("LocalDateTime.now("),
                "the clock holder must not carry effect-linter tokens:\n" + skyClock);
        assertTrue(Files.readString(buildDir.resolve("src/main/java/plan/Course.java"))
                .contains("SkyClock.today()"), "the omitting constructor reads today from the clock");
        assertTrue(Files.readString(buildDir.resolve("src/test/java/plan/TestEffects.java"))
                .contains("Clock.fixed"), "a '= today' module pins the test clock too");
    }

    @Test
    void nowOnDateTimeStagesTheDateTimeClockRead(@TempDir Path root) throws IOException {
        Ast.Module module = Parsing.parse("""
                module plan
                entity Booking { id Int @id  bookedAt DateTime = now  createdAt Instant = now }
                service Bookings uses db {
                  all() -> [Booking]  intent "Every booking."
                }
                """, "plan.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS).build(module,
                root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String booking = Files.readString(buildDir.resolve("src/main/java/plan/Booking.java"));
        assertTrue(booking.contains("SkyClock.nowDateTime()"),
                "'= now' on a DateTime field reads the wall clock:\n" + booking);
        assertTrue(booking.contains("SkyClock.now()"),
                "'= now' on an Instant field keeps its original read:\n" + booking);
    }

    @Test
    void bodyOutsideItsBudgetFailsTheBuild(@TempDir Path root) {
        Ast.Module module = Parsing.parse(NOTIFY, "notify.sky");
        new TypeChecker().check(module);
        var err = new ByteArrayOutputStream();

        int code = new Pipeline(new StubLlm("return !java.time.Instant.now().toString().isEmpty();"),
                ALWAYS_PASS).build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                quiet(), new PrintStream(err));

        assertEquals(2, code, "a body reaching outside its effects budget must fail the build");
        assertTrue(err.toString().contains("clock.instant()"), err.toString());
    }

    @Test
    void lintFailuresRegenerateBeforeFailing(@TempDir Path root) {
        Ast.Module module = Parsing.parse(NOTIFY, "notify.sky");
        new TypeChecker().check(module);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        StubLlm healsItself = new StubLlm((system, user) ->
                attempts.incrementAndGet() == 1 ? "return !java.time.Instant.now().toString().isEmpty();"
                        : "return true;");

        int code = new Pipeline(healsItself, ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(0, code, "a lint violation should trigger regeneration, not immediate failure");
        assertEquals(2, healsItself.calls());
    }

    private static final String LIBRARY = """
            module library
            entity Book { id Int @id  title Text(1..120)  copies Int @min(0) }
            entity Member { id Int @id  email Email @unique }
            entity NotFound { }
            entity BadInput { }
            entity DuplicateEmail { requested Email }
            service Shelf uses db {
              lend(id Int, copies Int) -> Book
                intent   "Decrease the stored book's copies."
                raises   NotFound when no book has that id
                raises   BadInput when copies <= 0
              reserve(id Int, days Int) -> Book
                intent   "Reserve the book for that many days."
                requires days > 0
              register(email Email) -> Member
                intent "Create the member."
                raises DuplicateEmail when email already registered
            }
            """;

    @Test
    void errorsLowerToExceptionsAndRaisesToTests(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(LIBRARY, "library.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String notFound = Files.readString(buildDir.resolve("src/main/java/library/NotFound.java"));
        assertTrue(notFound.contains("extends RuntimeException"), "errors lower to exceptions");
        String duplicate = Files.readString(buildDir.resolve("src/main/java/library/DuplicateEmail.java"));
        assertTrue(duplicate.contains("requested()"), "error fields carry the caller's context");
        assertFalse(Files.exists(buildDir.resolve("src/main/java/library/NotFoundJpa.java")),
                "errors are not data and must not be persisted");

        String shelf = Files.readString(buildDir.resolve("src/main/java/library/Shelf.java"));
        assertTrue(shelf.contains("requires: days > 0") && shelf.contains("IllegalArgumentException"),
                "requires lowers to a guard before the body");

        String tests = Files.readString(buildDir.resolve("src/test/java/library/ShelfTest.java"));
        assertTrue(tests.contains("assertThrows(NotFound.class"),
                "an existence raises becomes a test against the empty store");
        assertTrue(tests.contains("assertThrows(BadInput.class"),
                "a comparison raises becomes a boundary-witness test");
        assertTrue(tests.contains("assertThrows(DuplicateEmail.class"),
                "a uniqueness raises becomes a seeded-duplicate test");
        assertTrue(tests.contains("assertThrows(IllegalArgumentException.class"),
                "a requires guard gets its own boundary test");
    }

    private static final String WAREHOUSE = """
            module warehouse
            entity Product { id Int @id  name Text  stock Int @min(0) }
            service Stock uses db {
              restock(id Int, units Int) -> Product
                intent  "Add units to the stored product's stock and persist it."
                ensures result.stock == old(result.stock) + units
                example restock(1, 3) -> a Product with stock 8
              totalStock() -> Int
                intent  "The total stock across the store."
                ensures result == sum of (p.stock for p in all products)
                example totalStock() -> 0
              outOfStock() -> Int
                intent  "How many stored products are out of stock."
                ensures result == count of (p for p in all products where p.stock == 0)
                example outOfStock() -> 0
              bigger(a Int, b Int) -> Int
                intent  "The larger of the two."
                ensures result == max(a, b)
                example bigger(2, 5) -> 5
            }
            service Helpers {
              double(x Int) -> Int
                intent  "Twice the value."
                example double(2) -> 4
            }
            service Sums {
              quadruple(x Int) -> Int
                intent  "Four times the value."
                ensures result == double(double(x))
                example quadruple(2) -> 8
            }
            """;

    @Test
    void oldAggregatesAndHelpersLowerIntoTests(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(WAREHOUSE, "warehouse.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String stock = Files.readString(buildDir.resolve("src/test/java/warehouse/StockTest.java"));
        assertTrue(stock.contains("db.findProduct(id).orElseThrow()"),
                "old(result...) snapshots the stored row before the call");
        assertTrue(stock.contains("__old0"), "old() lowers to a pre-call snapshot variable");
        assertTrue(stock.contains("sumOf(") && stock.contains(".stream()"),
                "sum of lowers to a stream fold through the dispatching helper");
        assertTrue(stock.contains("db.allProducts()"), "'all products' reads the store under test");
        assertTrue(stock.contains(".filter(") && stock.contains(".count()"),
                "count of lowers to a filtered count");
        assertTrue(stock.contains("max(a, b)"), "max lowers through the overloaded helpers");

        String sums = Files.readString(buildDir.resolve("src/test/java/warehouse/SumsTest.java"));
        assertTrue(sums.contains("new Helpers().double(new Helpers().double(x))"),
                "effect-free helper calls lower to service calls");
    }

    private static final String LEDGER = """
            module ledger
            entity Account { id Int @id  balance Money }
            entity Receipt { id Int @id  amount Money }
            entity InsufficientFunds { }
            entity BadInput { }
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
            service Shelf uses db {
              restock(id Int, units Int) -> Account
                example restock(7, 3) on a Account with balance 5eur -> balance 8eur
                example restock(7, 0) -> raises BadInput
            }
            """;

    @Test
    void specsAndSeededExamplesLowerIntoTests(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(LEDGER, "ledger.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String bank = Files.readString(buildDir.resolve("src/test/java/ledger/BankTest.java"));
        assertTrue(bank.contains("void transfer_spec_1()"), "each spec becomes a test");
        assertTrue(bank.contains("new Account(1L, Money.of(\"100\", \"EUR\"))"),
                "given pins construct the witness arguments");
        assertTrue(bank.contains("db.save(from)") && bank.contains("db.save(to)"),
                "given state is seeded into the store");
        assertTrue(bank.contains("db.findAccount((from).id()).orElseThrow()"),
                "then re-reads stored state after the call");
        assertTrue(bank.contains("assertThrows(InsufficientFunds.class"),
                "then raises becomes an exception assertion");

        String shelf = Files.readString(buildDir.resolve("src/test/java/ledger/ShelfTest.java"));
        assertTrue(shelf.contains("db.save(new Account(id, Money.of(\"5\", \"EUR\")))"),
                "a seeded example stores the row keyed by the call argument");
        assertTrue(shelf.contains("assertThrows(BadInput.class"),
                "a raises example result becomes an exception assertion");
        assertTrue(shelf.contains("result.balance()"),
                "bare field expectations assert on the result");
    }

    private static final String VAULT = """
            module vault
            type Password = Text(12..128)
            entity WeakPassword { }
            entity User { id Int @id  password Password }
            policy StrongPasswords {
              whenever a Password is constructed
              require  length >= 12 and contains a symbol
              else     raise WeakPassword
            }
            policy NoSecretsInLogs {
              whenever a Secret is passed to a logger
              forbid
            }
            service Users uses db {
              register(id Int, password Password) -> User
                intent "Create and store the user."
            }
            """;

    @Test
    void requirePoliciesGuardConstructionSites(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(VAULT, "vault.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(new StubLlm("return db.save(new User(id, password));"), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String user = Files.readString(buildDir.resolve("src/main/java/vault/User.java"));
        assertTrue(user.contains("new WeakPassword()"),
                "a construction policy raises its named error in the record constructor");
        assertTrue(user.contains("isLetterOrDigit"), "'contains a symbol' lowers to a character scan");
        String users = Files.readString(buildDir.resolve("src/main/java/vault/Users.java"));
        assertTrue(users.contains("new WeakPassword()"),
                "the policy also guards service parameters of the type");
        assertTrue(users.contains("password.length() >= 12"),
                "the length predicate lowers to plain Java");
    }

    @Test
    void forbidPoliciesFailViolatingBodies(@TempDir Path root) {
        Ast.Module module = Parsing.parse(VAULT.replace("password Password }", "password Password  pin Secret<Text> }"),
                "vault.sky");
        new TypeChecker().check(module);
        var err = new ByteArrayOutputStream();

        int code = new Pipeline(
                new StubLlm("User u = db.save(new User(id, password, Secret.of(\"1\")));\n"
                        + "System.out.println(u.pin().reveal());\nreturn u;"),
                ALWAYS_PASS).build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                quiet(), new PrintStream(err));

        assertEquals(2, code, "a body breaking a forbid policy must fail the build");
        assertTrue(err.toString().contains("NoSecretsInLogs"), err.toString());
    }

    private static final String CRYPTO = """
            module crypto
            service Hasher {
              hash(input Bytes) -> Bytes
                ensures result.length == 32
                java {
                  var md = java.security.MessageDigest.getInstance("SHA-256");
                  return Bytes.of(md.digest(input.toByteArray()));
                }
            }
            """;

    @Test
    void aStagedCompileFailurePointsBackAtTheJavaBlock(@TempDir Path root) {
        Ast.Module module = Parsing.parse(CRYPTO, "crypto.sky");
        new TypeChecker().check(module);
        Verifier brokenCompile = dir -> VerificationResult.fail("""
                [ERROR] COMPILATION ERROR :\s
                [ERROR] /tmp/build/jvm-jakarta/src/main/java/crypto/Hasher.java:[14,9] cannot find symbol
                """);
        var err = new ByteArrayOutputStream();

        int code = new Pipeline(new StubLlm("return null;"), brokenCompile).build(module,
                root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"), quiet(), new PrintStream(err));

        assertEquals(2, code);
        String report = err.toString();
        assertTrue(report.contains("error [backend]: the staged project did not compile"), report);
        assertTrue(report.contains("Hasher.java:[14,9] cannot find symbol"), report);
        assertTrue(report.contains("the fix belongs in the"), report);
        assertTrue(report.contains("java block of the .sky source."), report);
    }

    @Test
    void nativeBodiesBypassTheModelAndFreeze(@TempDir Path root) throws Exception {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        Ast.Module module = Parsing.parse(CRYPTO, "crypto.sky");
        new TypeChecker().check(module);
        StubLlm stub = new StubLlm("return null;");

        int code = new Pipeline(stub, ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(0, stub.calls(), "a native body must never reach the model");
        String hasher = Files.readString(buildDir.resolve("src/main/java/crypto/Hasher.java"));
        assertTrue(hasher.contains("MessageDigest.getInstance(\"SHA-256\")"),
                "the hand-written body is staged verbatim");
        assertTrue(hasher.contains("catch (Exception e)"),
                "native bodies may use checked platform APIs without changing the signature");

        StubLlm second = new StubLlm("return null;");
        var verified = new java.util.concurrent.atomic.AtomicBoolean();
        Verifier recording = dir -> {
            verified.set(true);
            return VerificationResult.pass();
        };
        assertEquals(0, new Pipeline(second, recording).build(module, lock, buildDir, quiet(), quiet()));
        assertEquals(0, second.calls());
        assertFalse(verified.get(), "an unchanged native body is frozen like any other");

        Ast.Module edited = Parsing.parse(CRYPTO.replace("SHA-256", "SHA-512"), "crypto.sky");
        new TypeChecker().check(edited);
        assertEquals(0, new Pipeline(new StubLlm("return null;"), recording)
                .build(edited, lock, buildDir, quiet(), quiet()));
        assertTrue(verified.get(), "editing the native body must re-verify it");
    }

    @Test
    void nativeBodiesAreHeldToTheBudget(@TempDir Path root) {
        Ast.Module module = Parsing.parse("""
                module crypto
                service Stamper {
                  stamp(input Text) -> Text
                    ensures result == input
                    java {
                      return input + java.time.Instant.now();
                    }
                }
                """, "crypto.sky");
        new TypeChecker().check(module);
        var err = new ByteArrayOutputStream();
        StubLlm stub = new StubLlm("return null;");

        int code = new Pipeline(stub, ALWAYS_PASS).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), quiet(), new PrintStream(err));

        assertEquals(2, code, "hand-written bodies obey the same effects budget");
        assertEquals(0, stub.calls(), "a native violation must not trigger regeneration");
        assertTrue(err.toString().contains("clock"), err.toString());
    }

    @Test
    void nativeVerifyFailuresDoNotRegenerate(@TempDir Path root) {
        Ast.Module module = Parsing.parse(CRYPTO, "crypto.sky");
        new TypeChecker().check(module);
        StubLlm stub = new StubLlm("return null;");
        Verifier alwaysFail = dir -> VerificationResult.fail("digest length was wrong");

        int code = new Pipeline(stub, alwaysFail).build(module, root.resolve("sky.lock"),
                root.resolve("build/jvm-jakarta"), quiet(), quiet());

        assertEquals(2, code, "a native body failing its contracts fails the build");
        assertEquals(0, stub.calls(), "the model must never be asked to rewrite a native body");
    }

    @Test
    void theBuildTranscriptReadsLikeTheFreezeModel(@TempDir Path root) {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        var first = new ByteArrayOutputStream();
        new Pipeline(new StubLlm(BODY), ALWAYS_PASS)
                .build(checkedModule(), lock, buildDir, new PrintStream(first), quiet());
        String firstOut = first.toString();
        assertTrue(firstOut.contains("▸ synthesized ▸ verified ▸ frozen @"), firstOut);
        assertTrue(firstOut.contains("✓ 2 contracts") && firstOut.contains("✓ 1 example"), firstOut);

        var second = new ByteArrayOutputStream();
        new Pipeline(new StubLlm(BODY), ALWAYS_PASS)
                .build(checkedModule(), lock, buildDir, new PrintStream(second), quiet());
        assertTrue(second.toString().contains("▸ frozen @") && second.toString().contains("(unchanged)"),
                second.toString());

        Ast.Module changed = Parsing.parse(SHOP.replace("Increase stock.", "Raise it."), "shop.sky");
        new TypeChecker().check(changed);
        var third = new ByteArrayOutputStream();
        new Pipeline(new StubLlm(BODY), ALWAYS_PASS)
                .build(changed, lock, buildDir, new PrintStream(third), quiet());
        assertTrue(third.toString().contains("▸ regenerated ▸ verified ▸ frozen @"), third.toString());
    }

    @Test
    void bankRebuildStaysFrozen(@TempDir Path root) {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(checkedBankModule(), lock, buildDir, quiet(), quiet());

        StubLlm second = new StubLlm("return null;");
        int code = new Pipeline(second, ALWAYS_PASS).build(checkedBankModule(), lock, buildDir, quiet(), quiet());

        assertEquals(0, code);
        assertEquals(0, second.calls(), "an unchanged module with type declarations must stay frozen");
    }

    @Test
    void changingATypeDeclarationRegenerates(@TempDir Path root) {
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(checkedBankModule(), lock, buildDir, quiet(), quiet());

        Ast.Module changed = Parsing.parse(BANK.replace("{4,16}", "{4,32}"), "bank.sky");
        new TypeChecker().check(changed);
        StubLlm after = new StubLlm("return null;");
        new Pipeline(after, ALWAYS_PASS).build(changed, lock, buildDir, quiet(), quiet());

        assertEquals(2, after.calls(),
                "a changed type declaration is a spec change: every method re-synthesizes");
    }

    private static final String STORE_WITH_ALL = STORE.replace(
            "service Orders uses db, clock {",
            "service Orders uses db, clock {\n  all() -> [Order]  intent \"Every order.\"\n");

    @Test
    void webModulesWireEffectsThroughCdi(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(STORE_WITH_ALL + """
                view OrderList at "/orders" {
                  shows  Orders.all() as a table of (id)
                }
                """, "store.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        int code = new Pipeline(routingStub(VIEW_REPLY), ALWAYS_PASS)
                .build(module, root.resolve("sky.lock"), buildDir, quiet(), quiet());

        assertEquals(0, code);
        String orders = Files.readString(buildDir.resolve("src/main/java/store/Orders.java"));
        assertTrue(orders.contains("@jakarta.inject.Inject"),
                "a web-mode service receives its effects through CDI");
        assertTrue(orders.contains("protected Orders()"),
                "a normal-scoped CDI bean needs a constructor the proxy can call");
        String effects = Files.readString(buildDir.resolve("src/main/java/store/Effects.java"));
        assertTrue(effects.contains("@jakarta.enterprise.inject.Produces") && effects.contains("JpaDb"),
                "the declared effects need CDI producers");
        assertFalse(effects.contains("Mail mail"), "undeclared effects get no producer");
        assertTrue(Files.readString(buildDir.resolve("src/main/resources/META-INF/persistence.xml"))
                .contains("jdbc:h2:mem"), "the web persistence unit must be runnable as staged");
        assertTrue(Files.readString(buildDir.resolve("pom.xml")).contains("eclipselink"),
                "the web POM carries the JPA provider");
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
