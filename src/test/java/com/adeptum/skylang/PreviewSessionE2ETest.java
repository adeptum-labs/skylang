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
import com.adeptum.skylang.preview.EditResult;
import com.adeptum.skylang.preview.PreviewSession;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of {@code sky preview}: a frozen view serves live with no model call, and a
 * natural-language edit reshapes the view and, on accept, writes the appears predicate back into
 * the source. Opt-in (needs Maven + the TomEE stack): run with {@code SKY_E2E=1}.
 */
@EnabledIfEnvironmentVariable(named = "SKY_E2E", matches = "1")
class PreviewSessionE2ETest {

    private static final String SHOP_VIEW = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog {
              all() -> [Product]  intent "Every product."
              restock(id Int, units Int) -> Product  intent "Increase stock."
            }
            view ProductList at "/products" {
              shows  Catalog.all() as a table of (name, stock)
              action "Restock" on row -> Catalog.restock(row.id, ask Int)
            }
            """;

    // The synthesized markup carries styleClass="compact", so an `appears rows is compact` edit verifies.
    private static final String VIEW_REPLY = """
            ```xhtml
            <h:form id="f">
              <h:dataTable id="products" styleClass="compact" value="#{productListBean.rows}" var="row">
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
                public String restock(long id) { catalog.restock(id, 1); return null; }
            }
            ```
            """;

    private static final String ALL_BODY =
            "return java.util.List.of(new Product(1L, \"Notebook\", 5L), new Product(2L, \"Pen\", 12L));";
    private static final String RESTOCK_BODY = "return new Product(id, \"Item\", units);";
    private static final Verifier ALWAYS_PASS = dir -> VerificationResult.pass();

    /** Serves method bodies, view markup, and — for an edit — an appears predicate. */
    private static StubLlm studioStub() {
        return new StubLlm((system, user) -> {
            if (system.contains("translate a natural-language")) {
                return "appears rows is compact";
            }
            if (system.contains("UI-synthesis")) {
                return VIEW_REPLY;
            }
            return user.contains("all(") ? ALL_BODY : RESTOCK_BODY;
        });
    }

    private static Ast.Module checked() {
        Ast.Module module = Parsing.parse(SHOP_VIEW, "shop.sky");
        new TypeChecker().check(module);
        return module;
    }

    @Test
    void servesFrozenViewWithoutModelCalls(@TempDir Path root) throws Exception {
        Ast.Module module = checked();
        Path sky = root.resolve("shop.sky");
        Files.writeString(sky, SHOP_VIEW);
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        // Freeze the view first.
        new Pipeline(studioStub(), ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        // Open with a model that fails if called — a frozen view must need none.
        Llm forbidden = (system, user) -> {
            throw new AssertionError("the model was called for a frozen view");
        };
        try (PreviewSession session = new PreviewSession(forbidden)) {
            PreviewSession.Handle handle = session.open(module, sky, lock, buildDir, 0, "mvn", quiet(), quiet());
            HttpResponse<String> view = get("http://localhost:" + handle.appPort() + "/app/ProductList.xhtml");
            assertEquals(200, view.statusCode(), () -> view.body());
            assertTrue(view.body().contains("Notebook"), () -> view.body());
        }
    }

    @Test
    void editThenAcceptWritesAppearsBack(@TempDir Path root) throws Exception {
        Ast.Module module = checked();
        Path sky = root.resolve("shop.sky");
        Files.writeString(sky, SHOP_VIEW);
        Path lock = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");

        new Pipeline(studioStub(), ALWAYS_PASS).build(module, lock, buildDir, quiet(), quiet());

        try (PreviewSession session = new PreviewSession(studioStub())) {
            session.open(module, sky, lock, buildDir, 0, "mvn", quiet(), quiet());

            EditResult edit = session.edit("ProductList", "make the rows compact");
            assertTrue(edit.ok(), edit.message());
            assertFalse(Files.readString(sky).contains("appears rows is compact"),
                    "an unaccepted edit must not touch the source");

            EditResult accept = session.accept();
            assertTrue(accept.ok(), accept.message());
            assertTrue(Files.readString(sky).contains("appears rows is compact"),
                    "accept should write the appears predicate back into the source");
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
