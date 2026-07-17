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

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A service's declared @scope decides the CDI lifecycle annotation on its staged bean. */
class ProjectStagerScopeTest {

    private static Ast.Module module(String scopeLine) {
        return Parsing.parse("""
                module shop
                entity Product { id Int  name Text }
                %s
                service Catalog {
                  all() -> [Product]  intent "Every product."
                }
                """.formatted(scopeLine), "shop.sky");
    }

    private static String staged(Path root, String scopeLine) throws IOException {
        new ProjectStager().stage(module(scopeLine),
                Map.of("shop.Catalog.all", "return java.util.List.of();"), root);
        return Files.readString(root.resolve("src/main/java/shop/Catalog.java"));
    }

    @Test
    void defaultScopeStaysApplicationScoped(@TempDir Path root) throws IOException {
        String service = staged(root, "");
        assertTrue(service.contains("@jakarta.enterprise.context.ApplicationScoped"), service);
        assertFalse(service.contains("java.io.Serializable"), service);
        assertFalse(Files.readString(root.resolve("pom.xml")).contains("payara-api"),
                "an unscoped module must not depend on payara-api");
    }

    @Test
    void requestScopeStagesARequestBean(@TempDir Path root) throws IOException {
        String service = staged(root, "@scope(request)");
        assertTrue(service.contains("@jakarta.enterprise.context.RequestScoped"), service);
        assertFalse(service.contains("java.io.Serializable"), service);
    }

    @Test
    void sessionScopeStagesAPassivationCapableBean(@TempDir Path root) throws IOException {
        String service = staged(root, "@scope(session)");
        assertTrue(service.contains("@jakarta.enterprise.context.SessionScoped"), service);
        assertTrue(service.contains("implements java.io.Serializable"), service);
    }

    @Test
    void clusterScopeStagesAPayaraClusteredSingleton(@TempDir Path root) throws IOException {
        String service = staged(root, "@scope(cluster)");
        assertTrue(service.contains("@fish.payara.cluster.Clustered"), service);
        assertTrue(service.contains("@jakarta.enterprise.context.ApplicationScoped"), service);
        assertTrue(service.contains("implements java.io.Serializable"), service);
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("payara-api"),
                "a clustered module needs the payara-api dependency to compile");
    }
}
