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

package com.adeptum.skylang.synth;

import com.adeptum.skylang.config.Provider;
import com.adeptum.skylang.config.ReasoningEffort;
import com.adeptum.skylang.config.SkyConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Connects to the configured provider (OpenAI or Anthropic) through LangChain4j. The model is
 * built lazily on first use, so a fully-frozen build resolves no credentials.
 */
@Slf4j
public final class LangChain4jLlm implements Llm {

    private static final int MAX_TOKENS = 4096;
    /** OpenAI reasoning models spend the completion budget on hidden reasoning, so give it room. */
    private static final int REASONING_MAX_TOKENS = 16384;
    private static final int MAX_DEGRADE_ATTEMPTS = 3;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /**
     * OpenAI renamed the completion-length cap: newer models require
     * {@code max_completion_tokens} and reject the legacy {@code max_tokens}, while some older
     * models accept only the legacy name.
     */
    enum TokenLimit { COMPLETION, LEGACY }

    /**
     * The request parameters some models reject — the token-limit spelling, whether to send an
     * OpenAI {@code reasoning_effort}, and whether to enable Anthropic thinking. We start with the
     * richest setting the config asks for and drop whatever a given model refuses, so one build
     * works across model generations without a per-model table.
     */
    record Build(TokenLimit tokenLimit, boolean reasoning, boolean thinking) {
    }

    private final Supplier<SkyConfig> configSupplier;
    private ChatModel model;
    private Build cachedBuild;
    private volatile Build resolvedBuild;   // the build that last succeeded; the next call starts here

    public LangChain4jLlm(Supplier<SkyConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public LangChain4jLlm(SkyConfig config) {
        this(() -> config);
    }

    /** Anthropic thinking needs at least this many budget tokens to be worth enabling. */
    private static final int ANTHROPIC_MIN_THINKING = 1024;

    /** The richest parameter set the configuration asks for, before any model refuses a part. */
    private static Build initialBuild(SkyConfig config) {
        boolean reasoning = config.provider() == Provider.OPENAI;
        boolean thinking = config.provider() == Provider.ANTHROPIC
                && thinkingBudget(config.reasoningEffort()) >= ANTHROPIC_MIN_THINKING;
        return new Build(TokenLimit.COMPLETION, reasoning, thinking);
    }

    /** Bodies synthesize concurrently, so the cache must be safe to race. */
    private synchronized ChatModel modelFor(SkyConfig config, Build build) {
        if (model == null || !build.equals(cachedBuild)) {
            model = buildModel(config, build);
            cachedBuild = build;
        }
        return model;
    }

    static ChatModel buildModel(SkyConfig config, Build build) {
        return switch (config.provider()) {
            case ANTHROPIC -> {
                AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.model())
                        .timeout(TIMEOUT);
                if (build.thinking()) {
                    int budget = thinkingBudget(config.reasoningEffort());
                    builder = builder.thinkingType("enabled")
                            .thinkingBudgetTokens(budget)
                            .temperature(1.0)                 // Anthropic requires 1 with thinking
                            .maxTokens(budget + MAX_TOKENS);   // output must exceed the thinking budget
                } else {
                    builder = builder.maxTokens(MAX_TOKENS);
                }
                yield builder.build();
            }
            case OPENAI -> {
                int cap = build.reasoning() ? REASONING_MAX_TOKENS : MAX_TOKENS;
                OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.model())
                        .timeout(TIMEOUT);
                builder = build.tokenLimit() == TokenLimit.LEGACY
                        ? builder.maxTokens(cap) : builder.maxCompletionTokens(cap);
                if (build.reasoning()) {
                    builder = builder.reasoningEffort(config.reasoningEffort().id());
                }
                yield builder.build();
            }
        };
    }

    /**
     * The Anthropic thinking budget an effort maps to. A bare number is used directly as the token
     * budget; the common named levels map to sensible budgets; {@code minimal}/{@code none}/
     * {@code off} disable thinking; any other named level still gets a middle budget so a
     * provider-specific value is not silently ignored.
     */
    static int thinkingBudget(ReasoningEffort effort) {
        String value = effort.id();
        if (value.matches("\\d{1,6}")) {
            return Integer.parseInt(value);
        }
        return switch (value) {
            case "minimal", "none", "off" -> 0;
            case "low" -> 2048;
            case "medium" -> 8192;
            case "high" -> 16384;
            default -> 8192;
        };
    }

    @Override
    public String complete(String system, String userMessage) {
        SkyConfig config = configSupplier.get();   // config errors propagate before the call
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(system), UserMessage.from(userMessage))
                .build();
        log.debug("LLM request to {}/{} ({} + {} chars)\n=== system ===\n{}\n=== user ===\n{}",
                config.provider().id(), config.model(), system.length(), userMessage.length(),
                system, userMessage);
        Build build = resolvedBuild != null ? resolvedBuild : initialBuild(config);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_DEGRADE_ATTEMPTS; attempt++) {
            try {
                String text = modelFor(config, build).chat(request).aiMessage().text();
                resolvedBuild = build;   // remember what worked, so later calls skip the dance
                log.debug("LLM reply ({} chars)\n{}", text == null ? 0 : text.length(), text);
                return text;
            } catch (RuntimeException e) {
                last = e;
                Build degraded = degrade(config, build, e.getMessage());
                if (degraded == null) {
                    break;
                }
                build = degraded;
            }
        }
        throw new SynthException("LLM call failed: " + last.getMessage(), last);
    }

    /** Make a tiny live call to confirm the credentials work; throws on failure. */
    public static void validate(SkyConfig config) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("Reply with the single word: OK"))
                .build();
        ChatResponse response = chatResilient(config, request);
        String text = response.aiMessage().text();
        if (text == null || text.isBlank()) {
            throw new SynthException("validation call returned an empty response");
        }
    }

    /** Run a request, dropping each parameter a model rejects until it succeeds or none remain. */
    private static ChatResponse chatResilient(SkyConfig config, ChatRequest request) {
        Build build = initialBuild(config);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_DEGRADE_ATTEMPTS; attempt++) {
            try {
                return buildModel(config, build).chat(request);
            } catch (RuntimeException e) {
                last = e;
                Build degraded = degrade(config, build, e.getMessage());
                if (degraded == null) {
                    break;
                }
                build = degraded;
            }
        }
        throw last;
    }

    /**
     * Drop the one parameter a model just rejected, monotonically (token spelling flips once to
     * legacy; reasoning and thinking only turn off), so the retry loop always terminates. Returns
     * the degraded build, or null when the error is not about a parameter we can drop.
     */
    static Build degrade(SkyConfig config, Build build, String message) {
        if (config.provider() == Provider.OPENAI) {
            if (build.tokenLimit() == TokenLimit.COMPLETION && isTokenLimitError(message)) {
                return new Build(TokenLimit.LEGACY, build.reasoning(), build.thinking());
            }
            if (build.reasoning() && isUnsupportedParam(message, "reasoning_effort")) {
                return new Build(build.tokenLimit(), false, build.thinking());
            }
        } else if (build.thinking()
                && (isUnsupportedParam(message, "thinking") || isUnsupportedParam(message, "budget_tokens"))) {
            return new Build(build.tokenLimit(), build.reasoning(), false);
        }
        return null;
    }

    /** True when a provider rejected the request because of the token-limit parameter's spelling. */
    static boolean isTokenLimitError(String message) {
        return isUnsupportedParam(message, "max_tokens")
                || isUnsupportedParam(message, "max_completion_tokens");
    }

    /** True when the message reports that the named request parameter is unsupported. */
    static boolean isUnsupportedParam(String message, String param) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains(param.toLowerCase(Locale.ROOT))
                && (m.contains("unsupported") || m.contains("not supported"));
    }
}
