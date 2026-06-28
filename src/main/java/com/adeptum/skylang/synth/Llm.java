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

/**
 * A provider-neutral view of a chat model: a system prompt plus a user message in, the model's
 * text out. SkyLang-specific prompt construction lives in {@link PromptBuilder}; the concrete
 * connection (OpenAI or Anthropic, via LangChain4j) lives in {@link LangChain4jLlm}. Trivial to
 * stub in tests.
 */
public interface Llm {

    String complete(String system, String userMessage);
}
