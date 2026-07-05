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
import com.adeptum.skylang.config.SkyConfig;
import com.adeptum.skylang.synth.LangChain4jLlm;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * {@code sky onboard} — one-time setup. Picks a provider (OpenAI or Anthropic), captures an API
 * key, validates it with a small live call, and writes {@code ~/.sky/config}. After this,
 * {@code sky build} needs no environment variables.
 */
@Command(name = "onboard", description = "Configure the LLM provider and API key (writes ~/.sky/config).")
public final class OnboardCommand implements Callable<Integer> {

    @Option(names = {"-p", "--provider"}, description = "anthropic or openai")
    String provider;

    @Option(names = {"-k", "--api-key", "--key"}, description = "API key (prompted if omitted)")
    String apiKey;

    @Option(names = "--model", description = "Model name (defaults to the provider's default).")
    String model;

    @Option(names = "--no-validate", description = "Skip the live validation call before saving.")
    boolean noValidate;

    @Override
    public Integer call() {
        try {
            Provider p = (provider != null && !provider.isBlank())
                    ? Provider.parse(provider) : promptProvider();
            String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : promptKey();
            String chosenModel = (model != null && !model.isBlank()) ? model : p.defaultModel();

            SkyConfig config = new SkyConfig(p, key.trim(), chosenModel);

            if (!noValidate) {
                System.out.print("Validating " + p.id() + " key against " + chosenModel + " … ");
                System.out.flush();
                try {
                    LangChain4jLlm.validate(config);
                    System.out.println("ok");
                } catch (RuntimeException e) {
                    System.out.println("failed");
                    System.err.println("error: " + e.getMessage());
                    System.err.println("(use --no-validate to save the key without checking it)");
                    return 1;
                }
            }

            ConfigStore store = new ConfigStore();
            store.save(config);
            System.out.printf("Saved %s%n  provider: %s%n  model:    %s%n  key:      %s%n",
                    store.path(), p.id(), chosenModel, config.maskedKey());
            return 0;
        } catch (ConfigException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private Provider promptProvider() {
        String answer = readLine("Provider [anthropic/openai]: ");
        return Provider.parse(answer);
    }

    private String promptKey() {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("API key: ");
            if (chars == null || chars.length == 0) {
                throw new ConfigException("no API key entered");
            }
            return new String(chars);
        }
        String key = readLine("API key: ");
        if (key.isBlank()) {
            throw new ConfigException("no API key entered");
        }
        return key;
    }

    private String readLine(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            return line == null ? "" : line.strip();
        } catch (IOException e) {
            throw new ConfigException("could not read input: " + e.getMessage());
        }
    }
}
