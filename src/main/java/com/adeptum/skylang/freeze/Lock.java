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

package com.adeptum.skylang.freeze;

import com.adeptum.skylang.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The freeze store ({@code sky.lock}): the accepted, already-verified body of each method
 * plus a content hash of its specification. A later build reuses a body when its spec hash
 * still matches, so the model is never called again — builds stay offline and deterministic.
 */
public final class Lock {

    /** A frozen method: the hash of its spec and the accepted target-language body. */
    public record Entry(String specHash, String body) {
    }

    /**
     * A frozen view: the hash of its spec, the accepted Facelets markup plus backing bean, and the
     * visual baseline — a base64 PNG of the view rasterized by the pinned renderer, captured when
     * the view was accepted ({@code ""} until then). Later builds re-render and diff against it.
     */
    public record ViewEntry(String specHash, String markup, String bean, String visual) {

        public ViewEntry(String specHash, String markup, String bean) {
            this(specHash, markup, bean, "");
        }

        public ViewEntry withVisual(String visual) {
            return new ViewEntry(specHash, markup, bean, visual);
        }
    }

    private String profileId = "";
    private String profileVersion = "";
    private final Map<String, Entry> methods = new LinkedHashMap<>();
    private final Map<String, ViewEntry> views = new LinkedHashMap<>();

    public static Lock load(Path path) {
        Lock lock = new Lock();
        if (!Files.exists(path)) {
            return lock;
        }
        try {
            Object root = Json.parse(Files.readString(path));
            if (root instanceof Map<?, ?> map) {
                if (map.get("profile") instanceof Map<?, ?> profile) {
                    lock.profileId = asString(profile.get("id"));
                    lock.profileVersion = asString(profile.get("version"));
                }
                if (map.get("methods") instanceof Map<?, ?> methodMap) {
                    for (Map.Entry<?, ?> e : methodMap.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> m) {
                            lock.methods.put(String.valueOf(e.getKey()), new Entry(
                                    String.valueOf(m.get("specHash")),
                                    multiline(m.get("body"))));
                        }
                    }
                }
                if (map.get("views") instanceof Map<?, ?> viewMap) {
                    for (Map.Entry<?, ?> e : viewMap.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> v) {
                            lock.views.put(String.valueOf(e.getKey()), new ViewEntry(
                                    String.valueOf(v.get("specHash")),
                                    multiline(v.get("markup")),
                                    multiline(v.get("bean")),
                                    asString(v.get("visual"))));
                        }
                    }
                }
            }
            return lock;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    public void save(Path path) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", profileId);
        profile.put("version", profileVersion);

        // Bodies and markup are stored one line per array element, and the whole lock is
        // pretty-printed, so a git diff of sky.lock reads at the level of changed lines.
        Map<String, Object> methodMap = new LinkedHashMap<>();
        methods.forEach((key, entry) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("specHash", entry.specHash());
            m.put("body", entry.body().lines().toList());
            methodMap.put(key, m);
        });

        Map<String, Object> viewMap = new LinkedHashMap<>();
        views.forEach((key, entry) -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("specHash", entry.specHash());
            v.put("markup", entry.markup().lines().toList());
            v.put("bean", entry.bean().lines().toList());
            v.put("visual", entry.visual());
            viewMap.put(key, v);
        });

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("profile", profile);
        root.put("methods", methodMap);
        root.put("views", viewMap);

        try {
            Files.writeString(path, Json.writePretty(root) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    /** A stored text: an array of lines in the current form, a single string in older locks. */
    private static String multiline(Object o) {
        if (o instanceof java.util.List<?> lines) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                sb.append(i > 0 ? "\n" : "").append(lines.get(i));
            }
            return sb.toString();
        }
        return asString(o);
    }

    /**
     * The canonical form frozen bodies are stored in: LF line endings, no trailing
     * whitespace, leading tabs as four spaces, no blank first or last lines — so a
     * regeneration can never produce a diff of pure formatting.
     */
    public static String canonical(String body) {
        java.util.List<String> lines = new java.util.ArrayList<>(body.replace("\r\n", "\n")
                .replace('\r', '\n').lines()
                .map(line -> {
                    int i = 0;
                    while (i < line.length() && line.charAt(i) == '\t') {
                        i++;
                    }
                    return "    ".repeat(i) + line.substring(i).stripTrailing();
                })
                .toList());
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }

    public void setProfile(String id, String version) {
        this.profileId = id;
        this.profileVersion = version;
    }

    /** The profile the lock was frozen under — {@code ""} for a fresh lock. */
    public String profileId() {
        return profileId;
    }

    public Optional<Entry> get(String key) {
        return Optional.ofNullable(methods.get(key));
    }

    public void put(String key, Entry entry) {
        methods.put(key, entry);
    }

    public Optional<ViewEntry> getView(String key) {
        return Optional.ofNullable(views.get(key));
    }

    public void putView(String key, ViewEntry entry) {
        views.put(key, entry);
    }
}
