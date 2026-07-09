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
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage web layer under " + buildDir, e);
        }
    }

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
