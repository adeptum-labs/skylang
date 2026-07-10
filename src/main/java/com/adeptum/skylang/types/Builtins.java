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

package com.adeptum.skylang.types;

import com.adeptum.skylang.front.ast.Ast;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single source of truth for SkyLang's built-in type vocabulary, shared by the
 * checker and the backend: primitive names, the {@code Email} refinement, and the
 * container kinds with their arities.
 */
public final class Builtins {

    /** The effects a service may declare with {@code uses}; all four bound by the JVM profile. */
    public static final List<String> EFFECTS = List.of("db", "http", "clock", "mail");

    /** The shape {@code Email} enforces at construction; pragmatic rather than full RFC 5322. */
    public static final String EMAIL_REGEX = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    public static final Ty.NamedTy EMAIL = new Ty.NamedTy("Email", Ty.TEXT, new Ast.Matching(EMAIL_REGEX));

    private static final Map<String, Ty.Prim> PRIMS = Map.of(
            "Int", Ty.INT, "Text", Ty.TEXT, "Bool", Ty.BOOL,
            "Money", Ty.MONEY, "Instant", Ty.INSTANT, "Bytes", Ty.BYTES);

    private static final Map<String, Integer> GENERIC_ARITY = Map.of(
            "List", 1, "Set", 1, "Maybe", 1, "Secret", 1, "Map", 2);

    private Builtins() {
    }

    /** Resolve a built-in, non-container type name: a primitive or {@code Email}. */
    public static Optional<Ty> resolve(String name) {
        if (name.equals("Email")) {
            return Optional.of(EMAIL);
        }
        return Optional.ofNullable(PRIMS.get(name));
    }

    public static Optional<Ty.Prim> prim(String name) {
        return Optional.ofNullable(PRIMS.get(name));
    }

    /** The argument count of a container kind, or empty if {@code name} is not one. */
    public static Optional<Integer> genericArity(String name) {
        return Optional.ofNullable(GENERIC_ARITY.get(name));
    }

    /** True when the name is claimed by the language and cannot name an entity or type decl. */
    public static boolean isReserved(String name) {
        return PRIMS.containsKey(name) || name.equals("Email") || GENERIC_ARITY.containsKey(name);
    }
}
