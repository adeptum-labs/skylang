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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the interaction lane: a synthesized view is staged and its generated
 * ViewsInteractionTest drives a real (headless) browser — rendering the view and clicking its action.
 * Opt-in (needs Maven + the TomEE/Selenium stack): run with {@code SKY_E2E=1}.
 */
@EnabledIfEnvironmentVariable(named = "SKY_E2E", matches = "1")
class ViewInteractionE2ETest {

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

    private static final String VIEW_REPLY = """
            ```xhtml
            <h:form id="f">
              <h:dataTable id="products" value="#{productListBean.rows}" var="row">
                <h:column id="c_name"><h:outputText value="#{row.name}"/></h:column>
                <h:column id="c_stock"><h:outputText value="#{row.stock}"/></h:column>
                <h:column id="c_act"><h:commandButton id="restock" value="Restock" action="#{productListBean.restock(row.id)}"/></h:column>
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

    private static StubLlm stub() {
        return new StubLlm((system, user) -> {
            if (system.contains("UI-synthesis")) {
                return VIEW_REPLY;
            }
            return user.contains("all(") ? ALL_BODY : RESTOCK_BODY;
        });
    }

    @Test
    void synthesizedViewSurvivesBrowserInteraction(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(SHOP_VIEW, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");

        // Stage the web project without running Maven.
        var out = new ByteArrayOutputStream();
        new Pipeline(stub(), ALWAYS_PASS).build(module, root.resolve("sky.lock"), buildDir,
                new PrintStream(out), new PrintStream(out));

        // Run the interaction lane in the staged project (SKY_UI enables the generated test).
        ProcessBuilder pb = new ProcessBuilder("mvn", "-B", "test", "-Dtest=ViewsInteractionTest")
                .directory(buildDir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("SKY_UI", "1");
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();

        assertEquals(0, exit, () -> "interaction lane failed:\n" + output);
        assertTrue(output.contains("ViewsInteractionTest"), () -> "the interaction test should have run:\n" + output);
    }
}
