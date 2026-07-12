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
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * A resolved SkyLang type: a primitive, an entity, a declared refined type (nominal),
 * an inline range refinement (structural), or a parameterised container. Refined types
 * erase to their base for operator typing and lowering; their predicates are enforced
 * at construction points.
 */
public sealed interface Ty permits Ty.Prim, Ty.EntityTy, Ty.NamedTy, Ty.AnonRefined, Ty.GenericTy {

    Prim INT = new Prim("Int");
    Prim TEXT = new Prim("Text");
    Prim BOOL = new Prim("Bool");
    Prim MONEY = new Prim("Money");
    Prim INSTANT = new Prim("Instant");
    Prim DATE = new Prim("Date");
    Prim DATETIME = new Prim("DateTime");
    Prim BYTES = new Prim("Bytes");

    record Prim(String name) implements Ty {
        @Override
        public String toString() {
            return name;
        }
    }

    record EntityTy(String name) implements Ty {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A declared refined type ({@code type Slug = ...}): nominal — two names never mix. */
    record NamedTy(String name, Prim base, Ast.Refinement refinement) implements Ty {
        @Override
        public String toString() {
            return name;
        }
    }

    /** An inline refinement like {@code Int(0..100)}: structural — identity is base plus bounds. */
    record AnonRefined(Prim base, OptionalLong lo, OptionalLong hi) implements Ty {
        @Override
        public String toString() {
            return base.name() + "(" + (lo.isPresent() ? lo.getAsLong() : "") + ".."
                    + (hi.isPresent() ? hi.getAsLong() : "") + ")";
        }
    }

    /** {@code List<T>}, {@code Set<T>}, {@code Map<K,V>}, {@code Maybe<T>}, {@code Secret<T>}. */
    record GenericTy(String kind, List<Ty> args) implements Ty {
        @Override
        public String toString() {
            return kind + "<" + args.stream().map(Ty::toString).collect(Collectors.joining(", ")) + ">";
        }
    }

    static Ty entity(String name) {
        return new EntityTy(name);
    }

    static Ty list(Ty element) {
        return new GenericTy("List", List.of(element));
    }

    /** The representation type operators and lowering see: refinements strip to their base. */
    default Ty erased() {
        return switch (this) {
            case NamedTy n -> n.base();
            case AnonRefined a -> a.base();
            default -> this;
        };
    }

    default boolean isInt() {
        return erased().equals(INT);
    }

    default boolean isBool() {
        return erased().equals(BOOL);
    }

    default boolean isSecret() {
        return this instanceof GenericTy g && g.kind().equals("Secret");
    }
}
