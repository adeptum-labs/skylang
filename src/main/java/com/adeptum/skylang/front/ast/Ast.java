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

import java.math.BigDecimal;
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

    public record Module(String name, List<TypeDecl> types, List<Entity> entities,
                         List<Service> services, List<View> views) {
        public Module(String name, List<Entity> entities, List<Service> services, List<View> views) {
            this(name, List.of(), entities, services, views);
        }
    }

    /**
     * Data with identity and invariants; {@code values} seeds and closes an enum-like
     * instance set. The freeze hash covers this record's string form, so {@code toString}
     * is pinned: byte-identical to the original record format unless {@code values} is set.
     */
    public record Entity(String name, List<Field> fields, List<String> values) {
        public Entity(String name, List<Field> fields) {
            this(name, fields, List.of());
        }

        @Override
        public String toString() {
            return "Entity[name=" + name + ", fields=" + fields
                    + (values.isEmpty() ? "" : ", values=" + values) + "]";
        }
    }

    /**
     * A field of an entity. {@code id}/{@code unique}/{@code min} reflect the annotations;
     * {@code defaultValue} reflects {@code = expr}. The freeze hash of every method and view
     * covers this record's string form, so {@code toString} is pinned: it must stay byte-identical
     * to the original record format for fields that use none of the newer attributes, and only
     * append them when set.
     */
    public record Field(String name, Type type, boolean id, OptionalLong min,
                        boolean unique, Optional<Expr> defaultValue) {
        public Field(String name, Type type, boolean id, OptionalLong min) {
            this(name, type, id, min, false, Optional.empty());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Field[name=").append(name).append(", type=").append(type)
                    .append(", id=").append(id).append(", min=").append(min);
            if (unique) {
                sb.append(", unique=true");
            }
            defaultValue.ifPresent(d -> sb.append(", default=").append(d));
            return sb.append(']').toString();
        }
    }

    /**
     * Behaviour with no state of its own; {@code uses} is the effects budget. The freeze
     * hash covers this record's string form, so {@code toString} is pinned: byte-identical
     * to the original record format unless a budget is declared.
     */
    public record Service(String name, List<Method> methods, List<String> uses) {
        public Service(String name, List<Method> methods) {
            this(name, methods, List.of());
        }

        @Override
        public String toString() {
            return "Service[name=" + name + ", methods=" + methods
                    + (uses.isEmpty() ? "" : ", uses=" + uses) + "]";
        }
    }

    public record Param(String name, Type type) {
    }

    // ----- types ---------------------------------------------------------------

    public sealed interface Type permits TypeRef, GenericType, RangedType {
        /** The type as written in SkyLang source, e.g. {@code [Product]}, {@code Maybe<User>}, {@code Int(0..100)}. */
        String sky();
    }

    /** A reference to a type by name: {@code Int}, {@code Text}, or an entity name; {@code list} marks {@code [E]}. */
    public record TypeRef(String name, boolean list) implements Type {
        public TypeRef(String name) {
            this(name, false);
        }

        @Override
        public String sky() {
            return list ? "[" + name + "]" : name;
        }
    }

    /** A parameterised type: {@code Maybe<T>}, {@code Secret<T>}, {@code List<T>}, {@code Map<K,V>}, {@code Set<T>}. */
    public record GenericType(String name, List<Type> args) implements Type {
        @Override
        public String sky() {
            StringBuilder sb = new StringBuilder(name).append('<');
            for (int i = 0; i < args.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(args.get(i).sky());
            }
            return sb.append('>').toString();
        }
    }

    /** An inline range refinement: {@code Int(0..100)} or {@code Text(1..120)}; open ends are empty. */
    public record RangedType(String base, OptionalLong lo, OptionalLong hi) implements Type {
        @Override
        public String sky() {
            return base + "(" + (lo.isPresent() ? lo.getAsLong() : "") + ".."
                    + (hi.isPresent() ? hi.getAsLong() : "") + ")";
        }
    }

    /** {@code type Slug = Text matching /re/} — a named refined type over a base type. */
    public record TypeDecl(String name, String base, Refinement refinement) {
    }

    public sealed interface Refinement permits Range, Matching, Where {
    }

    /** {@code Int(0..100)} / {@code Int(1..)} — a numeric or length range; open ends are empty. */
    public record Range(OptionalLong lo, OptionalLong hi) implements Refinement {
    }

    /** {@code matching /^[a-z]+$/} — the regular expression with its slash delimiters stripped. */
    public record Matching(String regex) implements Refinement {
    }

    /** {@code where amount > 0} — an arbitrary predicate over the base type. */
    public record Where(Expr predicate) implements Refinement {
    }

    /**
     * A method: signature, drivers and contracts. The freeze hash covers this record's
     * string form, so {@code toString} is pinned: byte-identical to the original record
     * format unless the method declares {@code raises} clauses.
     */
    public record Method(String name,
                         List<Param> params,
                         Type returnType,
                         Optional<String> intent,
                         List<Expr> requires,
                         List<Expr> ensures,
                         List<Example> examples,
                         List<Raise> raises) {
        public Method(String name, List<Param> params, Type returnType, Optional<String> intent,
                      List<Expr> requires, List<Expr> ensures, List<Example> examples) {
            this(name, params, returnType, intent, requires, ensures, examples, List.of());
        }

        @Override
        public String toString() {
            return "Method[name=" + name + ", params=" + params + ", returnType=" + returnType
                    + ", intent=" + intent + ", requires=" + requires + ", ensures=" + ensures
                    + ", examples=" + examples
                    + (raises.isEmpty() ? "" : ", raises=" + raises) + "]";
        }
    }

    /** {@code raises NotFound when <condition>} — a named failure and when it must occur. */
    public record Raise(String error, RaiseCondition condition) {
    }

    public sealed interface RaiseCondition permits CondExpr, NoSuch, AlreadyRegistered {
    }

    /** A formal boolean condition: {@code when units <= 0}. */
    public record CondExpr(Expr expr) implements RaiseCondition {
    }

    /** {@code when no product has that id} — the lookup by the named field must find nothing. */
    public record NoSuch(String entityWord, String fieldWord) implements RaiseCondition {
    }

    /** {@code when email already registered} — the @unique value is already stored. */
    public record AlreadyRegistered(Expr value) implements RaiseCondition {
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
                       List<Action> actions, List<Expect> expects, List<Appears> appears) {
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
    public record AskArg(Type type) implements ActionArg {
    }

    public sealed interface Expect permits ExpectColumns, ExpectActionKind {
    }

    /** {@code table has columns (name, stock)}. */
    public record ExpectColumns(String subject, List<String> columns) implements Expect {
    }

    /** {@code action "Restock" is button}. */
    public record ExpectActionKind(String label, String kind) implements Expect {
    }

    public sealed interface Appears permits AppearsPlacement, AppearsStyle, AppearsColumnOrder {
    }

    /** {@code appears action "Restock" in toolbar} — the control renders inside a named region. */
    public record AppearsPlacement(String label, String region) implements Appears {
    }

    /** {@code appears rows is compact} — a style/density marker on a subject. */
    public record AppearsStyle(String subject, String value) implements Appears {
    }

    /** {@code appears columns (name, stock)} — the rendered column order. */
    public record AppearsColumnOrder(List<String> columns) implements Appears {
    }

    // ----- expressions -------------------------------------------------------

    public sealed interface Expr
            permits IntLit, StrLit, BoolLit, MoneyLit, NameExpr, MemberExpr, CallExpr, BinExpr,
            NotExpr, OldExpr, EmptyCheck, AggExpr {
    }

    /** {@code not <expr>}. */
    public record NotExpr(Expr value) implements Expr {
    }

    /** {@code old(expr)} — the value of {@code expr} before the method ran (ensures only). */
    public record OldExpr(Expr value) implements Expr {
    }

    /** {@code <collection> is empty}. */
    public record EmptyCheck(Expr value) implements Expr {
    }

    /** {@code sum of (p.stock for p in products where ...)} / {@code count of (...)}. */
    public record AggExpr(String kind, Expr value, String var, AggSource source,
                          Optional<Expr> where) implements Expr {
    }

    public sealed interface AggSource permits AllOf, SourceExpr {
    }

    /** {@code all products} — every stored entity of the kind the word names. */
    public record AllOf(String word) implements AggSource {
    }

    public record SourceExpr(Expr expr) implements AggSource {
    }

    public record IntLit(long value) implements Expr {
    }

    /** String literal with surrounding quotes already stripped and escapes resolved. */
    public record StrLit(String value) implements Expr {
    }

    public record BoolLit(boolean value) implements Expr {
    }

    /** {@code 9.99eur} — an exact decimal amount plus an upper-cased ISO currency code. */
    public record MoneyLit(BigDecimal amount, String currency) implements Expr {
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
