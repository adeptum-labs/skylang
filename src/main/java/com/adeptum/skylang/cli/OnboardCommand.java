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

import com.adeptum.skylang.config.ConfigException;
import com.adeptum.skylang.config.ConfigStore;
import com.adeptum.skylang.config.Provider;
import com.adeptum.skylang.config.ReasoningEffort;
import com.adeptum.skylang.config.SkyConfig;
import com.adeptum.skylang.synth.LangChain4jLlm;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code sky onboard} — provider setup. Picks a provider (OpenAI or Anthropic), a model, and an API
 * key, validates the key with a small live call, and writes {@code ~/.sky/config}. Re-running it
 * edits an existing config: the current provider, model, and key are offered as defaults, so
 * changing just the model is a matter of accepting the rest. {@code sky onboard --model NAME} on an
 * existing config changes only the model, reusing the stored provider and key.
 */
@Command(mixinStandardHelpOptions = true, name = "onboard", description = "Configure the provider, model, and API key (writes ~/.sky/config).")
public final class OnboardCommand implements Callable<Integer> {

    /** The credential store; injectable so tests need not touch the real {@code ~/.sky}. */
    ConfigStore store;

    @Option(names = {"-p", "--provider"}, description = "anthropic or openai")
    String provider;

    @Option(names = {"-k", "--api-key", "--key"}, description = "API key (prompted if omitted)")
    String apiKey;

    @Option(names = "--model",
            description = "Model name. On an existing config, changes only the model, reusing the "
                    + "stored provider and key. Prompted (with a default) if omitted.")
    String model;

    @Option(names = "--reasoning-effort",
            description = "How hard the model thinks: low, medium, or high (default high). On an "
                    + "existing config, changes only this. Prompted (with a default) if omitted.")
    String reasoningEffort;

    @Option(names = "--no-validate", description = "Skip the live validation call before saving.")
    boolean noValidate;

    @Option(names = "--show",
            description = "Print each resolved config key and the source it came from; makes no changes.")
    boolean show;

    @Override
    public Integer call() {
        ConfigStore store = store();
        if (show) {
            return showResolved(store);
        }
        try {
            Optional<SkyConfig> existing = store.load();

            // `sky onboard --model NAME` / `--reasoning-effort E` on an existing config changes only
            // the named settings, reusing the stored provider and key.
            if ((hasModelFlag() || hasEffortFlag()) && provider == null && apiKey == null
                    && existing.isPresent()) {
                return applyChanges(store, existing.get());
            }

            Provider p = (provider != null && !provider.isBlank())
                    ? Provider.parse(provider) : promptProvider(existing);
            String chosenModel = hasModelFlag() ? model.trim() : promptModel(p, existing);
            ReasoningEffort chosenEffort = hasEffortFlag()
                    ? ReasoningEffort.parse(reasoningEffort) : promptEffort(existing);
            String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : promptKey(existing);

            SkyConfig config = new SkyConfig(p, key.trim(), chosenModel, chosenEffort);
            if (!validate(config)) {
                return 1;
            }
            store.save(config);
            printConfig(store, config, "Saved");
            return 0;
        } catch (ConfigException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private ConfigStore store() {
        return store != null ? store : new ConfigStore();
    }

    private boolean hasModelFlag() {
        return model != null && !model.isBlank();
    }

    private boolean hasEffortFlag() {
        return reasoningEffort != null && !reasoningEffort.isBlank();
    }

    /** Change the named settings on an existing config, keeping its provider and key. */
    private Integer applyChanges(ConfigStore store, SkyConfig existing) {
        String newModel = hasModelFlag() ? model.trim() : existing.model();
        ReasoningEffort newEffort = hasEffortFlag()
                ? ReasoningEffort.parse(reasoningEffort) : existing.reasoningEffort();
        SkyConfig updated = new SkyConfig(existing.provider(), existing.apiKey(), newModel, newEffort);
        if (!validate(updated)) {
            return 1;
        }
        store.save(updated);
        printConfig(store, updated, "Updated");
        return 0;
    }

    /** Validate the key against the model with a small live call, unless {@code --no-validate}. */
    private boolean validate(SkyConfig config) {
        if (noValidate) {
            return true;
        }
        System.out.print("Validating " + config.provider().id()
                + " key against " + config.model() + " … ");
        System.out.flush();
        try {
            LangChain4jLlm.validate(config);
            System.out.println("ok");
            return true;
        } catch (RuntimeException e) {
            System.out.println("failed");
            System.err.println("error: " + e.getMessage());
            System.err.println("(use --no-validate to save without checking it)");
            return false;
        }
    }

    private void printConfig(ConfigStore store, SkyConfig config, String verb) {
        System.out.printf("%s %s%n  provider: %s%n  model:    %s%n  effort:   %s%n  key:      %s%n",
                verb, store.path(), config.provider().id(), config.model(),
                config.reasoningEffort().id(), config.maskedKey());
    }

    private Integer showResolved(ConfigStore store) {
        try {
            System.out.print(renderShow(store.describe(), store.path()));
            return 0;
        } catch (ConfigException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Render the resolution report {@code sky onboard --show} prints: each key with the source it
     * was read from, and a note wherever the config file silently overrides a set environment
     * variable. Pure, so it is unit-testable without touching the real {@code ~/.sky}.
     */
    static String renderShow(ConfigStore.Resolution r, Path configPath) {
        if (r.config().isEmpty()) {
            return "No LLM credentials configured.\n"
                    + "Run `sky onboard`, or set ANTHROPIC_API_KEY / OPENAI_API_KEY.\n";
        }
        SkyConfig config = r.config().get();
        ConfigStore.Origins origins = r.origins();
        StringBuilder out = new StringBuilder();
        out.append("Resolved configuration (").append(configPath).append("):\n");
        out.append(row("provider", config.provider().id(), origins.provider()));
        out.append(row("model", config.model(), origins.model()));
        out.append(row("effort", config.reasoningEffort().id(), origins.reasoningEffort()));
        out.append(row("key", config.maskedKey(), origins.apiKey()));

        boolean providerEnvSet = config.provider() == Provider.ANTHROPIC
                ? r.anthropicEnvSet() : r.openaiEnvSet();
        if (origins.apiKey() == ConfigStore.Origin.CONFIG_FILE && providerEnvSet) {
            out.append("note: ").append(config.provider().envVar())
                    .append(" is set but ignored — the config file defines api_key.\n");
        }
        if (origins.model() == ConfigStore.Origin.CONFIG_FILE && r.skyModelEnvSet()) {
            out.append("note: SKY_MODEL is set but ignored — the config file defines model.\n");
        }
        if (origins.reasoningEffort() == ConfigStore.Origin.CONFIG_FILE && r.skyReasoningEffortEnvSet()) {
            out.append("note: SKY_REASONING_EFFORT is set but ignored — "
                    + "the config file defines reasoning_effort.\n");
        }
        return out.toString();
    }

    private static String row(String key, String value, ConfigStore.Origin origin) {
        return String.format("  %-9s %-24s (%s)%n", key, value, origin.label());
    }

    private Provider promptProvider(Optional<SkyConfig> existing) {
        Optional<Provider> current = existing.map(SkyConfig::provider);
        String suffix = current.map(c -> " [" + c.id() + "]").orElse("");
        String answer = readLine("Provider (anthropic / openai)" + suffix + ": ");
        if (answer.isBlank() && current.isPresent()) {
            return current.get();
        }
        return Provider.parse(answer);
    }

    private String promptModel(Provider p, Optional<SkyConfig> existing) {
        String preset = defaultModel(p, existing);
        String answer = readLine("Model [" + preset + "]: ");
        return answer.isBlank() ? preset : answer.trim();
    }

    /** The model the prompt offers by default: the current one for this provider, else its default. */
    static String defaultModel(Provider p, Optional<SkyConfig> existing) {
        return existing.filter(e -> e.provider() == p).map(SkyConfig::model).orElse(p.defaultModel());
    }

    private ReasoningEffort promptEffort(Optional<SkyConfig> existing) {
        ReasoningEffort preset = existing.map(SkyConfig::reasoningEffort).orElse(ReasoningEffort.DEFAULT);
        String answer = readLine("Reasoning effort (low / medium / high) [" + preset.id() + "]: ");
        return answer.isBlank() ? preset : ReasoningEffort.parse(answer);
    }

    private String promptKey(Optional<SkyConfig> existing) {
        String prompt = existing.isPresent() ? "API key (enter to keep current): " : "API key: ";
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword(prompt);
            if (chars == null || chars.length == 0) {
                return keepOrFail(existing);
            }
            return new String(chars);
        }
        String key = readLine(prompt);
        if (key.isBlank()) {
            return keepOrFail(existing);
        }
        return key;
    }

    private static String keepOrFail(Optional<SkyConfig> existing) {
        if (existing.isPresent()) {
            return existing.get().apiKey();
        }
        throw new ConfigException("no API key entered");
    }

    // One reader for the whole command: a fresh reader per prompt would lose input its buffer
    // already read ahead, so piped answers past the first prompt would vanish.
    private BufferedReader stdin;

    private String readLine(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        if (stdin == null) {
            stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        }
        try {
            String line = stdin.readLine();
            return line == null ? "" : line.strip();
        } catch (IOException e) {
            throw new ConfigException("could not read input: " + e.getMessage());
        }
    }
}
