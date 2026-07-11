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

package com.adeptum.skylang.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The project manifest ({@code sky.project}): the project name, the one line that binds the
 * build to a profile, and the {@code requires} block of dependencies — logical names with
 * version constraints, resolved by the active profile's registry. Three things, and no more.
 */
public record Manifest(String project, String profile, List<Require> requires) {

    /** One declared dependency: a logical name and a version constraint (^4.0, ~2.1, 2.1.3). */
    public record Require(String name, String constraint) {
    }

    public static final String FILE_NAME = "sky.project";
    private static final String REFERENCE_PROFILE = "jvm-jakarta";

    /** The manifest next to the sources, when the project has one. */
    public static Optional<Manifest> load(Path dir) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    public static Manifest parse(String source) {
        String project = null;
        String profile = null;
        List<Require> requires = new ArrayList<>();
        boolean inRequires = false;
        for (String raw : source.lines().toList()) {
            String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (inRequires) {
                if (line.equals("}")) {
                    inRequires = false;
                } else {
                    requires.add(require(line, requires));
                }
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            String value = parts.length > 1 ? parts[1].strip() : "";
            switch (parts[0]) {
                case "project" -> project = unique("project", project, value);
                case "profile" -> profile = unique("profile", profile, value);
                case "requires" -> {
                    if (!value.equals("{")) {
                        throw new ConfigException("sky.project: expected 'requires {'");
                    }
                    inRequires = true;
                }
                default -> throw new ConfigException("sky.project: unknown directive '" + parts[0]
                        + "' (expected project, profile or requires)");
            }
        }
        if (inRequires) {
            throw new ConfigException("sky.project: unclosed requires block");
        }
        if (project == null || project.isEmpty()) {
            throw new ConfigException("sky.project: missing 'project <name>' line");
        }
        // Both values name things and become path segments; keep them to plain identifiers.
        requireName("project", project);
        String active = profile == null ? REFERENCE_PROFILE : profile;
        requireName("profile", active);
        return new Manifest(project, active, List.copyOf(requires));
    }

    private static Require require(String line, List<Require> seen) {
        String[] parts = line.split("\\s+");
        if (parts.length != 2) {
            throw new ConfigException("sky.project: a requires line is '<name> <constraint>', got '"
                    + line + "'");
        }
        String name = parts[0];
        String constraint = parts[1];
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            throw new ConfigException("sky.project: dependency name '" + name
                    + "' must be a plain lowercase name");
        }
        if (!constraint.matches("[\\^~]?\\d+(\\.\\d+){0,2}")) {
            throw new ConfigException("sky.project: version constraint '" + constraint
                    + "' is not ^X.Y, ~X.Y or an exact version");
        }
        if (seen.stream().anyMatch(r -> r.name().equals(name))) {
            throw new ConfigException("sky.project: duplicate requirement '" + name + "'");
        }
        return new Require(name, constraint);
    }

    private static String unique(String directive, String existing, String value) {
        if (existing != null) {
            throw new ConfigException("sky.project: duplicate '" + directive + "' line");
        }
        if (value.isEmpty()) {
            throw new ConfigException("sky.project: '" + directive + "' needs a value");
        }
        return value;
    }

    private static void requireName(String directive, String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new ConfigException("sky.project: '" + directive + "' must be a plain name, got '"
                    + value + "'");
        }
    }

    private static String stripComment(String line) {
        int i = line.indexOf("//");
        return i < 0 ? line : line.substring(0, i);
    }
}
