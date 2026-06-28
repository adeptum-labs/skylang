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
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes SkyLang's credential file, {@code ~/.sky/config} (a simple {@code key=value}
 * file). {@code sky onboard} writes it; {@code sky build} resolves from it, falling back to the
 * provider environment variables. The path is injectable for tests.
 */
public final class ConfigStore {

    private final Path path;

    public ConfigStore() {
        this(defaultPath());
    }

    public ConfigStore(Path path) {
        this.path = path;
    }

    public static Path defaultPath() {
        return Paths.get(System.getProperty("user.home"), ".sky", "config");
    }

    public Path path() {
        return path;
    }

    /** File first (explicit onboarding wins); then a single provider environment variable. */
    public Optional<SkyConfig> load() {
        if (Files.exists(path)) {
            return Optional.of(readFile());
        }
        return fromEnvironment();
    }

    public SkyConfig resolve() {
        return load().orElseThrow(() -> new ConfigException(
                "no LLM credentials configured. Run `sky onboard` to set a provider and key, "
                        + "or set ANTHROPIC_API_KEY / OPENAI_API_KEY."));
    }

    public void save(SkyConfig config) {
        String content = ""
                + "# SkyLang credentials — keep private, do not commit.\n"
                + "provider=" + config.provider().id() + "\n"
                + "model=" + config.model() + "\n"
                + "api_key=" + config.apiKey() + "\n";
        try {
            Path dir = path.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
                trySetPerms(dir, "rwx------");
            }
            Files.writeString(path, content);
            trySetPerms(path, "rw-------");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    private SkyConfig readFile() {
        Map<String, String> kv = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    kv.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        Provider provider = Provider.parse(kv.get("provider"));
        String model = kv.getOrDefault("model", provider.defaultModel());
        return new SkyConfig(provider, kv.get("api_key"), model);
    }

    private Optional<SkyConfig> fromEnvironment() {
        String anthropic = System.getenv(Provider.ANTHROPIC.envVar());
        String openai = System.getenv(Provider.OPENAI.envVar());
        boolean hasAnthropic = anthropic != null && !anthropic.isBlank();
        boolean hasOpenai = openai != null && !openai.isBlank();

        if (hasAnthropic && hasOpenai) {
            throw new ConfigException("both ANTHROPIC_API_KEY and OPENAI_API_KEY are set — "
                    + "run `sky onboard` to pick one, or unset the other.");
        }
        String override = System.getenv("SKY_MODEL");
        if (hasAnthropic) {
            return Optional.of(config(Provider.ANTHROPIC, anthropic, override));
        }
        if (hasOpenai) {
            return Optional.of(config(Provider.OPENAI, openai, override));
        }
        return Optional.empty();
    }

    private static SkyConfig config(Provider provider, String key, String modelOverride) {
        String model = (modelOverride == null || modelOverride.isBlank())
                ? provider.defaultModel() : modelOverride;
        return new SkyConfig(provider, key.trim(), model);
    }

    private static void trySetPerms(Path target, String perms) {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(perms));
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows) — best effort only.
        }
    }
}
