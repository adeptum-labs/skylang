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

import com.adeptum.skylang.backend.JvmProfile;
import com.adeptum.skylang.deps.Budget;
import com.adeptum.skylang.deps.Registry;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.run.RunSession;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.synth.StubLlm;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of {@code sky run}: the war {@code sky build} emits is what gets served, its
 * root is the module's first view, and a frozen module needs no model to bring up. Opt-in (needs
 * Maven + the TomEE stack): run with {@code SKY_E2E=1}.
 */
@EnabledIfEnvironmentVariable(named = "SKY_E2E", matches = "1")
class RunSessionE2ETest {

    private static final String SHOP = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog {
              all() -> [Product]  intent "Every product."
            }
            view ProductList at "/products" {
              shows  Catalog.all() as a table of (name, stock)
            }
            """;

    private static final String VIEW_REPLY = """
            ```xhtml
            <h:form id="f">
              <h:dataTable id="products" value="#{productListBean.rows}" var="row">
                <h:column id="c_name"><h:outputText value="#{row.name}"/></h:column>
                <h:column id="c_stock"><h:outputText value="#{row.stock}"/></h:column>
              </h:dataTable>
            </h:form>
            ```
            ```java
            @jakarta.inject.Named
            @jakarta.faces.view.ViewScoped
            public class ProductListBean implements java.io.Serializable {
                @jakarta.inject.Inject
                Catalog catalog;
                public java.util.List<Product> getRows() { return catalog.all(); }
            }
            ```
            """;

    /** The same view reduced to one column — what the stub answers once `stock` leaves the spec. */
    private static final String NAME_ONLY_REPLY = """
            ```xhtml
            <h:form id="f">
              <h:dataTable id="products" value="#{productListBean.rows}" var="row">
                <h:column id="c_name"><h:outputText value="#{row.name}"/></h:column>
              </h:dataTable>
            </h:form>
            ```
            ```java
            @jakarta.inject.Named
            @jakarta.faces.view.ViewScoped
            public class ProductListBean implements java.io.Serializable {
                @jakarta.inject.Inject
                Catalog catalog;
                public java.util.List<Product> getRows() { return catalog.all(); }
            }
            ```
            """;

    private static final String ALL_BODY =
            "return java.util.List.of(new Product(1L, \"Notebook\", 5L), new Product(2L, \"Pen\", 12L));";
    private static final Verifier ALWAYS_PASS = dir -> VerificationResult.pass();

    private static Budget noDeps() {
        return new Budget(List.of(), Registry.forProfile("jvm-jakarta", Optional.empty()).prefixIndex());
    }

    private static RunSession session(Llm llm) {
        return new RunSession(llm, ALWAYS_PASS, JvmProfile.INSTANCE, noDeps());
    }

    private static StubLlm stub() {
        return new StubLlm((system, user) -> system.contains("UI-synthesis") ? VIEW_REPLY : ALL_BODY);
    }

    private static Ast.Module checked() {
        Ast.Module module = Parsing.parse(SHOP, "shop.sky");
        new TypeChecker().check(module);
        return module;
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesThePackagedApplicationAtItsFrontDoor(@TempDir Path root) throws Exception {
        Path sky = root.resolve("shop.sky");
        Files.writeString(sky, SHOP);
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        int port = freePort();

        try (RunSession session = session(stub())) {
            RunSession.Handle handle = session.open(checked(), sky, lock, buildDir, "shop", port,
                    false, quiet(), quiet());

            assertEquals(port, handle.port(), "run serves on the port it was asked for");
            assertTrue(Files.exists(buildDir.resolve("target/shop.war")),
                    "run serves the artifact `sky build` emits");

            HttpResponse<String> root_ = get("http://localhost:" + port + "/");
            assertEquals(200, root_.statusCode(), root_::body);
            assertTrue(root_.body().contains("ProductList.xhtml"),
                    "the root should send the browser to the first view: " + root_.body());

            HttpResponse<String> view = get("http://localhost:" + port + "/ProductList.xhtml");
            assertEquals(200, view.statusCode(), view::body);
            assertTrue(view.body().contains("Notebook"),
                    "the running application should serve real data: " + view.body());
            assertTrue(view.body().contains("<table"),
                    "and Faces should have rendered it, not served the source: " + view.body());
        }
    }

    @Test
    void aFrozenApplicationRunsWithNoModelCalls(@TempDir Path root) throws Exception {
        Path sky = root.resolve("shop.sky");
        Files.writeString(sky, SHOP);
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(stub(), ALWAYS_PASS).build(checked(), lock, buildDir, quiet(), quiet());

        Llm forbidden = (system, user) -> {
            throw new AssertionError("the model was called for a frozen module");
        };
        int port = freePort();
        try (RunSession session = session(forbidden)) {
            RunSession.Handle handle = session.open(checked(), sky, lock, buildDir, "shop", port,
                    false, quiet(), quiet());

            assertEquals(200, get("http://localhost:" + handle.port() + "/ProductList.xhtml").statusCode());
        }
    }

    @Test
    void skippingTheBuildServesTheArtifactAlreadyThere(@TempDir Path root) throws Exception {
        Path sky = root.resolve("shop.sky");
        Files.writeString(sky, SHOP);
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        int port = freePort();

        // Build once, then serve that war with a model that would fail if the build ran again.
        try (RunSession first = session(stub())) {
            first.open(checked(), sky, lock, buildDir, "shop", port, false, quiet(), quiet());
        }

        Llm forbidden = (system, user) -> {
            throw new AssertionError("--skip-build must not build");
        };
        int second = freePort();
        try (RunSession session = session(forbidden)) {
            RunSession.Handle handle = session.open(checked(), sky, lock, buildDir, "shop", second,
                    true, quiet(), quiet());

            assertEquals(200, get("http://localhost:" + handle.port() + "/ProductList.xhtml").statusCode());
        }
    }

    /**
     * The watcher is the riskiest path in a run: it stops a serving application and puts a freshly
     * packaged one back on the same port. Drive the real loop — {@code run()}, not {@code open()}.
     */
    @Test
    void savingTheSourceRebuildsAndServesTheNewApplication(@TempDir Path root) throws Exception {
        Path sky = root.resolve("shop.sky");
        Files.writeString(sky, SHOP);
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        int port = freePort();

        // The view the stub answers with follows the spec's projection (the prompt's "Columns:"
        // line — the entity itself always mentions stock), so a reload is visible over HTTP.
        StubLlm following = new StubLlm((system, user) -> {
            if (!system.contains("UI-synthesis")) {
                return ALL_BODY;
            }
            return user.contains("Columns: name, stock") ? VIEW_REPLY : NAME_ONLY_REPLY;
        });

        RunSession session = session(following);
        Thread run = new Thread(() -> session.run(checked(), sky, lock, buildDir, "shop", port,
                false, quiet(), quiet()), "run-under-test");
        run.setDaemon(true);
        try {
            run.start();
            waitForServing(port);
            assertTrue(get("http://localhost:" + port + "/ProductList.xhtml").body().contains("12"),
                    "the first build serves the two-column view");

            Files.writeString(sky, SHOP.replace("as a table of (name, stock)", "as a table of (name)"));

            // The rebuild repackages and relaunches; wait for the one-column view to appear.
            String body = "";
            for (int i = 0; i < 240; i++) {
                Thread.sleep(500);
                try {
                    body = get("http://localhost:" + port + "/ProductList.xhtml").body();
                } catch (Exception stillRestarting) {
                    continue;   // the port is briefly closed while the application is swapped
                }
                if (body.contains("Notebook") && !body.contains("12")) {
                    break;
                }
            }
            assertTrue(body.contains("Notebook"), "the rebuilt application should serve its data: " + body);
            assertFalse(body.contains("12"),
                    "saving the source should bring up the rebuilt, one-column view: " + body);
        } finally {
            session.close();
        }
    }

    /** The application is packaged and launched first; give the run time to get there. */
    private static void waitForServing(int port) throws Exception {
        for (int i = 0; i < 240; i++) {
            try {
                if (get("http://localhost:" + port + "/ProductList.xhtml").statusCode() == 200) {
                    return;
                }
            } catch (Exception notYet) {
                // not listening yet
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the application never started serving on port " + port);
    }

    @Test
    void skippingTheBuildWithNoArtifactRefusesToStart(@TempDir Path root) throws Exception {
        Path sky = root.resolve("shop.sky");
        Files.writeString(sky, SHOP);

        try (RunSession session = session(stub())) {
            RunSession.LaunchFailed failure = assertThrows(RunSession.LaunchFailed.class,
                    () -> session.open(checked(), sky, root.resolve("sky.lock"),
                            root.resolve("build/jvm-jakarta"), "shop", freePort(), true, quiet(), quiet()));

            assertEquals(2, failure.exitCode());
            assertTrue(failure.getMessage().contains("no artifact at"), failure.getMessage());
        }
    }
}
