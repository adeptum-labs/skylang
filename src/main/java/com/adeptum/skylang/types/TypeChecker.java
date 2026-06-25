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

        // Pass 2: check every method.
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                checkMethod(s.name(), m);
            }
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
        return switch (ref.name()) {
            case "Int" -> Ty.INT;
            case "Text" -> Ty.TEXT;
            default -> {
                if (entities.containsKey(ref.name())) {
                    yield Ty.entity(ref.name());
                }
                throw new CheckException(where + ": unknown type '" + ref.name() + "'");
            }
        };
    }
}
