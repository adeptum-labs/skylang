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

            Path test = buildDir.resolve("src/test/java").resolve(pkg);
            Files.createDirectories(test);
            Files.writeString(test.resolve("ViewHarness.java"), VIEW_HARNESS.formatted(pkg));
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
            methods.append("        String html = render(\"").append(view.name()).append(".xhtml\");\n");
            methods.append("        Document doc = Jsoup.parse(html);\n");
            methods.append("        assertNotNull(doc.selectFirst(\"table\"), () -> \"view ")
                    .append(view.name()).append(" did not render a table:\\n\" + html);\n");
            for (Ast.Action action : view.actions()) {
                methods.append("        assertTrue(html.contains(\"").append(escape(action.label()))
                        .append("\"), \"action \\\"").append(escape(action.label())).append("\\\" should render\");\n");
            }
            for (Ast.Appears a : view.appears()) {
                if (a instanceof Ast.AppearsPlacement p) {
                    methods.append("        assertTrue(doc.select(\".").append(escape(p.region()))
                            .append("\").stream().anyMatch(e -> e.html().contains(\"").append(escape(p.label()))
                            .append("\")), \"\\\"").append(escape(p.label())).append("\\\" should render in region ")
                            .append(escape(p.region())).append("\");\n");
                } else if (a instanceof Ast.AppearsStyle s) {
                    methods.append("        assertFalse(doc.select(\"table.").append(escape(s.value()))
                            .append("\").isEmpty(), \"the table should render with style ")
                            .append(escape(s.value())).append("\");\n");
                }
            }
            methods.append("    }\n");
        }
        return RENDER_TEST.formatted(pkg, methods.toString());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Generate the interaction lane: each view is driven in a real (headless) browser — it must render
     * a table, and its first action, when clicked, must not error the JSF postback. Opt-in via SKY_UI,
     * so it stays out of the default build.
     */
    private static String interactionTest(String pkg, Ast.Module module) {
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
            methods.append("                for (WebElement input : driver.findElements(By.cssSelector(\"input[type='text']\"))) {\n");
            methods.append("                    input.sendKeys(\"1\");\n                }\n");
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

    /** Wrap the synthesized fragment in a full Facelets page with the standard component namespaces. */
    private static String page(Ast.View view, String markup) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html"
                      xmlns:f="jakarta.faces.core">
                <h:head><title>%s</title></h:head>
                <h:body>
                %s
                </h:body>
                </html>
                """.formatted(view.name(), markup.strip());
    }

    /** Ensure the backing bean carries the module's package declaration. */
    private static String bean(String pkg, String beanSource) {
        String source = beanSource.strip();
        return source.startsWith("package ") ? source + "\n" : "package " + pkg + ";\n\n" + source + "\n";
    }
}
