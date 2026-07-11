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

import com.adeptum.skylang.config.SkyConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Connects to the configured provider (OpenAI or Anthropic) through LangChain4j. The model is
 * built lazily on first use, so a fully-frozen build resolves no credentials.
 */
public final class LangChain4jLlm implements Llm {

    private static final int MAX_TOKENS = 4096;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final Supplier<SkyConfig> configSupplier;
    private ChatModel model;

    public LangChain4jLlm(Supplier<SkyConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public LangChain4jLlm(SkyConfig config) {
        this(() -> config);
    }

    /** Bodies synthesize concurrently, so the lazy init must be safe to race. */
    private synchronized ChatModel model() {
        if (model == null) {
            model = buildModel(configSupplier.get());
        }
        return model;
    }

    static ChatModel buildModel(SkyConfig config) {
        return switch (config.provider()) {
            case ANTHROPIC -> AnthropicChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.model())
                    .maxTokens(MAX_TOKENS)
                    .timeout(TIMEOUT)
                    .build();
            case OPENAI -> OpenAiChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.model())
                    .maxTokens(MAX_TOKENS)
                    .timeout(TIMEOUT)
                    .build();
        };
    }

    @Override
    public String complete(String system, String userMessage) {
        ChatModel chatModel = model();   // credential/config errors propagate before the call
        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(system), UserMessage.from(userMessage))
                    .build());
            return response.aiMessage().text();
        } catch (RuntimeException e) {
            throw new SynthException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /** Make a tiny live call to confirm the credentials work; throws on failure. */
    public static void validate(SkyConfig config) {
        ChatResponse response = buildModel(config).chat(ChatRequest.builder()
                .messages(UserMessage.from("Reply with the single word: OK"))
                .build());
        String text = response.aiMessage().text();
        if (text == null || text.isBlank()) {
            throw new SynthException("validation call returned an empty response");
        }
    }
}
