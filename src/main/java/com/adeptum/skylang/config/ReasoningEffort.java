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

/**
 * How hard the model should think before answering. It is deliberately a free value rather than a
 * closed set: the common levels are {@code low}, {@code medium}, and {@code high}, but providers and
 * individual models add their own — OpenAI's {@code minimal}, model-specific tiers, or even a bare
 * token budget for Anthropic thinking. Whatever you set is passed through and the provider is the
 * authority on what it accepts. Onboarding defaults to {@link #DEFAULT}.
 */
public record ReasoningEffort(String value) {

    public ReasoningEffort {
        if (value == null || value.isBlank()) {
            throw new ConfigException("reasoning effort must not be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
    }

    /** The common levels, offered as conveniences; any other value is equally valid. */
    public static final ReasoningEffort MINIMAL = new ReasoningEffort("minimal");
    public static final ReasoningEffort LOW = new ReasoningEffort("low");
    public static final ReasoningEffort MEDIUM = new ReasoningEffort("medium");
    public static final ReasoningEffort HIGH = new ReasoningEffort("high");

    /** The default effort a fresh configuration is given. */
    public static final ReasoningEffort DEFAULT = HIGH;

    /** The lowercase form used on the wire and in the config file. */
    public String id() {
        return value;
    }

    public static ReasoningEffort parse(String text) {
        return new ReasoningEffort(text);
    }
}
