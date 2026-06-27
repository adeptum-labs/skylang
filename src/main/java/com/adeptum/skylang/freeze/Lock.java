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

    private String profileId = "";
    private String profileVersion = "";
    private final Map<String, Entry> methods = new LinkedHashMap<>();

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
                                    String.valueOf(m.get("body"))));
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

        Map<String, Object> methodMap = new LinkedHashMap<>();
        methods.forEach((key, entry) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("specHash", entry.specHash());
            m.put("body", entry.body());
            methodMap.put(key, m);
        });

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("profile", profile);
        root.put("methods", methodMap);

        try {
            Files.writeString(path, Json.write(root) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }

    public void setProfile(String id, String version) {
        this.profileId = id;
        this.profileVersion = version;
    }

    public Optional<Entry> get(String key) {
        return Optional.ofNullable(methods.get(key));
    }

    public void put(String key, Entry entry) {
        methods.put(key, entry);
    }
}
