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

package com.adeptum.skylang.cli;

import com.adeptum.skylang.config.ConfigStore;
import com.adeptum.skylang.config.Provider;
import com.adeptum.skylang.config.ReasoningEffort;
import com.adeptum.skylang.config.SkyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sky onboard --show} is the credential-resolution diagnostic: it prints each key with the
 * source it came from and flags an environment variable the config file silently overrides. The
 * render is pure, so these tests exercise it with hand-built resolutions, never touching ~/.sky.
 */
class OnboardCommandTest {

    private static final Path CONFIG = Path.of("/home/dev/.sky/config");

    @Test
    void showReportsFileSourcesAndFlagsTheIgnoredKey() {
        ConfigStore.Resolution r = new ConfigStore.Resolution(
                Optional.of(new SkyConfig(Provider.ANTHROPIC, "sk-ant-fromfile-123456", "claude-x")),
                new ConfigStore.Origins(ConfigStore.Origin.CONFIG_FILE, ConfigStore.Origin.CONFIG_FILE,
                        ConfigStore.Origin.CONFIG_FILE, ConfigStore.Origin.CONFIG_FILE),
                true, false, false, false);

        String out = OnboardCommand.renderShow(r, CONFIG);
        assertTrue(out.contains("provider  anthropic"), out);
        assertTrue(out.contains("effort    high"), "the reasoning effort is reported:\n" + out);
        assertTrue(out.contains("(config file)"), out);
        assertTrue(out.contains("/home/dev/.sky/config"), out);
        assertTrue(out.contains("ANTHROPIC_API_KEY is set but ignored"),
                "the diagnostic must explain why the environment key was not used:\n" + out);
        assertFalse(out.contains("SKY_MODEL is set but ignored"),
                "no model note when SKY_MODEL is not set:\n" + out);
    }

    @Test
    void showNamesTheEnvironmentSourcesThatFilledTheGaps() {
        ConfigStore.Resolution r = new ConfigStore.Resolution(
                Optional.of(new SkyConfig(Provider.ANTHROPIC, "sk-ant-fromenv-123456", "claude-tuned")),
                new ConfigStore.Origins(ConfigStore.Origin.CONFIG_FILE, ConfigStore.Origin.SKY_MODEL_ENV,
                        ConfigStore.Origin.ANTHROPIC_ENV, ConfigStore.Origin.SKY_EFFORT_ENV),
                true, false, true, true);

        String out = OnboardCommand.renderShow(r, CONFIG);
        assertTrue(out.contains("(ANTHROPIC_API_KEY)"), out);
        assertTrue(out.contains("(SKY_MODEL)"), out);
        assertTrue(out.contains("(SKY_REASONING_EFFORT)"), "effort resolved from the environment:\n" + out);
        // A key filled from the environment was not overridden, so nothing is flagged as ignored.
        assertFalse(out.contains("is set but ignored"), out);
    }

    @Test
    void showGuidesWhenNothingIsConfigured() {
        ConfigStore.Resolution r = new ConfigStore.Resolution(Optional.empty(),
                new ConfigStore.Origins(ConfigStore.Origin.UNSET, ConfigStore.Origin.UNSET,
                        ConfigStore.Origin.UNSET, ConfigStore.Origin.UNSET),
                false, false, false, false);

        String out = OnboardCommand.renderShow(r, CONFIG);
        assertTrue(out.contains("No LLM credentials configured"), out);
        assertTrue(out.contains("sky onboard"), out);
    }

    @Test
    void theFlagsAreWiredIntoPicocli() {
        String usage = new CommandLine(new OnboardCommand()).getUsageMessage();
        assertTrue(usage.contains("--show"), "onboard must expose --show:\n" + usage);
        assertTrue(usage.contains("--reasoning-effort"), "onboard must expose --reasoning-effort:\n" + usage);
    }

    // ----- selecting and changing the model -----------------------------------

    @Test
    void defaultModelPrefersTheCurrentModelForTheSameProvider() {
        SkyConfig current = new SkyConfig(Provider.ANTHROPIC, "sk-ant-123456789012", "claude-tuned");
        assertEquals("claude-tuned",
                OnboardCommand.defaultModel(Provider.ANTHROPIC, Optional.of(current)),
                "re-onboarding the same provider should offer the current model");
        assertEquals(Provider.OPENAI.defaultModel(),
                OnboardCommand.defaultModel(Provider.OPENAI, Optional.of(current)),
                "switching provider should fall back to the new provider's default");
        assertEquals(Provider.ANTHROPIC.defaultModel(),
                OnboardCommand.defaultModel(Provider.ANTHROPIC, Optional.empty()),
                "a first onboarding offers the provider default");
    }

    @Test
    void flagsDriveANonInteractiveOnboardIncludingTheModel(@TempDir Path dir) {
        Path file = dir.resolve(".sky/config");
        OnboardCommand cmd = new OnboardCommand();
        cmd.store = new ConfigStore(file, name -> null);
        cmd.provider = "anthropic";
        cmd.apiKey = "sk-ant-onboardkey-123456";
        cmd.model = "claude-custom-9";
        cmd.reasoningEffort = "high";   // set so the flow stays non-interactive
        cmd.noValidate = true;

        String out = capture(cmd);
        SkyConfig saved = new ConfigStore(file, name -> null).resolve();
        assertEquals("claude-custom-9", saved.model(), "the chosen model must be persisted");
        assertEquals(Provider.ANTHROPIC, saved.provider());
        assertEquals(ReasoningEffort.HIGH, saved.reasoningEffort());
        assertTrue(out.contains("claude-custom-9"), "the summary reports the model:\n" + out);
    }

    @Test
    void flagsPersistTheChosenReasoningEffort(@TempDir Path dir) {
        Path file = dir.resolve(".sky/config");
        OnboardCommand cmd = new OnboardCommand();
        cmd.store = new ConfigStore(file, name -> null);
        cmd.provider = "openai";
        cmd.apiKey = "sk-openai-onboardkey-1234";
        cmd.model = "gpt-5";
        cmd.reasoningEffort = "medium";
        cmd.noValidate = true;

        String out = capture(cmd);
        SkyConfig saved = new ConfigStore(file, name -> null).resolve();
        assertEquals(ReasoningEffort.MEDIUM, saved.reasoningEffort(), "the chosen effort must be persisted");
        assertTrue(out.contains("effort:   medium"), "the summary reports the effort:\n" + out);
    }

    @Test
    void modelOnlyChangeReusesTheStoredProviderAndKey(@TempDir Path dir) {
        Path file = dir.resolve(".sky/config");
        new ConfigStore(file, name -> null)
                .save(new SkyConfig(Provider.OPENAI, "sk-openai-original-123456", "gpt-4o"));

        OnboardCommand cmd = new OnboardCommand();
        cmd.store = new ConfigStore(file, name -> null);
        cmd.model = "gpt-4o-mini";
        cmd.noValidate = true;

        String out = capture(cmd);
        SkyConfig updated = new ConfigStore(file, name -> null).resolve();
        assertEquals("gpt-4o-mini", updated.model(), "only the model changes");
        assertEquals(Provider.OPENAI, updated.provider(), "the stored provider is reused");
        assertEquals("sk-openai-original-123456", updated.apiKey(), "the stored key is reused");
        assertTrue(out.contains("Updated") && out.contains("gpt-4o-mini"), out);
    }

    @Test
    void effortOnlyChangeReusesModelProviderAndKey(@TempDir Path dir) {
        Path file = dir.resolve(".sky/config");
        new ConfigStore(file, name -> null).save(
                new SkyConfig(Provider.OPENAI, "sk-openai-original-123456", "gpt-5", ReasoningEffort.LOW));

        OnboardCommand cmd = new OnboardCommand();
        cmd.store = new ConfigStore(file, name -> null);
        cmd.reasoningEffort = "high";
        cmd.noValidate = true;

        String out = capture(cmd);
        SkyConfig updated = new ConfigStore(file, name -> null).resolve();
        assertEquals(ReasoningEffort.HIGH, updated.reasoningEffort(), "only the effort changes");
        assertEquals("gpt-5", updated.model(), "the stored model is reused");
        assertEquals("sk-openai-original-123456", updated.apiKey(), "the stored key is reused");
        assertTrue(out.contains("Updated") && out.contains("effort:   high"), out);
    }

    /** Run the command with stdout captured, returning what it printed. */
    private static String capture(OnboardCommand cmd) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            assertEquals(0, cmd.call(), "onboard should succeed");
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
