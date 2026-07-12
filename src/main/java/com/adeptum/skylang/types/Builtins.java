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

    /** The effects a service may declare with {@code uses}; all five bound by the JVM profile. */
    public static final List<String> EFFECTS = List.of("db", "http", "clock", "mail", "auth");

    /** The shape {@code Email} enforces at construction; pragmatic rather than full RFC 5322. */
    public static final String EMAIL_REGEX = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    /** An ISO-4217-style three-letter code, consistent with the currency a Money carries. */
    public static final String CURRENCY_REGEX = "^[A-Z]{3}$";

    public static final Ty.NamedTy EMAIL = new Ty.NamedTy("Email", Ty.TEXT, new Ast.Matching(EMAIL_REGEX));
    public static final Ty.NamedTy CURRENCY =
            new Ty.NamedTy("Currency", Ty.TEXT, new Ast.Matching(CURRENCY_REGEX));
    public static final Ty.NamedTy PERCENTAGE = new Ty.NamedTy("Percentage", Ty.INT,
            new Ast.Range(java.util.OptionalLong.of(0), java.util.OptionalLong.of(100)));

    private static final Map<String, Ty.NamedTy> REFINED = Map.of(
            "Email", EMAIL, "Currency", CURRENCY, "Percentage", PERCENTAGE);

    private static final Map<String, Ty.Prim> PRIMS = Map.of(
            "Int", Ty.INT, "Text", Ty.TEXT, "Bool", Ty.BOOL,
            "Money", Ty.MONEY, "Instant", Ty.INSTANT, "Bytes", Ty.BYTES,
            "Date", Ty.DATE, "DateTime", Ty.DATETIME);

    private static final Map<String, Integer> GENERIC_ARITY = Map.of(
            "List", 1, "Set", 1, "Maybe", 1, "Secret", 1, "Map", 2);

    private Builtins() {
    }

    /** Resolve a built-in, non-container type name: a primitive or a built-in refinement. */
    public static Optional<Ty> resolve(String name) {
        Ty.NamedTy refined = REFINED.get(name);
        if (refined != null) {
            return Optional.of(refined);
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
        return PRIMS.containsKey(name) || REFINED.containsKey(name) || GENERIC_ARITY.containsKey(name);
    }
}
