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

    public record Module(String name, List<Entity> entities, List<Service> services, List<View> views) {
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

    /** A reference to a type by name: {@code Int}, {@code Text}, or an entity name; {@code list} marks {@code [E]}. */
    public record TypeRef(String name, boolean list) {
        public TypeRef(String name) {
            this(name, false);
        }
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

    // ----- views -------------------------------------------------------------

    public record View(String name, Optional<String> route, Shows shows,
                       List<Action> actions, List<Expect> expects) {
    }

    /** A qualified query call like {@code Catalog.all()}. */
    public record QualifiedCall(String service, String method, List<Expr> args) {
    }

    public record Shows(QualifiedCall query, Optional<Projection> projection) {
    }

    /** {@code a table of (name, stock)} — {@code kind} is "table" or "form". */
    public record Projection(String kind, List<String> columns) {
    }

    /** {@code action "Restock" on row -> Catalog.restock(row.id, ask Int)}. */
    public record Action(String label, String rowVar, String service, String method, List<ActionArg> args) {
    }

    public sealed interface ActionArg permits ExprArg, AskArg {
    }

    /** An action argument that is an expression over the row, e.g. {@code row.id}. */
    public record ExprArg(Expr value) implements ActionArg {
    }

    /** An action argument the user is prompted for, e.g. {@code ask Int}. */
    public record AskArg(TypeRef type) implements ActionArg {
    }

    public sealed interface Expect permits ExpectColumns, ExpectActionKind {
    }

    /** {@code table has columns (name, stock)}. */
    public record ExpectColumns(String subject, List<String> columns) implements Expect {
    }

    /** {@code action "Restock" is button}. */
    public record ExpectActionKind(String label, String kind) implements Expect {
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
