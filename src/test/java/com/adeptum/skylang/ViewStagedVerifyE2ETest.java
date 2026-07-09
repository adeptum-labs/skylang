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
import com.adeptum.skylang.verify.MavenVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end proof that a synthesized view actually deploys and renders: the project is staged as a
 * Jakarta EE web app and the REAL Maven verifier runs its generated render test, which boots an
 * embedded TomEE and fetches the view. Opt-in (needs Maven on PATH, JDK 17+, and the TomEE stack):
 * run with {@code SKY_E2E=1}. Excluded from the normal test run.
 */
@EnabledIfEnvironmentVariable(named = "SKY_E2E", matches = "1")
class ViewStagedVerifyE2ETest {

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
              expect table has columns (name, stock)
              expect action "Restock" is button
            }
            """;

    private static final String ALL_BODY =
            "return java.util.List.of(new Product(1L, \"Notebook\", 5L), new Product(2L, \"Pen\", 12L));";
    private static final String RESTOCK_BODY = "return new Product(id, \"Item\", units);";

    private static final String VIEW_REPLY = """
            ```xhtml
            <h:form id="f">
              <h:dataTable id="products" value="#{productListBean.rows}" var="row">
                <h:column id="c_name"><f:facet name="header"><h:outputText value="Name"/></f:facet><h:outputText value="#{row.name}"/></h:column>
                <h:column id="c_stock"><f:facet name="header"><h:outputText value="Stock"/></f:facet><h:outputText value="#{row.stock}"/></h:column>
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

                public java.util.List<Product> getRows() {
                    return catalog.all();
                }

                public String restock(long id) {
                    catalog.restock(id, 1);
                    return null;
                }
            }
            ```
            """;

    private static StubLlm stub() {
        return new StubLlm((system, user) -> {
            if (system.contains("UI-synthesis")) {
                return VIEW_REPLY;
            }
            return user.contains("all(") ? ALL_BODY : RESTOCK_BODY;
        });
    }

    @Test
    void generatedViewRendersInContainer(@TempDir Path root) {
        Ast.Module module = Parsing.parse(SHOP_VIEW, "shop.sky");
        new TypeChecker().check(module);

        var out = new ByteArrayOutputStream();
        int code = new Pipeline(stub(), new MavenVerifier())
                .build(module, root.resolve("sky.lock"), root.resolve("build/jvm-jakarta"),
                        new PrintStream(out), new PrintStream(out));

        assertEquals(0, code, () -> "staged render verification failed:\n" + out.toString(StandardCharsets.UTF_8));
    }
}
