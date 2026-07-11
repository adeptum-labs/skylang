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
import java.util.function.UnaryOperator;

/**
 * Reads and writes SkyLang's credential file, {@code ~/.sky/config} (a simple {@code key=value}
 * file). {@code sky onboard} writes it; {@code sky build} resolves from it, filling any key the
 * file omits from the provider environment variables. Resolution is per key: the file wins for
 * every key it defines, and the environment fills only the gaps. Both the path and the environment
 * are injectable for tests.
 */
public final class ConfigStore {

    private static final String MODEL_ENV = "SKY_MODEL";
    private static final String EFFORT_ENV = "SKY_REASONING_EFFORT";

    private final Path path;
    private final UnaryOperator<String> env;

    public ConfigStore() {
        this(defaultPath());
    }

    public ConfigStore(Path path) {
        this(path, System::getenv);
    }

    public ConfigStore(Path path, UnaryOperator<String> env) {
        this.path = path;
        this.env = env;
    }

    public static Path defaultPath() {
        return Paths.get(System.getProperty("user.home"), ".sky", "config");
    }

    public Path path() {
        return path;
    }

    /** Where a resolved configuration key was read from — for {@code sky onboard --show}. */
    public enum Origin {
        CONFIG_FILE("config file"),
        ANTHROPIC_ENV("ANTHROPIC_API_KEY"),
        OPENAI_ENV("OPENAI_API_KEY"),
        SKY_MODEL_ENV(MODEL_ENV),
        SKY_EFFORT_ENV(EFFORT_ENV),
        PROVIDER_DEFAULT("provider default"),
        DEFAULT("default"),
        UNSET("unset");

        private final String label;

        Origin(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The source of each resolved field. */
    public record Origins(Origin provider, Origin model, Origin apiKey, Origin reasoningEffort) {
    }

    /**
     * A resolved configuration with the source of each field, plus which provider environment
     * variables are set — enough for {@code sky onboard --show} to explain what won and why.
     */
    public record Resolution(Optional<SkyConfig> config, Origins origins,
                             boolean anthropicEnvSet, boolean openaiEnvSet, boolean skyModelEnvSet,
                             boolean skyReasoningEffortEnvSet) {
    }

    /** The merged configuration, or empty when neither a file nor the environment supplies one. */
    public Optional<SkyConfig> load() {
        return describe().config();
    }

    public SkyConfig resolve() {
        return load().orElseThrow(() -> new ConfigException(
                "no LLM credentials configured. Run `sky onboard` to set a provider and key, "
                        + "or set ANTHROPIC_API_KEY / OPENAI_API_KEY."));
    }

    /**
     * Resolve every field from the file first, then the environment, recording where each came
     * from. The file wins for any key it defines; the environment fills only the gaps.
     */
    public Resolution describe() {
        Map<String, String> file = Files.exists(path) ? readFileKv() : Map.of();
        String anthropicKey = trimmedEnv(Provider.ANTHROPIC.envVar());
        String openaiKey = trimmedEnv(Provider.OPENAI.envVar());
        String skyModel = trimmedEnv(MODEL_ENV);
        String skyEffort = trimmedEnv(EFFORT_ENV);
        boolean anthropicSet = anthropicKey != null;
        boolean openaiSet = openaiKey != null;
        boolean skyModelSet = skyModel != null;
        boolean skyEffortSet = skyEffort != null;

        Provider provider;
        Origin providerOrigin;
        if (file.containsKey("provider")) {
            provider = Provider.parse(file.get("provider"));
            providerOrigin = Origin.CONFIG_FILE;
        } else if (anthropicSet && openaiSet) {
            throw new ConfigException("both ANTHROPIC_API_KEY and OPENAI_API_KEY are set — "
                    + "run `sky onboard` to pick one, or unset the other.");
        } else if (anthropicSet) {
            provider = Provider.ANTHROPIC;
            providerOrigin = Origin.ANTHROPIC_ENV;
        } else if (openaiSet) {
            provider = Provider.OPENAI;
            providerOrigin = Origin.OPENAI_ENV;
        } else {
            return new Resolution(Optional.empty(),
                    new Origins(Origin.UNSET, Origin.UNSET, Origin.UNSET, Origin.UNSET),
                    false, false, skyModelSet, skyEffortSet);
        }

        String providerEnvKey = provider == Provider.ANTHROPIC ? anthropicKey : openaiKey;
        Origin providerEnvOrigin =
                provider == Provider.ANTHROPIC ? Origin.ANTHROPIC_ENV : Origin.OPENAI_ENV;
        String apiKey;
        Origin apiKeyOrigin;
        if (file.containsKey("api_key")) {
            apiKey = file.get("api_key");
            apiKeyOrigin = Origin.CONFIG_FILE;
        } else if (providerEnvKey != null) {
            apiKey = providerEnvKey;
            apiKeyOrigin = providerEnvOrigin;
        } else {
            apiKey = null;
            apiKeyOrigin = Origin.UNSET;
        }

        String model;
        Origin modelOrigin;
        if (file.containsKey("model")) {
            model = file.get("model");
            modelOrigin = Origin.CONFIG_FILE;
        } else if (skyModel != null) {
            model = skyModel;
            modelOrigin = Origin.SKY_MODEL_ENV;
        } else {
            model = provider.defaultModel();
            modelOrigin = Origin.PROVIDER_DEFAULT;
        }

        ReasoningEffort effort;
        Origin effortOrigin;
        if (file.containsKey("reasoning_effort")) {
            effort = ReasoningEffort.parse(file.get("reasoning_effort"));
            effortOrigin = Origin.CONFIG_FILE;
        } else if (skyEffort != null) {
            effort = ReasoningEffort.parse(skyEffort);
            effortOrigin = Origin.SKY_EFFORT_ENV;
        } else {
            effort = ReasoningEffort.DEFAULT;
            effortOrigin = Origin.DEFAULT;
        }

        Origins origins = new Origins(providerOrigin, modelOrigin, apiKeyOrigin, effortOrigin);
        Optional<SkyConfig> config = apiKey == null ? Optional.empty()
                : Optional.of(new SkyConfig(provider, apiKey.trim(), model, effort));
        return new Resolution(config, origins, anthropicSet, openaiSet, skyModelSet, skyEffortSet);
    }

    /** An environment value, trimmed, or null when unset or blank. */
    private String trimmedEnv(String name) {
        String value = env.apply(name);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    public void save(SkyConfig config) {
        String content = ""
                + "# SkyLang credentials — keep private, do not commit.\n"
                + "provider=" + config.provider().id() + "\n"
                + "model=" + config.model() + "\n"
                + "reasoning_effort=" + config.reasoningEffort().id() + "\n"
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

    /** Parse the {@code key=value} file into its defined keys, ignoring comments and blanks. */
    private Map<String, String> readFileKv() {
        Map<String, String> kv = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    String value = trimmed.substring(eq + 1).strip();
                    if (!value.isEmpty()) {
                        kv.put(trimmed.substring(0, eq).strip(), value);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        return kv;
    }

    private static void trySetPerms(Path target, String perms) {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(perms));
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows) — best effort only.
        }
    }
}
