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

/**
 * One resolved dependency: the logical name and constraint the manifest declared, the
 * registry version that satisfied it, the pinned native coordinates (the mapping's whole
 * transitive closure), and the package prefixes those coordinates provide — the linter's
 * evidence that a body reached for a library the manifest never declared.
 */
public record Resolved(String name, String constraint, String version,
                       List<String> coordinates, List<String> packages) {
}
