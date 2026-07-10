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

import com.adeptum.skylang.backend.ProjectStager;
import com.adeptum.skylang.freeze.Lock;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.StubLlm;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.MavenVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the visual-freeze gate: the first verified build captures and freezes a
 * baseline of the rendered view, a recheck re-renders offline and passes the similarity gate, and
 * a baseline that no longer matches the rendered look fails the build. Opt-in (needs Maven + the
 * TomEE/rasterizer stack): run with {@code SKY_E2E=1}.
 */
@EnabledIfEnvironmentVariable(named = "SKY_E2E", matches = "1")
class VisualFreezeE2ETest {

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

    private static StubLlm stub() {
        return new StubLlm((system, user) -> {
            if (system.contains("UI-synthesis")) {
                return VIEW_REPLY;
            }
            return user.contains("all(") ? ALL_BODY : RESTOCK_BODY;
        });
    }

    @Test
    void baselineFreezesThenGatesLaterBuilds(@TempDir Path root) throws Exception {
        Ast.Module module = Parsing.parse(SHOP_VIEW, "shop.sky");
        new TypeChecker().check(module);
        Path lockPath = root.resolve("sky.lock");
        Path buildDir = root.resolve("build/jvm-jakarta");
        String viewKey = ProjectStager.viewKey("shop", "ProductList");

        // Build 1: synthesize, verify in-container, capture and freeze the visual baseline.
        var out = new ByteArrayOutputStream();
        int first = new Pipeline(stub(), new MavenVerifier())
                .build(module, lockPath, buildDir, new PrintStream(out), new PrintStream(out));
        assertEquals(0, first, () -> "first build failed:\n" + out);
        String visual = Lock.load(lockPath).getView(viewKey).orElseThrow().visual();
        assertFalse(visual.isEmpty(), "the first verified build should freeze a visual baseline");

        // Build 2: everything frozen; a recheck re-renders offline and the gate must hold.
        var recheckOut = new ByteArrayOutputStream();
        StubLlm offline = stub();
        int second = new Pipeline(offline, new MavenVerifier()).build(module, lockPath, buildDir,
                new PrintStream(recheckOut), new PrintStream(recheckOut), true);
        assertEquals(0, second, () -> "recheck against the frozen baseline failed:\n" + recheckOut);
        assertEquals(0, offline.calls(), "a recheck must not call the model");

        // A baseline that no longer matches the rendered look must fail the gate.
        Lock lock = Lock.load(lockPath);
        lock.putView(viewKey, lock.getView(viewKey).orElseThrow().withVisual(darkCanvas()));
        lock.save(lockPath);
        var driftOut = new ByteArrayOutputStream();
        int third = new Pipeline(stub(), new MavenVerifier()).build(module, lockPath, buildDir,
                new PrintStream(driftOut), new PrintStream(driftOut), true);
        assertEquals(1, third, "a drifted view must fail the visual gate");
        assertTrue(driftOut.toString().contains("drifted from its frozen look"),
                () -> "the failure should name the drift:\n" + driftOut);
    }

    /** A baseline that cannot match any rendered view: the gate's canvas painted solid dark. */
    private static String darkCanvas() throws Exception {
        BufferedImage image = new BufferedImage(1024, 768, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
