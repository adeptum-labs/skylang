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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

    /** An environment that supplies nothing — keeps file-only tests independent of the host. */
    private static final Map<String, String> NO_ENV = Map.of();

    @Test
    void savesAndLoadsRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve(".sky/config");
        ConfigStore store = new ConfigStore(file);

        store.save(new SkyConfig(Provider.OPENAI, "sk-openai-abcdef123456", "gpt-4o"));

        assertTrue(Files.exists(file));
        SkyConfig loaded = store.resolve();
        assertEquals(Provider.OPENAI, loaded.provider());
        assertEquals("sk-openai-abcdef123456", loaded.apiKey());
        assertEquals("gpt-4o", loaded.model());
    }

    @Test
    void defaultsModelWhenAbsentInFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config");
        Files.writeString(file, "provider=anthropic\napi_key=sk-ant-xyz9876543210\n");

        SkyConfig loaded = new ConfigStore(file, NO_ENV::get).resolve();
        assertEquals(Provider.ANTHROPIC, loaded.provider());
        assertEquals(Provider.ANTHROPIC.defaultModel(), loaded.model());
    }

    @Test
    void ignoresCommentsAndBlankLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config");
        Files.writeString(file, "# a comment\n\nprovider = openai \n api_key = sk-openai-key12345 \n");

        SkyConfig loaded = new ConfigStore(file).resolve();
        assertEquals(Provider.OPENAI, loaded.provider());
        assertEquals("sk-openai-key12345", loaded.apiKey());
    }

    @Test
    void resolveThrowsWhenNothingConfigured(@TempDir Path dir) {
        // A path that does not exist and (in CI) no provider env var → resolve must fail clearly.
        ConfigStore store = new ConfigStore(dir.resolve("does-not-exist"));
        if (System.getenv("ANTHROPIC_API_KEY") == null && System.getenv("OPENAI_API_KEY") == null) {
            ConfigException e = assertThrows(ConfigException.class, store::resolve);
            assertTrue(e.getMessage().contains("onboard"));
        }
    }

    @Test
    void rejectsUnknownProvider() {
        assertThrows(ConfigException.class, () -> Provider.parse("gemini"));
    }

    // ----- per-key resolution: the file wins per key, the environment fills the gaps -----------

    @Test
    void skyModelFillsTheModelGapWhenTheFileOmitsIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config");
        Files.writeString(file, "provider=anthropic\napi_key=sk-ant-xyz9876543210\n");
        Map<String, String> env = Map.of("SKY_MODEL", "claude-tuned-1");

        ConfigStore.Resolution r = new ConfigStore(file, env::get).describe();
        assertEquals("claude-tuned-1", r.config().orElseThrow().model());
        assertEquals(ConfigStore.Origin.SKY_MODEL_ENV, r.origins().model());
        assertEquals(ConfigStore.Origin.CONFIG_FILE, r.origins().apiKey());
        assertTrue(r.skyModelEnvSet());
    }

    @Test
    void theEnvironmentKeyFillsTheGapWhenTheFileOmitsApiKey(@TempDir Path dir) throws Exception {
        // The book's remedy: drop api_key from the file and the environment variable takes effect.
        Path file = dir.resolve("config");
        Files.writeString(file, "provider=anthropic\nmodel=claude-x\n");
        Map<String, String> env = Map.of("ANTHROPIC_API_KEY", "sk-ant-fromenv-123456");

        ConfigStore.Resolution r = new ConfigStore(file, env::get).describe();
        assertEquals("sk-ant-fromenv-123456", r.config().orElseThrow().apiKey());
        assertEquals(ConfigStore.Origin.ANTHROPIC_ENV, r.origins().apiKey());
        assertEquals(ConfigStore.Origin.CONFIG_FILE, r.origins().model());
    }

    @Test
    void theEnvironmentSuppliesEverythingWhenNoFileExists(@TempDir Path dir) {
        Map<String, String> env = Map.of("ANTHROPIC_API_KEY", "sk-ant-only-env-123456");

        ConfigStore.Resolution r = new ConfigStore(dir.resolve("absent"), env::get).describe();
        SkyConfig config = r.config().orElseThrow();
        assertEquals(Provider.ANTHROPIC, config.provider());
        assertEquals("sk-ant-only-env-123456", config.apiKey());
        assertEquals(Provider.ANTHROPIC.defaultModel(), config.model());
        assertEquals(ConfigStore.Origin.ANTHROPIC_ENV, r.origins().provider());
        assertEquals(ConfigStore.Origin.ANTHROPIC_ENV, r.origins().apiKey());
        assertEquals(ConfigStore.Origin.PROVIDER_DEFAULT, r.origins().model());
    }

    @Test
    void aCompleteFileWinsEveryKeyWhileTheEnvironmentIsFlaggedSet(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config");
        Files.writeString(file, "provider=anthropic\nmodel=claude-x\napi_key=sk-ant-fromfile-123456\n");
        Map<String, String> env = Map.of("ANTHROPIC_API_KEY", "sk-ant-env-999999", "SKY_MODEL", "ignored");

        ConfigStore.Resolution r = new ConfigStore(file, env::get).describe();
        assertEquals("sk-ant-fromfile-123456", r.config().orElseThrow().apiKey());
        assertEquals(ConfigStore.Origin.CONFIG_FILE, r.origins().provider());
        assertEquals(ConfigStore.Origin.CONFIG_FILE, r.origins().model());
        assertEquals(ConfigStore.Origin.CONFIG_FILE, r.origins().apiKey());
        assertTrue(r.anthropicEnvSet(), "the ignored variable is still reported as set, so --show can flag it");
        assertTrue(r.skyModelEnvSet());
    }

    @Test
    void bothEnvironmentKeysWithoutAFileProviderConflict(@TempDir Path dir) {
        Map<String, String> env = Map.of(
                "ANTHROPIC_API_KEY", "sk-ant-123456789012", "OPENAI_API_KEY", "sk-openai-123456789012");

        ConfigException e = assertThrows(ConfigException.class,
                () -> new ConfigStore(dir.resolve("absent"), env::get).describe());
        assertTrue(e.getMessage().contains("both"), e.getMessage());
    }

    @Test
    void aFileProviderResolvesTheConflictTheEnvironmentWouldRaise(@TempDir Path dir) throws Exception {
        // With the provider pinned in the file, both env keys being set is no longer ambiguous.
        Path file = dir.resolve("config");
        Files.writeString(file, "provider=anthropic\napi_key=sk-ant-fromfile-123456\n");
        Map<String, String> env = Map.of(
                "ANTHROPIC_API_KEY", "sk-ant-123456789012", "OPENAI_API_KEY", "sk-openai-123456789012");

        ConfigStore.Resolution r = new ConfigStore(file, env::get).describe();
        assertEquals(Provider.ANTHROPIC, r.config().orElseThrow().provider());
    }

    @Test
    void nothingConfiguredResolvesEmpty(@TempDir Path dir) {
        ConfigStore.Resolution r = new ConfigStore(dir.resolve("absent"), NO_ENV::get).describe();
        assertTrue(r.config().isEmpty());
        assertFalse(r.anthropicEnvSet());
    }

    @Test
    void reasoningEffortResolvesFileThenEnvThenDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config");
        Map<String, String> env = Map.of("SKY_REASONING_EFFORT", "medium");

        Files.writeString(file, "provider=openai\napi_key=sk-openai-abcdef123456\nreasoning_effort=low\n");
        ConfigStore.Resolution fromFile = new ConfigStore(file, env::get).describe();
        assertEquals(ReasoningEffort.LOW, fromFile.config().orElseThrow().reasoningEffort());
        assertEquals(ConfigStore.Origin.CONFIG_FILE, fromFile.origins().reasoningEffort());
        assertTrue(fromFile.skyReasoningEffortEnvSet(), "the ignored variable is still reported set");

        Files.writeString(file, "provider=openai\napi_key=sk-openai-abcdef123456\n");
        ConfigStore.Resolution fromEnv = new ConfigStore(file, env::get).describe();
        assertEquals(ReasoningEffort.MEDIUM, fromEnv.config().orElseThrow().reasoningEffort());
        assertEquals(ConfigStore.Origin.SKY_EFFORT_ENV, fromEnv.origins().reasoningEffort());

        ConfigStore.Resolution fallback = new ConfigStore(file, NO_ENV::get).describe();
        assertEquals(ReasoningEffort.HIGH, fallback.config().orElseThrow().reasoningEffort(),
                "with neither file nor environment, effort defaults to high");
        assertEquals(ConfigStore.Origin.DEFAULT, fallback.origins().reasoningEffort());
    }
}
