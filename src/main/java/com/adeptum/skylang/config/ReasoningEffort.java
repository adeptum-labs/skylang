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
 * How hard the model should think before answering. Maps to OpenAI's {@code reasoning_effort} and
 * to Anthropic's extended-thinking budget. Onboarding defaults to {@link #HIGH}.
 */
public enum ReasoningEffort {

    LOW, MEDIUM, HIGH;

    /** The default effort a fresh configuration is given. */
    public static final ReasoningEffort DEFAULT = HIGH;

    /** The lowercase form used on the wire and in the config file. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ReasoningEffort parse(String text) {
        if (text != null) {
            String t = text.trim().toUpperCase(Locale.ROOT);
            for (ReasoningEffort effort : values()) {
                if (effort.name().equals(t)) {
                    return effort;
                }
            }
        }
        throw new ConfigException("unknown reasoning effort '" + text
                + "' (expected: low, medium, or high)");
    }
}
