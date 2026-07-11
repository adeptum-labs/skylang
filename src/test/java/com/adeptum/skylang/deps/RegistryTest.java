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

package com.adeptum.skylang.deps;

import com.adeptum.skylang.config.ConfigException;
import com.adeptum.skylang.config.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dependency registry is the only door: logical names resolve to pinned native
 * coordinates per profile, unknown names are refused at build time, and two names can never
 * drag conflicting versions of the same artifact into a build.
 */
class RegistryTest {

    private static List<Resolved> resolve(String... lines) {
        List<Manifest.Require> requires = java.util.Arrays.stream(lines)
                .map(l -> l.split("\\s+"))
                .map(p -> new Manifest.Require(p[0], p[1]))
                .toList();
        return Registry.forProfile("jvm-jakarta", Optional.empty()).resolve(requires);
    }

    @Test
    void resolvesTheBookExamplesToMavenCoordinates() {
        List<Resolved> resolved = resolve("bcrypt ^4.0", "http-client ~2.1", "json 2.1.3");
        Resolved bcrypt = resolved.get(0);
        assertEquals("bcrypt", bcrypt.name());
        assertTrue(bcrypt.coordinates().get(0).startsWith("org.mindrot:jbcrypt:"),
                "the JVM registry maps bcrypt onto jbcrypt: " + bcrypt);
        assertTrue(resolved.get(1).coordinates().get(0).contains("httpclient5"), resolved.toString());
        assertTrue(resolved.get(2).coordinates().get(0).contains("jackson-databind"),
                resolved.toString());
    }

    @Test
    void theSameRequiresResolvesToNpmPackagesUnderTsNode() {
        Registry ts = Registry.forProfile("ts-node", Optional.empty());
        List<Resolved> resolved = ts.resolve(List.of(new Manifest.Require("bcrypt", "^4.0")));
        assertTrue(resolved.get(0).coordinates().get(0).startsWith("bcryptjs:"),
                "the Node registry maps the same logical name onto npm: " + resolved);
    }

    @Test
    void anUnknownNameIsARefusalNamingTheValve() {
        ConfigException e = assertThrows(ConfigException.class, () -> resolve("left-pad ^1.0"));
        assertTrue(e.getMessage().contains("'left-pad' is not in the jvm-jakarta registry"),
                e.getMessage());
        assertTrue(e.getMessage().contains("sky.registry"), e.getMessage());
    }

    @Test
    void anUnsatisfiableConstraintNamesTheAvailableVersions() {
        ConfigException e = assertThrows(ConfigException.class, () -> resolve("bcrypt ^9.0"));
        assertTrue(e.getMessage().contains("no registered version of 'bcrypt'"), e.getMessage());
        assertTrue(e.getMessage().contains("^9.0"), e.getMessage());
    }

    @Test
    void aLocalSkyRegistryExtendsTheBundledOne(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("sky.registry"), """
                // reviewed like any other dependency change
                profile jvm-jakarta {
                  sodium 1.2.0 -> com.goterl:lazysodium-java:5.1.4 packages com.goterl
                }
                profile ts-node {
                  sodium 1.2.0 -> libsodium-wrappers:0.7.13 packages libsodium-wrappers
                }
                """);
        Registry registry = Registry.forProfile("jvm-jakarta", Optional.of(dir));
        List<Resolved> resolved = registry.resolve(List.of(new Manifest.Require("sodium", "^1.0")));
        assertEquals(List.of("com.goterl:lazysodium-java:5.1.4"), resolved.get(0).coordinates());
        assertEquals(List.of("com.goterl"), resolved.get(0).packages());
    }

    @Test
    void twoNamesDisagreeingOnAnArtifactIsABuildError(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("sky.registry"), """
                profile jvm-jakarta {
                  json-old 1.0.0 -> com.fasterxml.jackson.core:jackson-databind:2.10.0 packages com.fasterxml.jackson
                }
                """);
        Registry registry = Registry.forProfile("jvm-jakarta", Optional.of(dir));
        ConfigException e = assertThrows(ConfigException.class, () -> registry.resolve(List.of(
                new Manifest.Require("json", "2.1.3"),
                new Manifest.Require("json-old", "1.0.0"))));
        assertTrue(e.getMessage().contains("json"), e.getMessage());
        assertTrue(e.getMessage().contains("json-old"), e.getMessage());
        assertTrue(e.getMessage().contains("jackson-databind"), e.getMessage());
    }

    @Test
    void constraintsPickTheHighestMatchingVersion(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("sky.registry"), """
                profile jvm-jakarta {
                  demo 1.1.0 -> demo:demo:1.1.0 packages demo
                  demo 1.2.5 -> demo:demo:1.2.5 packages demo
                  demo 2.0.0 -> demo:demo:2.0.0 packages demo
                }
                """);
        Registry registry = Registry.forProfile("jvm-jakarta", Optional.of(dir));
        assertEquals("1.2.5", registry.resolve(List.of(new Manifest.Require("demo", "^1.0")))
                .get(0).version(), "caret picks the highest compatible release");
        assertEquals("1.1.0", registry.resolve(List.of(new Manifest.Require("demo", "~1.1")))
                .get(0).version(), "tilde stays within the minor line");
        assertEquals("2.0.0", registry.resolve(List.of(new Manifest.Require("demo", "2.0.0")))
                .get(0).version(), "a bare version pins exactly");
    }
}
