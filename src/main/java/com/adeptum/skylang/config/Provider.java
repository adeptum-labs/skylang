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

import java.util.Locale;

/** An LLM provider SkyLang can synthesize through. */
public enum Provider {

    ANTHROPIC("claude-opus-4-8", "ANTHROPIC_API_KEY"),
    OPENAI("gpt-4o", "OPENAI_API_KEY");

    private final String defaultModel;
    private final String envVar;

    Provider(String defaultModel, String envVar) {
        this.defaultModel = defaultModel;
        this.envVar = envVar;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** The environment variable that supplies this provider's key when no config file exists. */
    public String envVar() {
        return envVar;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Provider parse(String text) {
        if (text != null) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            for (Provider p : values()) {
                if (p.id().equals(t)) {
                    return p;
                }
            }
        }
        throw new ConfigException("unknown provider '" + text + "' (expected: anthropic or openai)");
    }
}
