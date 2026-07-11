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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A profile's dependency registry: the only door between logical names and native packages.
 * The bundled registry ships with the compiler; a project may extend it with a reviewed
 * {@code sky.registry} file next to the manifest. Requiring a name the registry does not map
 * is a build-time refusal, and two names can never disagree on an underlying artifact —
 * that tension is reconciled here or reported, never discovered at runtime.
 */
public final class Registry {

    /** One registry mapping: a logical version and the pinned native closure it stands for. */
    record Entry(String version, List<String> coordinates, List<String> packages) {
    }

    public static final String LOCAL_FILE = "sky.registry";

    private final String profileId;
    private final Map<String, List<Entry>> entries;

    private Registry(String profileId, Map<String, List<Entry>> entries) {
        this.profileId = profileId;
        this.entries = entries;
    }

    /** The bundled registry for a profile, extended by the project's sky.registry if present. */
    public static Registry forProfile(String profileId, Optional<Path> projectDir) {
        Map<String, List<Entry>> entries = new LinkedHashMap<>();
        bundled(profileId).ifPresent(text -> parseInto(entries, text, "registry/" + profileId));
        projectDir.map(dir -> dir.resolve(LOCAL_FILE)).filter(Files::isRegularFile)
                .ifPresent(file -> parseLocal(entries, file, profileId));
        return new Registry(profileId, entries);
    }

    public List<Resolved> resolve(List<Manifest.Require> requires) {
        List<Resolved> resolved = new ArrayList<>();
        for (Manifest.Require r : requires) {
            List<Entry> versions = entries.get(r.name());
            if (versions == null) {
                throw new ConfigException("dependency '" + r.name() + "' is not in the " + profileId
                        + " registry (registered: " + String.join(", ", entries.keySet())
                        + ") — extend it with a reviewed sky.registry mapping");
            }
            Entry match = versions.stream()
                    .filter(e -> satisfies(e.version(), r.constraint()))
                    .max(Comparator.comparing(e -> versionKey(e.version())))
                    .orElseThrow(() -> new ConfigException("no registered version of '" + r.name()
                            + "' satisfies '" + r.constraint() + "' (available: "
                            + versions.stream().map(Entry::version).collect(Collectors.joining(", "))
                            + ")"));
            resolved.add(new Resolved(r.name(), r.constraint(), match.version(),
                    match.coordinates(), match.packages()));
        }
        reportConflicts(resolved);
        return resolved;
    }

    /** Two logical names must never pin two versions of the same underlying artifact. */
    private static void reportConflicts(List<Resolved> resolved) {
        Map<String, Resolved> byArtifact = new LinkedHashMap<>();
        Map<String, String> pinned = new LinkedHashMap<>();
        for (Resolved r : resolved) {
            for (String coordinate : r.coordinates()) {
                int cut = coordinate.lastIndexOf(':');
                String artifact = coordinate.substring(0, cut);
                String version = coordinate.substring(cut + 1);
                Resolved first = byArtifact.putIfAbsent(artifact, r);
                String firstVersion = pinned.putIfAbsent(artifact, version);
                if (first != null && !version.equals(firstVersion)) {
                    throw new ConfigException("dependencies '" + first.name() + "' and '" + r.name()
                            + "' disagree on " + artifact + " (" + firstVersion + " vs " + version
                            + ") — the registry cannot reconcile them");
                }
            }
        }
    }

    private static boolean satisfies(String version, String constraint) {
        if (constraint.startsWith("^")) {
            String wanted = constraint.substring(1);
            return major(version).equals(major(wanted))
                    && versionKey(version).compareTo(versionKey(wanted)) >= 0;
        }
        if (constraint.startsWith("~")) {
            String wanted = constraint.substring(1);
            return (version + ".").startsWith(wanted + ".")
                    && versionKey(version).compareTo(versionKey(wanted)) >= 0;
        }
        return version.equals(constraint);
    }

    private static String major(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    /** Zero-padded segments so lexicographic comparison orders versions numerically. */
    private static String versionKey(String version) {
        return java.util.Arrays.stream(version.split("\\."))
                .map(part -> String.format("%08d", Long.parseLong(part)))
                .collect(Collectors.joining("."));
    }

    // ---- parsing ---------------------------------------------------------------------------

    private static Optional<String> bundled(String profileId) {
        try (InputStream in = Registry.class.getResourceAsStream("/registry/" + profileId + ".registry")) {
            return in == null ? Optional.empty()
                    : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the bundled " + profileId + " registry", e);
        }
    }

    /** sky.registry groups mappings per profile: {@code profile <id> { <mapping lines> }}. */
    private static void parseLocal(Map<String, List<Entry>> entries, Path file, String profileId) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        String section = null;
        StringBuilder lines = new StringBuilder();
        for (String raw : text.lines().toList()) {
            String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("profile ") && line.endsWith("{")) {
                section = line.substring("profile ".length(), line.length() - 1).strip();
            } else if (line.equals("}")) {
                section = null;
            } else if (section == null) {
                throw new ConfigException("sky.registry: mappings must sit inside a 'profile <id> {' block");
            } else if (section.equals(profileId)) {
                lines.append(raw).append('\n');
            }
        }
        parseInto(entries, lines.toString(), file.toString());
    }

    private static void parseInto(Map<String, List<Entry>> entries, String text, String where) {
        for (String raw : text.lines().toList()) {
            String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            // <name> <version> -> <coordinate>[, <coordinate>...] packages <prefix>[, <prefix>...]
            int arrow = line.indexOf("->");
            int packagesAt = line.lastIndexOf(" packages ");
            if (arrow < 0 || packagesAt < arrow) {
                throw new ConfigException(where + ": a mapping line is "
                        + "'<name> <version> -> <coordinates> packages <prefixes>', got '" + line + "'");
            }
            String[] head = line.substring(0, arrow).strip().split("\\s+");
            if (head.length != 2) {
                throw new ConfigException(where + ": expected '<name> <version>' before '->' in '"
                        + line + "'");
            }
            List<String> coordinates = split(line.substring(arrow + 2, packagesAt));
            List<String> packages = split(line.substring(packagesAt + " packages ".length()));
            entries.computeIfAbsent(head[0], k -> new ArrayList<>())
                    .add(new Entry(head[1], coordinates, packages));
        }
    }

    private static List<String> split(String csv) {
        return java.util.Arrays.stream(csv.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }

    private static String stripComment(String line) {
        int i = line.indexOf("//");
        return i < 0 ? line : line.substring(0, i);
    }
}
