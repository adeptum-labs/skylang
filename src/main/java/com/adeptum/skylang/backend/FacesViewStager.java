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

package com.adeptum.skylang.backend;

import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.UiPromptBuilder.UiArtifact;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Materializes the web layer of a staged project: each view becomes a Facelets page under
 * {@code src/main/webapp} plus its backing bean under {@code src/main/java}, alongside the
 * descriptors an embedded Jakarta Faces deployment needs. Composed beside {@link ProjectStager},
 * which supplies the entities, services, and web POM.
 */
public final class FacesViewStager {

    /** When true, served pages carry the studio's in-page selection script (preview only). */
    private final boolean preview;

    public FacesViewStager() {
        this(false);
    }

    public FacesViewStager(boolean preview) {
        this.preview = preview;
    }

    private static final String WEB_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
                     version="6.0">
              <servlet>
                <servlet-name>Faces Servlet</servlet-name>
                <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                <load-on-startup>1</load-on-startup>
              </servlet>
              <servlet-mapping>
                <servlet-name>Faces Servlet</servlet-name>
                <url-pattern>*.xhtml</url-pattern>
              </servlet-mapping>
            </web-app>
            """;

    private static final String BEANS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
                   bean-discovery-mode="all" version="4.0"/>
            """;

    /**
     * @param views     map from view name to its synthesized markup and backing bean
     * @param baselines map from view name to its frozen visual baseline (PNG); a view without one is
     *                  captured by the staged visual gate instead of compared
     */
    public void stage(Ast.Module module, Map<String, UiArtifact> views, Map<String, byte[]> baselines,
                      Path buildDir) {
        String pkg = module.name();
        Path webapp = buildDir.resolve("src/main/webapp");
        Path webInf = webapp.resolve("WEB-INF");
        Path java = buildDir.resolve("src/main/java").resolve(pkg);
        Path visual = buildDir.resolve("src/test/resources/sky-visual");
        try {
            Files.createDirectories(webInf);
            Files.createDirectories(java);
            Files.createDirectories(visual);
            Files.writeString(webInf.resolve("web.xml"), WEB_XML);
            Files.writeString(webInf.resolve("beans.xml"), BEANS_XML);

            for (Ast.View view : module.views()) {
                UiArtifact artifact = views.get(view.name());
                if (artifact == null) {
                    throw new IllegalStateException("no synthesized artifact for view " + view.name());
                }
                Files.writeString(webapp.resolve(view.name() + ".xhtml"), page(view, artifact.markup()));
                Files.writeString(java.resolve(view.name() + "Bean.java"), bean(pkg, artifact.bean()));

                // Stage the frozen baseline for the visual gate; drop a stale one left by an earlier
                // staging so a re-synthesized view is recaptured instead of compared against its past.
                byte[] baseline = baselines.get(view.name());
                if (baseline != null) {
                    Files.write(visual.resolve(view.name() + ".png"), baseline);
                } else {
                    Files.deleteIfExists(visual.resolve(view.name() + ".png"));
                }
            }

            for (String converter : convertersNeeded(module)) {
                String source = switch (converter) {
                    case "Money" -> MONEY_CONVERTER;
                    case "Date" -> DATE_CONVERTER;
                    case "DateTime" -> DATETIME_CONVERTER;
                    default -> INSTANT_CONVERTER;
                };
                Files.writeString(java.resolve(converter + "Converter.java"), source.formatted(pkg));
            }

            Path test = buildDir.resolve("src/test/java").resolve(pkg);
            Files.createDirectories(test);
            Files.writeString(test.resolve("ViewHarness.java"), VIEW_HARNESS.formatted(pkg));
            Files.writeString(test.resolve("VisualGate.java"), VISUAL_GATE.formatted(pkg));
            Files.writeString(test.resolve("ViewsRenderTest.java"), renderTest(pkg, module));
            Files.writeString(test.resolve("ViewsInteractionTest.java"), interactionTest(pkg, module));
            Files.writeString(test.resolve("PreviewServer.java"), PREVIEW_SERVER.formatted(pkg));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage web layer under " + buildDir, e);
        }
    }

    /**
     * Generate the staged project's render test: each view is deployed to an embedded TomEE, fetched
     * over HTTP, and checked against its expectations. A render error (e.g. a bad EL binding) surfaces
     * as a non-200 response, so the view is disposed exactly like a method body that fails its tests.
     */
    private static String renderTest(String pkg, Ast.Module module) {
        StringBuilder methods = new StringBuilder();
        for (Ast.View view : module.views()) {
            String lower = Character.toLowerCase(view.name().charAt(0)) + view.name().substring(1);
            methods.append("\n    @Test\n    void ").append(lower).append("Renders() throws Exception {\n");
            boolean isTable = view.shows().projection()
                    .map(Ast.Projection::kind).filter("table"::equals).isPresent();
            methods.append("        String html = render(\"").append(view.name()).append(".xhtml\");\n");
            methods.append("        Document doc = Jsoup.parse(html);\n");
            if (isTable) {
                methods.append("        assertNotNull(doc.selectFirst(\"table\"), () -> \"view ")
                        .append(view.name()).append(" did not render a table:\\n\" + html);\n");
            } else {
                // A summary or form has no table; just require that it rendered a body.
                methods.append("        assertFalse(doc.body().children().isEmpty(), () -> \"view ")
                        .append(view.name()).append(" rendered nothing:\\n\" + html);\n");
            }
            for (Ast.Action action : view.actions()) {
                methods.append("        assertTrue(html.contains(\"").append(escape(action.label()))
                        .append("\"), \"action \\\"").append(escape(action.label())).append("\\\" should render\");\n");
            }
            for (String image : com.adeptum.skylang.verify.ViewVerifier.bytesColumns(module, view)) {
                // The id-based handle is state-independent: an absent Maybe<Bytes> still renders the img.
                methods.append("        assertFalse(doc.select(\"img[id$=").append(escape(image))
                        .append("Image]\").isEmpty(), \"field ").append(escape(image))
                        .append(" should render as an image\");\n");
            }
            for (Ast.Appears a : view.appears()) {
                if (a instanceof Ast.AppearsPlacement p) {
                    methods.append("        assertTrue(doc.select(\".").append(escape(p.region()))
                            .append("\").stream().anyMatch(e -> e.html().contains(\"").append(escape(p.label()))
                            .append("\")), \"\\\"").append(escape(p.label())).append("\\\" should render in region ")
                            .append(escape(p.region())).append("\");\n");
                } else if (a instanceof Ast.AppearsStyle s && isTable) {
                    methods.append("        assertFalse(doc.select(\"table.").append(escape(s.value()))
                            .append("\").isEmpty(), \"the table should render with style ")
                            .append(escape(s.value())).append("\");\n");
                }
            }
            methods.append("        VisualGate.check(\"").append(view.name()).append("\", html);\n");
            methods.append("    }\n");
        }
        return RENDER_TEST.formatted(pkg, methods.toString());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Which prompted input types need a staged Faces converter: Money and/or Instant. */
    private static java.util.Set<String> convertersNeeded(Ast.Module module) {
        Map<String, Ast.TypeDecl> types = Lowering.typesOf(module);
        java.util.Set<String> needed = new java.util.LinkedHashSet<>();
        for (Ast.View v : module.views()) {
            for (Ast.Action a : v.actions()) {
                for (Ast.ActionArg arg : a.args()) {
                    if (arg instanceof Ast.AskArg ask) {
                        String erased = erasedName(ask.type(), types);
                        if (erased.equals("Money") || erased.equals("Instant")
                                || erased.equals("Date") || erased.equals("DateTime")) {
                            needed.add(erased);
                        }
                    }
                }
            }
        }
        return needed;
    }

    /** The base type name a prompted type erases to, resolving declared refined types. */
    private static String erasedName(Ast.Type type, Map<String, Ast.TypeDecl> types) {
        return switch (type) {
            case Ast.TypeRef ref -> {
                Ast.TypeDecl d = types.get(ref.name());
                yield d != null ? d.base() : ref.name();
            }
            case Ast.RangedType r -> r.base();
            case Ast.GenericType g -> g.name();
        };
    }

    /** A form value that satisfies the prompted type, for driving inputs in the interaction lane. */
    private static String sample(Ast.Type type, Map<String, Ast.TypeDecl> types) {
        if (type instanceof Ast.RangedType r && r.base().equals("Int") && r.lo().isPresent()) {
            return Long.toString(Math.max(r.lo().getAsLong(), 1));
        }
        if (type instanceof Ast.TypeRef ref && types.get(ref.name()) != null
                && types.get(ref.name()).refinement() instanceof Ast.Range range
                && types.get(ref.name()).base().equals("Int") && range.lo().isPresent()) {
            return Long.toString(Math.max(range.lo().getAsLong(), 1));
        }
        return switch (erasedName(type, types)) {
            case "Money" -> "9.99 EUR";
            case "Instant" -> "2026-01-01T00:00:00Z";
            case "Date" -> "2026-01-01";
            case "DateTime" -> "2026-01-01T00:00:00";
            case "Email" -> "a@example.com";
            case "Currency" -> "EUR";
            case "Text" -> "sample";
            default -> "1";
        };
    }

    /**
     * Generate the interaction lane: each view is driven in a real (headless) browser — it must render
     * a table, and its first action, when clicked, must not error the JSF postback. Opt-in via SKY_UI,
     * so it stays out of the default build.
     */
    private static String interactionTest(String pkg, Ast.Module module) {
        Map<String, Ast.TypeDecl> types = Lowering.typesOf(module);
        StringBuilder methods = new StringBuilder();
        for (Ast.View view : module.views()) {
            String lower = Character.toLowerCase(view.name().charAt(0)) + view.name().substring(1);
            methods.append("\n    @Test\n    void ").append(lower).append("Interacts() throws Exception {\n");
            methods.append("        int port = ViewHarness.freePort();\n");
            methods.append("        Configuration configuration = new Configuration();\n");
            methods.append("        configuration.setHttpPort(port);\n");
            methods.append("        try (Container container = new Container(configuration)) {\n");
            methods.append("            container.deploy(\"/app\", ViewHarness.webapp().toFile());\n");
            methods.append("            HtmlUnitDriver driver = new HtmlUnitDriver(true);\n");
            methods.append("            try {\n");
            methods.append("                driver.get(\"http://localhost:\" + port + \"/app/")
                    .append(view.name()).append(".xhtml\");\n");
            methods.append("                assertNotNull(driver.findElement(By.tagName(\"table\")), \"view ")
                    .append(view.name()).append(" should render a table in a real browser\");\n");
            // Text inputs are the clicked action's prompted arguments, in order; each gets a
            // value that satisfies its declared type so converters and validators accept it.
            methods.append("                String[] samples = {").append(askSamples(view, types)).append("};\n");
            methods.append("                java.util.List<WebElement> inputs = driver.findElements(By.cssSelector(\"input[type='text']\"));\n");
            methods.append("                for (int i = 0; i < inputs.size(); i++) {\n");
            methods.append("                    inputs.get(i).sendKeys(i < samples.length ? samples[i] : \"1\");\n");
            methods.append("                }\n");
            if (!view.actions().isEmpty()) {
                String label = escape(view.actions().get(0).label());
                methods.append("                driver.findElement(By.xpath(\"(//input[@value='").append(label)
                        .append("'] | //button[normalize-space()='").append(label).append("'])[1]\")).click();\n");
                methods.append("                String page = driver.getPageSource().toLowerCase();\n");
                methods.append("                assertFalse(page.contains(\"http status 5\") || page.contains(\"exception\"), \"action \\\"")
                        .append(label).append("\\\" errored on click\");\n");
            }
            methods.append("            } finally {\n                driver.quit();\n            }\n");
            methods.append("        }\n    }\n");
        }
        return INTERACTION_TEST.formatted(pkg, methods.toString());
    }

    /**
     * The visual-freeze gate. The rendered HTML is rasterized by a version-pinned, pure-Java renderer
     * onto a fixed canvas — no browser, no network — and compared against the frozen baseline by
     * structural similarity (SSIM). Hidden inputs and scripts are stripped first so per-boot noise
     * like the Faces view state can never leak into the pixels. Without a baseline the rasterization
     * is captured to {@code target/sky-visual} for the compiler to freeze into {@code sky.lock}.
     */
    private static final String VISUAL_GATE = """
            package %s;

            import org.jsoup.Jsoup;
            import org.jsoup.nodes.Document;
            import org.xhtmlrenderer.simple.Graphics2DRenderer;

            import javax.imageio.ImageIO;
            import java.awt.image.BufferedImage;
            import java.nio.file.Files;
            import java.nio.file.Path;

            import static org.junit.jupiter.api.Assertions.assertTrue;

            /** Rasterizes a rendered view with the pinned renderer and diffs it against its frozen look. */
            final class VisualGate {

                private static final double THRESHOLD = 0.97;
                private static final int WIDTH = 1024;
                private static final int HEIGHT = 768;

                private VisualGate() {
                }

                static void check(String view, String html) throws Exception {
                    BufferedImage actual = rasterize(html);
                    Path capture = Path.of("target/sky-visual", view + ".png");
                    Files.createDirectories(capture.getParent());
                    ImageIO.write(actual, "png", capture.toFile());

                    Path frozen = Path.of("src/test/resources/sky-visual", view + ".png");
                    if (!Files.exists(frozen)) {
                        System.out.println("visual gate: no baseline for " + view + " yet — captured " + capture);
                        return;
                    }
                    BufferedImage baseline = ImageIO.read(frozen.toFile());
                    double score = ssim(baseline, actual);
                    assertTrue(score >= THRESHOLD, () -> "view " + view + " drifted from its frozen look"
                            + " (ssim " + score + " < " + THRESHOLD + "); compare " + frozen + " with " + capture);
                }

                private static BufferedImage rasterize(String html) throws Exception {
                    Document doc = Jsoup.parse(html);
                    doc.select("input[type=hidden], script").remove();
                    doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
                    Path page = Files.createTempFile("sky-visual", ".xhtml");
                    try {
                        Files.writeString(page, doc.outerHtml());
                        return Graphics2DRenderer.renderToImage(page.toUri().toString(), WIDTH, HEIGHT);
                    } finally {
                        Files.deleteIfExists(page);
                    }
                }

                /** Mean SSIM over 8x8 windows of the grayscale images; 1.0 = identical, below ~0.97 = drift. */
                private static double ssim(BufferedImage a, BufferedImage b) {
                    if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                        return 0;
                    }
                    double[] ga = gray(a);
                    double[] gb = gray(b);
                    int w = a.getWidth(), h = a.getHeight(), window = 8, n = window * window;
                    double c1 = 6.5025, c2 = 58.5225, total = 0;
                    int windows = 0;
                    for (int y = 0; y + window <= h; y += window) {
                        for (int x = 0; x + window <= w; x += window) {
                            double ma = 0, mb = 0;
                            for (int j = 0; j < window; j++) {
                                for (int i = 0; i < window; i++) {
                                    ma += ga[(y + j) * w + x + i];
                                    mb += gb[(y + j) * w + x + i];
                                }
                            }
                            ma /= n;
                            mb /= n;
                            double va = 0, vb = 0, cov = 0;
                            for (int j = 0; j < window; j++) {
                                for (int i = 0; i < window; i++) {
                                    double da = ga[(y + j) * w + x + i] - ma;
                                    double db = gb[(y + j) * w + x + i] - mb;
                                    va += da * da;
                                    vb += db * db;
                                    cov += da * db;
                                }
                            }
                            va /= n - 1;
                            vb /= n - 1;
                            cov /= n - 1;
                            total += ((2 * ma * mb + c1) * (2 * cov + c2))
                                    / ((ma * ma + mb * mb + c1) * (va + vb + c2));
                            windows++;
                        }
                    }
                    return windows == 0 ? 0 : total / windows;
                }

                private static double[] gray(BufferedImage img) {
                    int w = img.getWidth(), h = img.getHeight();
                    double[] out = new double[w * h];
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int rgb = img.getRGB(x, y);
                            out[y * w + x] = 0.299 * ((rgb >> 16) & 0xff)
                                    + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                        }
                    }
                    return out;
                }
            }
            """;

    /** Shared plumbing for everything that boots the staged webapp: tests, gates, the preview server. */
    private static final String VIEW_HARNESS = """
            package %s;

            import java.io.IOException;
            import java.io.UncheckedIOException;
            import java.net.ServerSocket;
            import java.nio.file.Files;
            import java.nio.file.Path;

            import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

            /** Explodes the staged webapp and hands out ports for an embedded-container deployment. */
            final class ViewHarness {

                private ViewHarness() {
                }

                static Path webapp() throws IOException {
                    Path webapp = Files.createTempDirectory("sky-webapp").resolve("app");
                    Files.createDirectories(webapp);
                    copyTree(Path.of("src/main/webapp"), webapp);
                    copyTree(Path.of("target/classes"), webapp.resolve("WEB-INF/classes"));
                    return webapp;
                }

                static int freePort() throws IOException {
                    try (ServerSocket probe = new ServerSocket(0)) {
                        return probe.getLocalPort();
                    }
                }

                private static void copyTree(Path src, Path dest) throws IOException {
                    if (!Files.exists(src)) {
                        return;
                    }
                    try (var walk = Files.walk(src)) {
                        walk.forEach(p -> {
                            try {
                                Path target = dest.resolve(src.relativize(p).toString());
                                if (Files.isDirectory(p)) {
                                    Files.createDirectories(target);
                                } else {
                                    Files.createDirectories(target.getParent());
                                    Files.copy(p, target, REPLACE_EXISTING);
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                    }
                }
            }
            """;

    private static final String INTERACTION_TEST = """
            package %s;

            import org.apache.tomee.embedded.Configuration;
            import org.apache.tomee.embedded.Container;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
            import org.openqa.selenium.By;
            import org.openqa.selenium.WebElement;
            import org.openqa.selenium.htmlunit.HtmlUnitDriver;

            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertNotNull;

            /** Drives each view in a real (headless) browser and clicks its actions. Opt-in: run with SKY_UI=1. */
            @EnabledIfEnvironmentVariable(named = "SKY_UI", matches = "1")
            class ViewsInteractionTest {
            %s}
            """;

    /** A long-lived embedded TomEE that serves every staged view at {@code /app/<View>.xhtml}. */
    private static final String PREVIEW_SERVER = """
            package %s;

            import org.apache.tomee.embedded.Configuration;
            import org.apache.tomee.embedded.Container;

            import java.util.concurrent.CountDownLatch;

            /** Long-lived embedded TomEE serving the staged views for `sky preview`. */
            public final class PreviewServer {

                public static void main(String[] args) throws Exception {
                    int port = ViewHarness.freePort();
                    Configuration configuration = new Configuration();
                    configuration.setHttpPort(port);
                    Container container = new Container(configuration);
                    container.deploy("/app", ViewHarness.webapp().toFile());
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            container.close();
                        } catch (Exception ignored) {
                        }
                    }));

                    System.out.println("PREVIEW READY app=" + port);
                    System.out.flush();
                    new CountDownLatch(1).await();
                }
            }
            """;

    private static final String RENDER_TEST = """
            package %s;

            import org.apache.tomee.embedded.Configuration;
            import org.apache.tomee.embedded.Container;
            import org.jsoup.Jsoup;
            import org.jsoup.nodes.Document;
            import org.junit.jupiter.api.Test;

            import java.net.URI;
            import java.net.http.HttpClient;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;

            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            /** Deploys each view to an embedded TomEE and verifies its rendered structure and look. */
            class ViewsRenderTest {
            %s
                private static String render(String view) throws Exception {
                    int port = ViewHarness.freePort();
                    Configuration configuration = new Configuration();
                    configuration.setHttpPort(port);
                    try (Container container = new Container(configuration)) {
                        container.deploy("/app", ViewHarness.webapp().toFile());
                        HttpResponse<String> response = HttpClient.newHttpClient().send(
                                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/app/" + view)).build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode(), () -> "render failed:\\n" + response.body());
                        return response.body();
                    }
                }
            }
            """;

    /** The sample values for a view's first action's prompted arguments, as a Java array body. */
    private static String askSamples(Ast.View view, Map<String, Ast.TypeDecl> types) {
        if (view.actions().isEmpty()) {
            return "";
        }
        return view.actions().get(0).args().stream()
                .filter(Ast.AskArg.class::isInstance)
                .map(a -> "\"" + escape(sample(((Ast.AskArg) a).type(), types)) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static final String MONEY_CONVERTER = """
            package %s;

            import jakarta.faces.component.UIComponent;
            import jakarta.faces.context.FacesContext;
            import jakarta.faces.convert.Converter;
            import jakarta.faces.convert.ConverterException;
            import jakarta.faces.convert.FacesConverter;

            /** Converts between "9.99 EUR" form input and the staged Money type. */
            @FacesConverter("sky.money")
            public class MoneyConverter implements Converter<Money> {

                @Override
                public Money getAsObject(FacesContext context, UIComponent component, String value) {
                    if (value == null || value.isBlank()) {
                        return null;
                    }
                    String text = value.strip();
                    if (text.length() < 4) {
                        throw new ConverterException("expected an amount with a currency, e.g. 9.99 EUR");
                    }
                    String amount = text.substring(0, text.length() - 3).strip();
                    String currency = text.substring(text.length() - 3).toUpperCase(java.util.Locale.ROOT);
                    try {
                        return Money.of(amount, currency);
                    } catch (RuntimeException e) {
                        throw new ConverterException("expected an amount with a currency, e.g. 9.99 EUR", e);
                    }
                }

                @Override
                public String getAsString(FacesContext context, UIComponent component, Money value) {
                    return value == null ? "" : value.amount().toPlainString() + " " + value.currency().getCurrencyCode();
                }
            }
            """;

    private static final String INSTANT_CONVERTER = """
            package %s;

            import jakarta.faces.component.UIComponent;
            import jakarta.faces.context.FacesContext;
            import jakarta.faces.convert.Converter;
            import jakarta.faces.convert.ConverterException;
            import jakarta.faces.convert.FacesConverter;

            import java.time.Instant;
            import java.time.format.DateTimeParseException;

            /** Converts between ISO-8601 form input (2026-01-01T00:00:00Z) and java.time.Instant. */
            @FacesConverter("sky.instant")
            public class InstantConverter implements Converter<Instant> {

                @Override
                public Instant getAsObject(FacesContext context, UIComponent component, String value) {
                    if (value == null || value.isBlank()) {
                        return null;
                    }
                    try {
                        return Instant.parse(value.strip());
                    } catch (DateTimeParseException e) {
                        throw new ConverterException("expected an ISO-8601 instant, e.g. 2026-01-01T00:00:00Z", e);
                    }
                }

                @Override
                public String getAsString(FacesContext context, UIComponent component, Instant value) {
                    return value == null ? "" : value.toString();
                }
            }
            """;

    private static final String DATE_CONVERTER = """
            package %s;

            import jakarta.faces.component.UIComponent;
            import jakarta.faces.context.FacesContext;
            import jakarta.faces.convert.Converter;
            import jakarta.faces.convert.ConverterException;
            import jakarta.faces.convert.FacesConverter;

            import java.time.LocalDate;
            import java.time.format.DateTimeParseException;

            /** Converts between ISO form input (2026-01-01) and java.time.LocalDate. */
            @FacesConverter("sky.date")
            public class DateConverter implements Converter<LocalDate> {

                @Override
                public LocalDate getAsObject(FacesContext context, UIComponent component, String value) {
                    if (value == null || value.isBlank()) {
                        return null;
                    }
                    try {
                        return LocalDate.parse(value.strip());
                    } catch (DateTimeParseException e) {
                        throw new ConverterException("expected an ISO date, e.g. 2026-01-01", e);
                    }
                }

                @Override
                public String getAsString(FacesContext context, UIComponent component, LocalDate value) {
                    return value == null ? "" : value.toString();
                }
            }
            """;

    private static final String DATETIME_CONVERTER = """
            package %s;

            import jakarta.faces.component.UIComponent;
            import jakarta.faces.context.FacesContext;
            import jakarta.faces.convert.Converter;
            import jakarta.faces.convert.ConverterException;
            import jakarta.faces.convert.FacesConverter;

            import java.time.LocalDateTime;
            import java.time.format.DateTimeParseException;

            /** Converts between ISO form input (2026-01-01T00:00:00) and java.time.LocalDateTime. */
            @FacesConverter("sky.datetime")
            public class DateTimeConverter implements Converter<LocalDateTime> {

                @Override
                public LocalDateTime getAsObject(FacesContext context, UIComponent component, String value) {
                    if (value == null || value.isBlank()) {
                        return null;
                    }
                    try {
                        return LocalDateTime.parse(value.strip());
                    } catch (DateTimeParseException e) {
                        throw new ConverterException("expected an ISO date-time, e.g. 2026-01-01T00:00:00", e);
                    }
                }

                @Override
                public String getAsString(FacesContext context, UIComponent component, LocalDateTime value) {
                    return value == null ? "" : value.toString();
                }
            }
            """;

    /** Wrap the synthesized fragment in a full Facelets page with the standard component namespaces. */
    private String page(Ast.View view, String markup) {
        String selection = preview ? "\n" + selectionScript(view.name()) : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html"
                      xmlns:f="jakarta.faces.core">
                <h:head><title>%s</title></h:head>
                <h:body>
                %s%s
                </h:body>
                </html>
                """.formatted(view.name(), markup.strip(), selection);
    }

    /**
     * The in-page selection script the preview studio injects: on click it reports the clicked
     * control (a command's rendered label) or column header to the parent studio via postMessage,
     * so the studio can highlight the matching panel knob. CDATA-wrapped to stay valid Facelets XML.
     */
    static String selectionScript(String viewName) {
        return """
                <script>//<![CDATA[
                (function(){
                  var VIEW = "%s";
                  document.addEventListener('click', function(e){
                    var btn = e.target.closest('button, a, input[type=submit], input[type=button]');
                    if (btn) {
                      var label = (btn.value || btn.textContent || '').trim();
                      if (label) {
                        e.preventDefault(); e.stopPropagation();
                        window.parent.postMessage({ type: 'sky.select', view: VIEW, control: label }, '*');
                        return;
                      }
                    }
                    var th = e.target.closest('th');
                    if (th) {
                      var text = (th.textContent || '').trim();
                      if (text) window.parent.postMessage({ type: 'sky.select', view: VIEW, field: text }, '*');
                    }
                  }, true);
                })();
                //]]></script>""".formatted(viewName);
    }

    /** Ensure the backing bean carries the module's package declaration. */
    private static String bean(String pkg, String beanSource) {
        String source = beanSource.strip();
        return source.startsWith("package ") ? source + "\n" : "package " + pkg + ";\n\n" + source + "\n";
    }
}
