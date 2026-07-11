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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token-limit fallback hinges on recognising the provider error that asks for the other
 * spelling of the completion-length cap, so this pins that detection in both directions.
 */
class LangChain4jLlmTest {

    @Test
    void detectsARejectionOfTheLegacyTokenParameter() {
        // The exact shape OpenAI returns for a newer model given max_tokens.
        String error = "{ \"error\": { \"message\": \"Unsupported parameter: 'max_tokens' is not "
                + "supported with this model. Use 'max_completion_tokens' instead.\", "
                + "\"type\": \"invalid_request_error\", \"param\": \"max_tokens\", "
                + "\"code\": \"unsupported_parameter\" } }";
        assertTrue(LangChain4jLlm.isTokenLimitError(error));
    }

    @Test
    void detectsARejectionOfTheModernTokenParameter() {
        // The reverse: an older model given max_completion_tokens.
        assertTrue(LangChain4jLlm.isTokenLimitError(
                "Unsupported parameter: 'max_completion_tokens' is not supported with this model. "
                        + "Use 'max_tokens' instead."));
    }

    @Test
    void leavesUnrelatedFailuresAlone() {
        assertFalse(LangChain4jLlm.isTokenLimitError(null));
        assertFalse(LangChain4jLlm.isTokenLimitError("401 Unauthorized: incorrect API key provided"));
        assertFalse(LangChain4jLlm.isTokenLimitError(
                "The model `gpt-5.6-terra` does not exist or you do not have access to it."));
    }
}
