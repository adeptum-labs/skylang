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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

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

        SkyConfig loaded = new ConfigStore(file).resolve();
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
}
