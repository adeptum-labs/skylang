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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hard-layer checker: it validates types, contracts, and examples deterministically,
 * with no model involvement. {@code sky check} runs exactly this. Fails fast on the first
 * problem with a human-readable message.
 */
public final class TypeChecker {

    /** entity name -> (field name -> type), in declaration order. */
    private final Map<String, LinkedHashMap<String, Ty>> entities = new HashMap<>();

    public void check(Ast.Module module) {
        // Pass 1: register entities and validate their fields.
        for (Ast.Entity e : module.entities()) {
            if (entities.containsKey(e.name())) {
                throw new CheckException("duplicate entity '" + e.name() + "'");
            }
            entities.put(e.name(), new LinkedHashMap<>());
        }
        for (Ast.Entity e : module.entities()) {
            LinkedHashMap<String, Ty> fields = entities.get(e.name());
            for (Ast.Field f : e.fields()) {
                Ty ty = resolveType(f.type(), "field '" + e.name() + "." + f.name() + "'");
                if (f.min().isPresent() && !ty.isInt()) {
                    throw new CheckException("@min is only valid on Int fields, but "
                            + e.name() + "." + f.name() + " is " + ty);
                }
                if (fields.put(f.name(), ty) != null) {
                    throw new CheckException("duplicate field '" + e.name() + "." + f.name() + "'");
                }
            }
        }

        // Pass 2: check every method and index its signature for view resolution.
        Map<String, Map<String, MethodSig>> services = new HashMap<>();
        for (Ast.Service s : module.services()) {
            Map<String, MethodSig> methods = services.computeIfAbsent(s.name(), k -> new HashMap<>());
            for (Ast.Method m : s.methods()) {
                checkMethod(s.name(), m);
                methods.put(m.name(), signatureOf(s.name(), m));
            }
        }

        // Pass 3: check every view against the entities and the service signatures.
        for (Ast.View v : module.views()) {
            checkView(v, services);
        }
    }

    // ----- views -------------------------------------------------------------

    /** A resolved service-method signature, used to type-check view queries and actions. */
    private record MethodSig(List<Ty> params, Ty returnType) {
    }

    private MethodSig signatureOf(String service, Ast.Method m) {
        List<Ty> params = new ArrayList<>();
        for (Ast.Param p : m.params()) {
            params.add(resolveType(p.type(), service + "." + m.name() + " parameter '" + p.name() + "'"));
        }
        return new MethodSig(params, resolveType(m.returnType(), service + "." + m.name() + " return type"));
    }

    private void checkView(Ast.View v, Map<String, Map<String, MethodSig>> services) {
        String where = "view " + v.name();

        if (v.shows() == null) {
            throw new CheckException(where + " has no data source: add a 'shows' clause");
        }

        // The query must resolve to a service method returning a list of an entity (the row type).
        Ast.QualifiedCall q = v.shows().query();
        MethodSig querySig = lookup(services, q.service(), q.method(), where + " shows");
        checkArgCount(where + " shows", q.service() + "." + q.method(), q.args().size(), querySig.params().size());
        for (int i = 0; i < q.args().size(); i++) {
            Ty actual = infer(q.args().get(i), Map.of(), where + " shows argument " + (i + 1));
            if (!actual.equals(querySig.params().get(i))) {
                throw new CheckException(where + " shows argument " + (i + 1) + " is " + actual
                        + " but " + q.service() + "." + q.method() + " expects " + querySig.params().get(i));
            }
        }
        Ty ret = querySig.returnType();
        if (!ret.list() || !entities.containsKey(ret.name())) {
            throw new CheckException(where + " shows: " + q.service() + "." + q.method()
                    + " must return a list of an entity (e.g. [Product]) to drive a view, but returns " + ret);
        }
        String rowType = ret.name();
        LinkedHashMap<String, Ty> rowFields = entities.get(rowType);

        // Projected columns must be fields of the row entity.
        if (v.shows().projection().isPresent()) {
            for (String col : v.shows().projection().get().columns()) {
                requireField(where + " shows", rowType, rowFields, col);
            }
        }

        // Each action must call a declared method with row-typed / prompted arguments of matching type.
        for (Ast.Action a : v.actions()) {
            String actionWhere = where + " action \"" + a.label() + "\"";
            MethodSig sig = lookup(services, a.service(), a.method(), actionWhere);
            checkArgCount(actionWhere, a.service() + "." + a.method(), a.args().size(), sig.params().size());
            Map<String, Ty> env = Map.of(a.rowVar(), Ty.entity(rowType));
            for (int i = 0; i < a.args().size(); i++) {
                Ty expected = sig.params().get(i);
                Ty actual = switch (a.args().get(i)) {
                    case Ast.ExprArg ea -> infer(ea.value(), env, actionWhere + " argument " + (i + 1));
                    case Ast.AskArg ak -> resolveType(ak.type(), actionWhere + " argument " + (i + 1));
                };
                if (!actual.equals(expected)) {
                    throw new CheckException(actionWhere + " argument " + (i + 1) + " is " + actual
                            + " but " + a.service() + "." + a.method() + " expects " + expected);
                }
            }
        }

        // Expectations must reference real columns / declared actions.
        for (Ast.Expect e : v.expects()) {
            switch (e) {
                case Ast.ExpectColumns ec -> {
                    for (String col : ec.columns()) {
                        requireField(where + " expect", rowType, rowFields, col);
                    }
                }
                case Ast.ExpectActionKind ak -> {
                    boolean declared = v.actions().stream().anyMatch(a -> a.label().equals(ak.label()));
                    if (!declared) {
                        throw new CheckException(where + " expect: no action labelled \"" + ak.label() + "\"");
                    }
                }
            }
        }

        // Appearance predicates must reference declared actions, real fields, or known style targets.
        for (Ast.Appears a : v.appears()) {
            switch (a) {
                case Ast.AppearsPlacement p -> {
                    boolean declared = v.actions().stream().anyMatch(act -> act.label().equals(p.label()));
                    if (!declared) {
                        throw new CheckException(where + " appears: no action labelled \"" + p.label() + "\"");
                    }
                }
                case Ast.AppearsStyle s -> {
                    if (!s.subject().equals("rows") && !s.subject().equals("table")) {
                        throw new CheckException(where + " appears: unknown style subject '" + s.subject()
                                + "' (expected 'rows' or 'table')");
                    }
                }
                case Ast.AppearsColumnOrder co -> {
                    for (String col : co.columns()) {
                        requireField(where + " appears", rowType, rowFields, col);
                    }
                }
            }
        }
    }

    private MethodSig lookup(Map<String, Map<String, MethodSig>> services, String service, String method, String where) {
        Map<String, MethodSig> methods = services.get(service);
        if (methods == null) {
            throw new CheckException(where + ": unknown service '" + service + "'");
        }
        MethodSig sig = methods.get(method);
        if (sig == null) {
            throw new CheckException(where + ": " + service + " has no method '" + method + "'");
        }
        return sig;
    }

    private static void checkArgCount(String where, String callee, int got, int expected) {
        if (got != expected) {
            throw new CheckException(where + ": " + callee + " takes " + expected + " argument(s) but got " + got);
        }
    }

    private static void requireField(String where, String entity, Map<String, Ty> fields, String field) {
        if (!fields.containsKey(field)) {
            throw new CheckException(where + ": '" + field + "' is not a field of " + entity);
        }
    }

    private void checkMethod(String service, Ast.Method m) {
        String where = service + "." + m.name();

        if (m.intent().isEmpty() && m.examples().isEmpty()) {
            throw new CheckException(where + " has no driver: give it an intent and/or at least one example");
        }

        Map<String, Ty> params = new LinkedHashMap<>();
        for (Ast.Param p : m.params()) {
            Ty ty = resolveType(p.type(), where + " parameter '" + p.name() + "'");
            if (params.put(p.name(), ty) != null) {
                throw new CheckException(where + " has duplicate parameter '" + p.name() + "'");
            }
        }
        Ty returnType = resolveType(m.returnType(), where + " return type");

        // requires: over parameters only, must be Bool.
        for (Ast.Expr req : m.requires()) {
            Ty t = infer(req, params, where + " requires");
            if (!t.isBool()) {
                throw new CheckException(where + " requires must be a boolean condition, got " + t);
            }
        }

        // ensures: parameters plus `result`, must be Bool.
        Map<String, Ty> ensuresEnv = new LinkedHashMap<>(params);
        ensuresEnv.put("result", returnType);
        for (Ast.Expr ens : m.ensures()) {
            Ty t = infer(ens, ensuresEnv, where + " ensures");
            if (!t.isBool()) {
                throw new CheckException(where + " ensures must be a boolean condition, got " + t);
            }
        }

        // examples: concrete inputs (no parameter names in scope) -> expected result.
        for (Ast.Example ex : m.examples()) {
            checkExample(where, m, params, returnType, ex);
        }
    }

    private void checkExample(String where, Ast.Method m, Map<String, Ty> params,
                              Ty returnType, Ast.Example ex) {
        if (!ex.call().callee().equals(m.name())) {
            throw new CheckException(where + " example calls '" + ex.call().callee()
                    + "' but the method is '" + m.name() + "'");
        }
        List<Ast.Expr> args = ex.call().args();
        if (args.size() != m.params().size()) {
            throw new CheckException(where + " example passes " + args.size()
                    + " argument(s) but the method takes " + m.params().size());
        }
        List<Ty> paramTypes = List.copyOf(params.values());
        for (int i = 0; i < args.size(); i++) {
            Ty argTy = infer(args.get(i), Map.of(), where + " example argument " + (i + 1));
            if (!argTy.equals(paramTypes.get(i))) {
                throw new CheckException(where + " example argument " + (i + 1) + " is " + argTy
                        + " but parameter '" + m.params().get(i).name() + "' is " + paramTypes.get(i));
            }
        }

        switch (ex.result()) {
            case Ast.ExprResult er -> {
                Ty rt = infer(er.value(), Map.of(), where + " example result");
                if (!rt.equals(returnType)) {
                    throw new CheckException(where + " example result is " + rt
                            + " but the method returns " + returnType);
                }
            }
            case Ast.EntityResult ent -> {
                if (!ent.typeName().equals(returnType.name()) || !entities.containsKey(ent.typeName())) {
                    throw new CheckException(where + " example expects a " + ent.typeName()
                            + " but the method returns " + returnType);
                }
                Map<String, Ty> fields = entities.get(ent.typeName());
                for (Ast.FieldExpect fe : ent.fields()) {
                    Ty fieldTy = fields.get(fe.field());
                    if (fieldTy == null) {
                        throw new CheckException(where + " example refers to unknown field '"
                                + ent.typeName() + "." + fe.field() + "'");
                    }
                    Ty valTy = infer(fe.expected(), Map.of(), where + " example field '" + fe.field() + "'");
                    if (!valTy.equals(fieldTy)) {
                        throw new CheckException(where + " example field '" + fe.field() + "' expects "
                                + fieldTy + " but got " + valTy);
                    }
                }
            }
        }
    }

    // ----- expression typing -------------------------------------------------

    private Ty infer(Ast.Expr expr, Map<String, Ty> env, String where) {
        return switch (expr) {
            case Ast.IntLit ignored -> Ty.INT;
            case Ast.StrLit ignored -> Ty.TEXT;
            case Ast.NameExpr n -> {
                Ty t = env.get(n.name());
                if (t == null) {
                    throw new CheckException(where + ": unknown name '" + n.name() + "'");
                }
                yield t;
            }
            case Ast.MemberExpr me -> {
                Ty target = infer(me.target(), env, where);
                LinkedHashMap<String, Ty> fields = entities.get(target.name());
                if (fields == null) {
                    throw new CheckException(where + ": cannot read field '" + me.field() + "' of non-entity " + target);
                }
                Ty ft = fields.get(me.field());
                if (ft == null) {
                    throw new CheckException(where + ": " + target + " has no field '" + me.field() + "'");
                }
                yield ft;
            }
            case Ast.CallExpr ce -> inferConstructor(ce, env, where);
            case Ast.BinExpr be -> inferBinary(be, env, where);
        };
    }

    /** A call inside an expression is an entity constructor: {@code Product(1, "Notebook", 5)}. */
    private Ty inferConstructor(Ast.CallExpr ce, Map<String, Ty> env, String where) {
        LinkedHashMap<String, Ty> fields = entities.get(ce.callee());
        if (fields == null) {
            throw new CheckException(where + ": '" + ce.callee()
                    + "' is not an entity; only entity constructors are allowed in expressions");
        }
        List<Ty> fieldTypes = List.copyOf(fields.values());
        if (ce.args().size() != fieldTypes.size()) {
            throw new CheckException(where + ": " + ce.callee() + " takes " + fieldTypes.size()
                    + " field(s) but got " + ce.args().size());
        }
        for (int i = 0; i < ce.args().size(); i++) {
            Ty at = infer(ce.args().get(i), env, where);
            if (!at.equals(fieldTypes.get(i))) {
                throw new CheckException(where + ": " + ce.callee() + " field " + (i + 1)
                        + " expects " + fieldTypes.get(i) + " but got " + at);
            }
        }
        return Ty.entity(ce.callee());
    }

    private Ty inferBinary(Ast.BinExpr be, Map<String, Ty> env, String where) {
        Ty l = infer(be.left(), env, where);
        Ty r = infer(be.right(), env, where);
        return switch (be.op()) {
            case "+", "-", "*", "/" -> {
                requireInt(l, be.op(), where);
                requireInt(r, be.op(), where);
                yield Ty.INT;
            }
            case "<", "<=", ">", ">=" -> {
                requireInt(l, be.op(), where);
                requireInt(r, be.op(), where);
                yield Ty.BOOL;
            }
            case "==", "!=" -> {
                if (!l.equals(r)) {
                    throw new CheckException(where + ": cannot compare " + l + " " + be.op() + " " + r);
                }
                yield Ty.BOOL;
            }
            case "and", "or" -> {
                requireBool(l, be.op(), where);
                requireBool(r, be.op(), where);
                yield Ty.BOOL;
            }
            default -> throw new CheckException(where + ": unknown operator '" + be.op() + "'");
        };
    }

    private static void requireInt(Ty t, String op, String where) {
        if (!t.isInt()) {
            throw new CheckException(where + ": operator '" + op + "' requires Int, got " + t);
        }
    }

    private static void requireBool(Ty t, String op, String where) {
        if (!t.isBool()) {
            throw new CheckException(where + ": operator '" + op + "' requires a boolean, got " + t);
        }
    }

    private Ty resolveType(Ast.TypeRef ref, String where) {
        Ty base = switch (ref.name()) {
            case "Int" -> Ty.INT;
            case "Text" -> Ty.TEXT;
            default -> {
                if (entities.containsKey(ref.name())) {
                    yield Ty.entity(ref.name());
                }
                throw new CheckException(where + ": unknown type '" + ref.name() + "'");
            }
        };
        return ref.list() ? Ty.list(base.name()) : base;
    }
}
