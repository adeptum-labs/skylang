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
import com.adeptum.skylang.config.SkyConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Connects to the configured provider (OpenAI or Anthropic) through LangChain4j. The model is
 * built lazily on first use, so a fully-frozen build resolves no credentials.
 */
public final class LangChain4jLlm implements Llm {

    private static final int MAX_TOKENS = 4096;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /**
     * OpenAI renamed the completion-length cap: newer models require
     * {@code max_completion_tokens} and reject the legacy {@code max_tokens}, while some older
     * models accept only the legacy name. We try one spelling and fall back to the other, so the
     * client is not tied to any single generation of models.
     */
    private enum TokenLimit { COMPLETION, LEGACY }

    private final Supplier<SkyConfig> configSupplier;
    private ChatModel model;
    private TokenLimit tokenLimit = TokenLimit.COMPLETION;
    private boolean tokenLimitFlipped;

    public LangChain4jLlm(Supplier<SkyConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public LangChain4jLlm(SkyConfig config) {
        this(() -> config);
    }

    /** Bodies synthesize concurrently, so the lazy init must be safe to race. */
    private synchronized ChatModel model() {
        if (model == null) {
            model = buildModel(configSupplier.get(), tokenLimit);
        }
        return model;
    }

    static ChatModel buildModel(SkyConfig config, TokenLimit tokenLimit) {
        return switch (config.provider()) {
            case ANTHROPIC -> AnthropicChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.model())
                    .maxTokens(MAX_TOKENS)
                    .timeout(TIMEOUT)
                    .build();
            case OPENAI -> {
                OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                        .apiKey(config.apiKey())
                        .modelName(config.model())
                        .timeout(TIMEOUT);
                yield (tokenLimit == TokenLimit.LEGACY
                        ? builder.maxTokens(MAX_TOKENS)
                        : builder.maxCompletionTokens(MAX_TOKENS)).build();
            }
        };
    }

    @Override
    public String complete(String system, String userMessage) {
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(system), UserMessage.from(userMessage))
                .build();
        try {
            return model().chat(request).aiMessage().text();   // config errors propagate before the call
        } catch (RuntimeException e) {
            if (flipTokenLimitIfNeeded(e)) {
                try {
                    return model().chat(request).aiMessage().text();
                } catch (RuntimeException retry) {
                    throw new SynthException("LLM call failed: " + retry.getMessage(), retry);
                }
            }
            throw new SynthException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * On an OpenAI token-parameter rejection, switch spellings once and rebuild the cached model,
     * so every later call uses what worked. Returns whether a retry is worth attempting.
     */
    private synchronized boolean flipTokenLimitIfNeeded(RuntimeException e) {
        SkyConfig config = configSupplier.get();
        if (config.provider() != Provider.OPENAI || !isTokenLimitError(e.getMessage())) {
            return false;
        }
        if (!tokenLimitFlipped) {
            tokenLimit = tokenLimit == TokenLimit.COMPLETION ? TokenLimit.LEGACY : TokenLimit.COMPLETION;
            tokenLimitFlipped = true;
            model = buildModel(config, tokenLimit);
        }
        return true;   // another racing caller may have flipped it; retry against the current model
    }

    /** Make a tiny live call to confirm the credentials work; throws on failure. */
    public static void validate(SkyConfig config) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("Reply with the single word: OK"))
                .build();
        ChatResponse response = chatWithTokenFallback(config, request);
        String text = response.aiMessage().text();
        if (text == null || text.isBlank()) {
            throw new SynthException("validation call returned an empty response");
        }
    }

    /** Try the modern token parameter, falling back to the legacy one for an older OpenAI model. */
    private static ChatResponse chatWithTokenFallback(SkyConfig config, ChatRequest request) {
        try {
            return buildModel(config, TokenLimit.COMPLETION).chat(request);
        } catch (RuntimeException e) {
            if (config.provider() == Provider.OPENAI && isTokenLimitError(e.getMessage())) {
                return buildModel(config, TokenLimit.LEGACY).chat(request);
            }
            throw e;
        }
    }

    /** True when a provider rejected the request because of the token-limit parameter's spelling. */
    static boolean isTokenLimitError(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return (m.contains("max_tokens") || m.contains("max_completion_tokens"))
                && (m.contains("unsupported") || m.contains("not supported"));
    }
}
