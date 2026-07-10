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

package com.adeptum.skylang.backend;

import com.adeptum.skylang.config.ConfigException;

/** The profile registry: one adapter per shipped target, and honest errors for the rest. */
public final class Profiles {

    private Profiles() {
    }

    public static Profile byId(String id) {
        return switch (id) {
            case JvmProfile.ID -> JvmProfile.INSTANCE;
            case "ts-node", "python" -> throw new ConfigException("profile '" + id
                    + "' is designed but not shipped in this build (available: " + available() + ")");
            default -> throw new ConfigException(
                    "unknown profile '" + id + "' (available: " + available() + ")");
        };
    }

    public static String available() {
        return JvmProfile.ID;
    }
}
