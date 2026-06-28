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

/** The resolved LLM credentials SkyLang synthesizes with: provider, API key, and model. */
public record SkyConfig(Provider provider, String apiKey, String model) {

    public SkyConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigException("missing API key for provider " + provider);
        }
        if (model == null || model.isBlank()) {
            model = provider.defaultModel();
        }
    }

    /** The key with all but a short prefix/suffix hidden, for display. */
    public String maskedKey() {
        String k = apiKey;
        if (k.length() <= 12) {
            return "****";
        }
        return k.substring(0, 6) + "…" + k.substring(k.length() - 4);
    }
}
