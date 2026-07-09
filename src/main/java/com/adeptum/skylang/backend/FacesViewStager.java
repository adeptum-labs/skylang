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
     * @param views map from view name to its synthesized markup and backing bean
     */
    public void stage(Ast.Module module, Map<String, UiArtifact> views, Path buildDir) {
        String pkg = module.name();
        Path webapp = buildDir.resolve("src/main/webapp");
        Path webInf = webapp.resolve("WEB-INF");
        Path java = buildDir.resolve("src/main/java").resolve(pkg);
        try {
            Files.createDirectories(webInf);
            Files.createDirectories(java);
            Files.writeString(webInf.resolve("web.xml"), WEB_XML);
            Files.writeString(webInf.resolve("beans.xml"), BEANS_XML);

            for (Ast.View view : module.views()) {
                UiArtifact artifact = views.get(view.name());
                if (artifact == null) {
                    throw new IllegalStateException("no synthesized artifact for view " + view.name());
                }
                Files.writeString(webapp.resolve(view.name() + ".xhtml"), page(view, artifact.markup()));
                Files.writeString(java.resolve(view.name() + "Bean.java"), bean(pkg, artifact.bean()));
            }

            Path test = buildDir.resolve("src/test/java").resolve(pkg);
            Files.createDirectories(test);
            Files.writeString(test.resolve("ViewsRenderTest.java"), renderTest(pkg, module));
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

    /** A long-lived embedded TomEE that serves every staged view at {@code /app/<View>.xhtml}. */
    private static final String PREVIEW_SERVER = """
            package %s;

            import org.apache.tomee.embedded.Configuration;
            import org.apache.tomee.embedded.Container;

            import java.io.IOException;
            import java.io.UncheckedIOException;
            import java.net.ServerSocket;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.util.concurrent.CountDownLatch;

            import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

            /** Long-lived embedded TomEE serving the staged views for `sky preview`. */
            public final class PreviewServer {

                public static void main(String[] args) throws Exception {
                    int port;
                    try (ServerSocket probe = new ServerSocket(0)) {
                        port = probe.getLocalPort();
                    }

                    Path webapp = Files.createTempDirectory("sky-preview").resolve("app");
                    Files.createDirectories(webapp);
                    copyTree(Path.of("src/main/webapp"), webapp);
                    copyTree(Path.of("target/classes"), webapp.resolve("WEB-INF/classes"));

                    Configuration configuration = new Configuration();
                    configuration.setHttpPort(port);
                    Container container = new Container(configuration);
                    container.deploy("/app", webapp.toFile());
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

    private static final String RENDER_TEST = """
            package %s;

            import org.apache.tomee.embedded.Configuration;
            import org.apache.tomee.embedded.Container;
            import org.jsoup.Jsoup;
            import org.jsoup.nodes.Document;
            import org.junit.jupiter.api.Test;

            import java.io.IOException;
            import java.io.UncheckedIOException;
            import java.net.ServerSocket;
            import java.net.URI;
            import java.net.http.HttpClient;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;
            import java.nio.file.Files;
            import java.nio.file.Path;

            import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            /** Deploys each view to an embedded TomEE and verifies its rendered structure — no pixels. */
            class ViewsRenderTest {
            %s
                private static String render(String view) throws Exception {
                    Path webapp = Files.createTempDirectory("sky-render").resolve("app");
                    Files.createDirectories(webapp);
                    copyTree(Path.of("src/main/webapp"), webapp);
                    copyTree(Path.of("target/classes"), webapp.resolve("WEB-INF/classes"));

                    int port;
                    try (ServerSocket probe = new ServerSocket(0)) {
                        port = probe.getLocalPort();
                    }
                    Configuration configuration = new Configuration();
                    configuration.setHttpPort(port);
                    try (Container container = new Container(configuration)) {
                        container.deploy("/app", webapp.toFile());
                        HttpResponse<String> response = HttpClient.newHttpClient().send(
                                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/app/" + view)).build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode(), () -> "render failed:\\n" + response.body());
                        return response.body();
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
