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

package com.adeptum.skylang.front.ast;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The typed abstract syntax tree for the SkyLang thin slice. All nodes are immutable
 * records nested here so the sealed {@link Expr} / {@link Result} hierarchies stay in one
 * compilation unit.
 */
public final class Ast {

    private Ast() {
    }

    public record Module(String name, List<Entity> entities, List<Service> services) {
    }

    public record Entity(String name, List<Field> fields) {
    }

    /** A field of an entity. {@code id} reflects {@code @id}; {@code min} reflects {@code @min(n)}. */
    public record Field(String name, TypeRef type, boolean id, OptionalLong min) {
    }

    public record Service(String name, List<Method> methods) {
    }

    public record Param(String name, TypeRef type) {
    }

    /** A reference to a type by name: {@code Int}, {@code Text}, or an entity name. */
    public record TypeRef(String name) {
    }

    public record Method(String name,
                         List<Param> params,
                         TypeRef returnType,
                         Optional<String> intent,
                         List<Expr> requires,
                         List<Expr> ensures,
                         List<Example> examples) {
    }

    /** A concrete {@code example call(...) -> result} case. */
    public record Example(CallExpr call, Result result) {
    }

    // ----- example results ---------------------------------------------------

    public sealed interface Result permits ExprResult, EntityResult {
    }

    /** {@code -> 5} — the result is a plain expression the call must equal. */
    public record ExprResult(Expr value) implements Result {
    }

    /** {@code -> a Product with stock 8} — the result is an entity with expected fields. */
    public record EntityResult(String typeName, List<FieldExpect> fields) implements Result {
    }

    /** {@code stock 8} — the named field of the result is expected to equal {@code expected}. */
    public record FieldExpect(String field, Expr expected) {
    }

    // ----- expressions -------------------------------------------------------

    public sealed interface Expr
            permits IntLit, StrLit, NameExpr, MemberExpr, CallExpr, BinExpr {
    }

    public record IntLit(long value) implements Expr {
    }

    /** String literal with surrounding quotes already stripped and escapes resolved. */
    public record StrLit(String value) implements Expr {
    }

    public record NameExpr(String name) implements Expr {
    }

    public record MemberExpr(Expr target, String field) implements Expr {
    }

    /** A call or constructor: {@code restock(...)} or {@code Product(...)}. */
    public record CallExpr(String callee, List<Expr> args) implements Expr {
    }

    public record BinExpr(String op, Expr left, Expr right) implements Expr {
    }
}
