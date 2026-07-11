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

package com.adeptum.skylang.deps;

import java.util.List;
import java.util.Map;

/**
 * The dependency budget a build runs under: what the manifest declared (resolved), and every
 * package prefix the active registry knows — the linter's watch list. Like the effects
 * budget, it bounds what any body, synthesized or hand-written, may reach.
 */
public record Budget(List<Resolved> declared, Map<String, String> knownPrefixes) {

    public static final Budget NONE = new Budget(List.of(), Map.of());

    public boolean declares(String name) {
        return declared.stream().anyMatch(r -> r.name().equals(name));
    }
}
