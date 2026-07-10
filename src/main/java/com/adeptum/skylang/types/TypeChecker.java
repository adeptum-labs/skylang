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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The hard-layer checker: it validates types, contracts, and examples deterministically,
 * with no model involvement. {@code sky check} runs exactly this. Fails fast on the first
 * problem with a human-readable message.
 *
 * <p>Refined types erase to their base for operators; a refined value flows freely to its
 * base (widening) while the reverse needs a literal the checker can prove fits. Declared
 * type names are nominal: two named types never mix even over the same base.
 */
public final class TypeChecker {

    /** entity name -> (field name -> type), in declaration order. */
    private final Map<String, LinkedHashMap<String, Ty>> entities = new HashMap<>();

    /** declared refined types ({@code type Slug = ...}), in declaration order. */
    private final Map<String, Ty.NamedTy> typeDecls = new LinkedHashMap<>();

    /** entity name -> its closed value set, for entities declared with {@code values}. */
    private final Map<String, List<String>> valueSets = new HashMap<>();

    /** entity name -> how many leading fields a constructor must supply (the rest have defaults). */
    private final Map<String, Integer> requiredFields = new HashMap<>();

    /** the entities that carry an @id field — store roots and relation targets. */
    private final java.util.Set<String> identified = new java.util.HashSet<>();

    public void check(Ast.Module module) {
        registerTypeDecls(module);
        registerEntities(module);
        if (module.services().stream().anyMatch(s -> s.uses().contains("db"))) {
            validatePersistability(module);
        }

        // Check every method and index its signature for view resolution.
        Map<String, Map<String, MethodSig>> services = new HashMap<>();
        for (Ast.Service s : module.services()) {
            checkEffects(s);
            Map<String, MethodSig> methods = services.computeIfAbsent(s.name(), k -> new HashMap<>());
            for (Ast.Method m : s.methods()) {
                checkMethod(s.name(), m);
                methods.put(m.name(), signatureOf(s.name(), m));
            }
        }

        // Check every view against the entities and the service signatures.
        for (Ast.View v : module.views()) {
            checkView(v, services);
        }
    }

    // ----- declared types ------------------------------------------------------

    private void registerTypeDecls(Ast.Module module) {
        for (Ast.TypeDecl d : module.types()) {
            String where = "type '" + d.name() + "'";
            if (Builtins.isReserved(d.name())) {
                throw new CheckException(where + " shadows the built-in type " + d.name());
            }
            if (typeDecls.containsKey(d.name())) {
                throw new CheckException("duplicate type '" + d.name() + "'");
            }
            Ty.Prim base = Builtins.prim(d.base())
                    .orElseThrow(() -> new CheckException(where + ": unknown base type '" + d.base() + "'"));
            switch (d.refinement()) {
                case Ast.Range r -> {
                    requireRangeBase(base, d.base(), where);
                    validateRange(r.lo(), r.hi(), where);
                }
                case Ast.Matching m -> {
                    if (!base.equals(Ty.TEXT)) {
                        throw new CheckException(where + ": matching needs a Text base, not " + d.base());
                    }
                    compileRegex(m.regex(), where);
                }
                case Ast.Where w -> {
                    Ty t = infer(w.predicate(), whereEnv(base, where), where + " where");
                    if (!t.isBool()) {
                        throw new CheckException(where + ": the where predicate must be boolean, got " + t);
                    }
                }
            }
            typeDecls.put(d.name(), new Ty.NamedTy(d.name(), base, d.refinement()));
        }
    }

    /** The names a {@code where} predicate may mention: the value itself, or Money's parts. */
    private static Map<String, Ty> whereEnv(Ty.Prim base, String where) {
        if (base.equals(Ty.MONEY)) {
            return Map.of("amount", Ty.INT);
        }
        if (base.equals(Ty.INT) || base.equals(Ty.TEXT)) {
            return Map.of("value", base);
        }
        throw new CheckException(where + ": a where refinement is not supported on " + base);
    }

    private static void requireRangeBase(Ty.Prim base, String baseName, String where) {
        if (!base.equals(Ty.INT) && !base.equals(Ty.TEXT)) {
            throw new CheckException(where + ": a range refinement needs an Int or Text base, not " + baseName);
        }
    }

    private static void validateRange(OptionalLong lo, OptionalLong hi, String where) {
        if (lo.isEmpty() && hi.isEmpty()) {
            throw new CheckException(where + ": a range needs at least one bound");
        }
        if (lo.isPresent() && hi.isPresent() && lo.getAsLong() > hi.getAsLong()) {
            throw new CheckException(where + ": range lower bound exceeds the upper bound");
        }
    }

    private static Pattern compileRegex(String regex, String where) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new CheckException(where + ": invalid regular expression: " + e.getMessage());
        }
    }

    // ----- entities ------------------------------------------------------------

    private void registerEntities(Ast.Module module) {
        for (Ast.Entity e : module.entities()) {
            if (Builtins.isReserved(e.name())) {
                throw new CheckException("entity '" + e.name() + "' shadows the built-in type " + e.name());
            }
            if (typeDecls.containsKey(e.name())) {
                throw new CheckException("entity '" + e.name() + "' collides with the type of the same name");
            }
            if (entities.containsKey(e.name())) {
                throw new CheckException("duplicate entity '" + e.name() + "'");
            }
            entities.put(e.name(), new LinkedHashMap<>());
        }
        for (Ast.Entity e : module.entities()) {
            LinkedHashMap<String, Ty> fields = entities.get(e.name());
            for (Ast.Field f : e.fields()) {
                String where = "field '" + e.name() + "." + f.name() + "'";
                Ty ty = resolveType(f.type(), where);
                if (f.min().isPresent() && !ty.isInt()) {
                    throw new CheckException("@min is only valid on Int fields, but "
                            + e.name() + "." + f.name() + " is " + ty);
                }
                if (f.unique() && ty instanceof Ty.GenericTy) {
                    throw new CheckException("@unique is not valid on " + ty + " fields ("
                            + e.name() + "." + f.name() + ")");
                }
                f.defaultValue().ifPresent(v -> checkDefault(where, v, ty));
                if (fields.put(f.name(), ty) != null) {
                    throw new CheckException("duplicate field '" + e.name() + "." + f.name() + "'");
                }
            }
            if (!e.values().isEmpty()) {
                registerValueSet(e, fields);
            }
            int required = e.fields().size();
            while (required > 0 && e.fields().get(required - 1).defaultValue().isPresent()) {
                required--;
            }
            requiredFields.put(e.name(), required);
            if (e.fields().stream().anyMatch(Ast.Field::id)) {
                identified.add(e.name());
            }
        }
    }

    // ----- persistability (a db-using module maps its entities to storage) ------

    /**
     * With the db effect in play every entity must map to the profile's storage: @id
     * entities become rows, entities without one become components embedded in their
     * owner, and each field shape must have a column form.
     */
    private void validatePersistability(Ast.Module module) {
        for (Ast.Entity e : module.entities()) {
            if (!e.values().isEmpty()) {
                continue;   // a closed value set persists as its carrier text
            }
            boolean component = !identified.contains(e.name());
            for (Ast.Field f : e.fields()) {
                checkPersistable("entity '" + e.name() + "." + f.name() + "'",
                        entities.get(e.name()).get(f.name()), component);
            }
        }
    }

    private void checkPersistable(String where, Ty ty, boolean insideComponent) {
        if (ty.erased() instanceof Ty.Prim) {
            return;
        }
        switch (ty) {
            case Ty.EntityTy et -> {
                if (valueSets.containsKey(et.name()) || identified.contains(et.name())) {
                    return;   // a text column or a relation — both fine anywhere
                }
                if (insideComponent) {
                    throw new CheckException(where + ": a component entity cannot embed another component ("
                            + et.name() + ")");
                }
            }
            case Ty.GenericTy g -> {
                switch (g.kind()) {
                    case "Secret" -> {
                        Ty arg = g.args().get(0).erased();
                        if (!arg.equals(Ty.TEXT) && !arg.equals(Ty.BYTES)) {
                            throw new CheckException(where + ": " + ty + " is not persistable yet");
                        }
                    }
                    case "Maybe" -> {
                        if (insideComponent || !maybePersistable(g.args().get(0))) {
                            throw new CheckException(where + ": " + ty + " is not persistable yet");
                        }
                    }
                    case "List", "Set" -> {
                        if (insideComponent) {
                            throw new CheckException(where
                                    + ": component entities hold basic fields and references only");
                        }
                        checkPersistableElement(where, g.args().get(0));
                    }
                    default -> throw new CheckException(where + ": Map fields are not persistable yet");
                }
            }
            default -> {
            }
        }
    }

    private boolean maybePersistable(Ty arg) {
        Ty e = arg.erased();
        if (e.equals(Ty.INT) || e.equals(Ty.TEXT) || e.equals(Ty.BOOL) || e.equals(Ty.INSTANT)) {
            return true;
        }
        return arg instanceof Ty.EntityTy et
                && (identified.contains(et.name()) || valueSets.containsKey(et.name()));
    }

    private void checkPersistableElement(String where, Ty element) {
        Ty e = element.erased();
        if (e.equals(Ty.INT) || e.equals(Ty.TEXT) || e.equals(Ty.BOOL)) {
            return;
        }
        if (element instanceof Ty.EntityTy et) {
            if (valueSets.containsKey(et.name())) {
                return;
            }
            if (identified.contains(et.name())) {
                throw new CheckException(where + ": lists of identified entities are not persistable yet");
            }
            return;   // a component element; its own fields are checked as a component
        }
        throw new CheckException(where + ": " + element + " elements are not persistable yet");
    }

    /** {@code values Member, Admin} seeds and closes the instance set of an enum-like entity. */
    private void registerValueSet(Ast.Entity e, Map<String, Ty> fields) {
        if (e.fields().size() != 1 || !e.fields().get(0).id()) {
            throw new CheckException("entity '" + e.name()
                    + "': a values entity carries exactly one @id field naming each value");
        }
        Ty carrier = fields.get(e.fields().get(0).name());
        if (!carrier.equals(Ty.TEXT)) {
            throw new CheckException("entity '" + e.name() + "': the values carrier field must be Text, not "
                    + carrier);
        }
        if (e.values().size() != e.values().stream().distinct().count()) {
            throw new CheckException("entity '" + e.name() + "' declares a duplicate value");
        }
        valueSets.put(e.name(), e.values());
    }

    private static void checkEffects(Ast.Service s) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String effect : s.uses()) {
            if (!Builtins.EFFECTS.contains(effect)) {
                throw new CheckException("service '" + s.name() + "': unknown effect '" + effect
                        + "' (the jvm-jakarta profile binds " + String.join(", ", Builtins.EFFECTS) + ")");
            }
            if (!seen.add(effect)) {
                throw new CheckException("service '" + s.name() + "': duplicate effect '" + effect + "'");
            }
        }
    }

    private void checkDefault(String where, Ast.Expr value, Ty fieldTy) {
        if (value instanceof Ast.NameExpr n && n.name().equals("now")) {
            if (!fieldTy.equals(Ty.INSTANT)) {
                throw new CheckException(where + ": '= now' needs an Instant field, not " + fieldTy);
            }
            return;
        }
        if (value instanceof Ast.MemberExpr) {
            // A declared-value constant like Role.Member; infer resolves and validates it.
            Ty vt = infer(value, Map.of(), where + " default");
            fits(where + " default", null, vt, fieldTy);
            return;
        }
        if (!isLiteral(value)) {
            throw new CheckException(where + ": only literal or declared-value defaults are supported");
        }
        Ty vt = infer(value, Map.of(), where + " default");
        fits(where + " default", value, vt, fieldTy);
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
            fits(where + " shows argument " + (i + 1), q.args().get(i), actual, querySig.params().get(i));
        }
        if (!(querySig.returnType() instanceof Ty.GenericTy list && list.kind().equals("List")
                && list.args().get(0) instanceof Ty.EntityTy row)) {
            throw new CheckException(where + " shows: " + q.service() + "." + q.method()
                    + " must return a list of an entity (e.g. [Product]) to drive a view, but returns "
                    + querySig.returnType());
        }
        String rowType = row.name();
        LinkedHashMap<String, Ty> rowFields = entities.get(rowType);

        // Projected columns must be renderable fields of the row entity.
        if (v.shows().projection().isPresent()) {
            for (String col : v.shows().projection().get().columns()) {
                requireRenderableField(where + " shows", rowType, rowFields, col);
            }
        }

        // Each action must call a declared method with row-typed / prompted arguments of matching type.
        for (Ast.Action a : v.actions()) {
            String actionWhere = where + " action \"" + a.label() + "\"";
            MethodSig sig = lookup(services, a.service(), a.method(), actionWhere);
            checkArgCount(actionWhere, a.service() + "." + a.method(), a.args().size(), sig.params().size());
            Map<String, Ty> env = Map.of(a.rowVar(), Ty.entity(rowType));
            for (int i = 0; i < a.args().size(); i++) {
                String argWhere = actionWhere + " argument " + (i + 1);
                Ty expected = sig.params().get(i);
                switch (a.args().get(i)) {
                    case Ast.ExprArg ea ->
                            fits(argWhere, ea.value(), infer(ea.value(), env, argWhere), expected);
                    case Ast.AskArg ak -> fits(argWhere, null, resolveAskType(ak.type(), argWhere), expected);
                }
            }
        }

        // Expectations must reference real columns / declared actions.
        for (Ast.Expect e : v.expects()) {
            switch (e) {
                case Ast.ExpectColumns ec -> {
                    for (String col : ec.columns()) {
                        requireRenderableField(where + " expect", rowType, rowFields, col);
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
                        requireRenderableField(where + " appears", rowType, rowFields, col);
                    }
                }
            }
        }
    }

    /** The types a view may prompt the user for: text- or number-shaped values with converters. */
    private Ty resolveAskType(Ast.Type type, String where) {
        Ty t = resolveType(type, where);
        Ty e = t.erased();
        if (!e.equals(Ty.INT) && !e.equals(Ty.TEXT) && !e.equals(Ty.MONEY) && !e.equals(Ty.INSTANT)) {
            throw new CheckException(where + ": cannot prompt for " + t);
        }
        return t;
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

    private static void requireRenderableField(String where, String entity, Map<String, Ty> fields, String field) {
        Ty ty = fields.get(field);
        if (ty == null) {
            throw new CheckException(where + ": '" + field + "' is not a field of " + entity);
        }
        if (ty.isSecret()) {
            throw new CheckException(where + ": Secret field '" + field + "' cannot be rendered");
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
            String argWhere = where + " example argument " + (i + 1);
            Ty argTy = infer(args.get(i), Map.of(), argWhere);
            fits(argWhere, args.get(i), argTy, paramTypes.get(i));
        }

        switch (ex.result()) {
            case Ast.ExprResult er -> {
                String resultWhere = where + " example result";
                Ty rt = infer(er.value(), Map.of(), resultWhere);
                fits(resultWhere, er.value(), rt, returnType);
            }
            case Ast.EntityResult ent -> {
                if (!(returnType instanceof Ty.EntityTy et) || !ent.typeName().equals(et.name())
                        || !entities.containsKey(ent.typeName())) {
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
                    String feWhere = where + " example field '" + fe.field() + "'";
                    Ty valTy = infer(fe.expected(), Map.of(), feWhere);
                    fits(feWhere, fe.expected(), valTy, fieldTy);
                }
            }
        }
    }

    // ----- expression typing -------------------------------------------------

    private Ty infer(Ast.Expr expr, Map<String, Ty> env, String where) {
        return switch (expr) {
            case Ast.IntLit ignored -> Ty.INT;
            case Ast.StrLit ignored -> Ty.TEXT;
            case Ast.BoolLit ignored -> Ty.BOOL;
            case Ast.MoneyLit ignored -> Ty.MONEY;
            case Ast.NameExpr n -> {
                Ty t = env.get(n.name());
                if (t == null) {
                    if (n.name().equals("now")) {
                        throw new CheckException(where + ": 'now' cannot appear here — contracts and examples"
                                + " must stay deterministic; read the clock inside the body instead");
                    }
                    throw new CheckException(where + ": unknown name '" + n.name() + "'");
                }
                yield t;
            }
            case Ast.MemberExpr me -> {
                // Role.Member — a constant of a closed value set (unless a variable shadows the name).
                if (me.target() instanceof Ast.NameExpr n && !env.containsKey(n.name())
                        && valueSets.containsKey(n.name())) {
                    if (!valueSets.get(n.name()).contains(me.field())) {
                        throw new CheckException(where + ": " + n.name() + " has no value '" + me.field() + "'");
                    }
                    yield Ty.entity(n.name());
                }
                Ty target = infer(me.target(), env, where);
                if (!(target instanceof Ty.EntityTy et)) {
                    throw new CheckException(where + ": cannot read field '" + me.field() + "' of non-entity " + target);
                }
                Ty ft = entities.get(et.name()).get(me.field());
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
        if (valueSets.containsKey(ce.callee())) {
            throw new CheckException(where + ": the value set of " + ce.callee() + " is closed; use its"
                    + " declared constants (" + ce.callee() + "." + valueSets.get(ce.callee()).get(0) + ", ...)");
        }
        LinkedHashMap<String, Ty> fields = entities.get(ce.callee());
        if (fields == null) {
            throw new CheckException(where + ": '" + ce.callee()
                    + "' is not an entity; only entity constructors are allowed in expressions");
        }
        List<Ty> fieldTypes = List.copyOf(fields.values());
        int required = requiredFields.getOrDefault(ce.callee(), fieldTypes.size());
        if (ce.args().size() < required || ce.args().size() > fieldTypes.size()) {
            String arity = required == fieldTypes.size()
                    ? String.valueOf(fieldTypes.size()) : required + ".." + fieldTypes.size();
            throw new CheckException(where + ": " + ce.callee() + " takes " + arity
                    + " field(s) but got " + ce.args().size());
        }
        for (int i = 0; i < ce.args().size(); i++) {
            String argWhere = where + ": " + ce.callee() + " field " + (i + 1);
            Ty at = infer(ce.args().get(i), env, argWhere);
            fits(argWhere, ce.args().get(i), at, fieldTypes.get(i));
        }
        return Ty.entity(ce.callee());
    }

    private Ty inferBinary(Ast.BinExpr be, Map<String, Ty> env, String where) {
        Ty l = infer(be.left(), env, where);
        Ty r = infer(be.right(), env, where);
        // Declared type names are nominal: Percentage and Quantity never mix, even over Int.
        if (l instanceof Ty.NamedTy ln && r instanceof Ty.NamedTy rn && !ln.name().equals(rn.name())) {
            throw new CheckException(where + ": cannot mix " + l + " and " + r);
        }
        if (be.left() instanceof Ast.MoneyLit ml && be.right() instanceof Ast.MoneyLit mr
                && !ml.currency().equals(mr.currency())) {
            throw new CheckException(where + ": money literals have different currencies ("
                    + ml.currency() + " vs " + mr.currency() + ")");
        }
        Ty le = l.erased();
        Ty re = r.erased();
        return switch (be.op()) {
            case "+", "-" -> {
                if (le.equals(Ty.INT) && re.equals(Ty.INT)) {
                    yield Ty.INT;
                }
                if (le.equals(Ty.MONEY) && re.equals(Ty.MONEY)) {
                    yield Ty.MONEY;
                }
                throw new CheckException(where + ": operator '" + be.op() + "' cannot combine " + l + " and " + r);
            }
            case "*" -> {
                if (le.equals(Ty.INT) && re.equals(Ty.INT)) {
                    yield Ty.INT;
                }
                if (le.equals(Ty.MONEY) && re.equals(Ty.MONEY)) {
                    throw new CheckException(where + ": Money cannot be multiplied by Money");
                }
                if ((le.equals(Ty.MONEY) && re.equals(Ty.INT)) || (le.equals(Ty.INT) && re.equals(Ty.MONEY))) {
                    yield Ty.MONEY;
                }
                throw new CheckException(where + ": operator '*' cannot combine " + l + " and " + r);
            }
            case "/" -> {
                if (le.equals(Ty.MONEY) || re.equals(Ty.MONEY)) {
                    throw new CheckException(where
                            + ": Money division must handle the remainder explicitly and is not supported");
                }
                if (le.equals(Ty.INT) && re.equals(Ty.INT)) {
                    yield Ty.INT;
                }
                throw new CheckException(where + ": operator '/' cannot combine " + l + " and " + r);
            }
            case "<", "<=", ">", ">=" -> {
                boolean ordered = le.equals(re)
                        && (le.equals(Ty.INT) || le.equals(Ty.MONEY) || le.equals(Ty.INSTANT));
                if (!ordered) {
                    throw new CheckException(where + ": operator '" + be.op()
                            + "' cannot compare " + l + " and " + r);
                }
                yield Ty.BOOL;
            }
            case "==", "!=" -> {
                if (!le.equals(re)) {
                    throw new CheckException(where + ": cannot compare " + l + " " + be.op() + " " + r);
                }
                yield Ty.BOOL;
            }
            case "and", "or" -> {
                if (!l.isBool() || !r.isBool()) {
                    throw new CheckException(where + ": operator '" + be.op() + "' requires a boolean, got "
                            + (l.isBool() ? r : l));
                }
                yield Ty.BOOL;
            }
            default -> throw new CheckException(where + ": unknown operator '" + be.op() + "'");
        };
    }

    // ----- type resolution and fitting ----------------------------------------

    private Ty resolveType(Ast.Type type, String where) {
        return switch (type) {
            case Ast.TypeRef ref -> {
                Ty t = resolveName(ref.name(), where);
                yield ref.list() ? Ty.list(t) : t;
            }
            case Ast.RangedType r -> {
                Ty.Prim base = Builtins.prim(r.base())
                        .orElseThrow(() -> new CheckException(where + ": unknown type '" + r.base() + "'"));
                requireRangeBase(base, r.base(), where);
                validateRange(r.lo(), r.hi(), where);
                yield new Ty.AnonRefined(base, r.lo(), r.hi());
            }
            case Ast.GenericType g -> {
                int arity = Builtins.genericArity(g.name())
                        .orElseThrow(() -> new CheckException(where + ": unknown type '" + g.name() + "'"));
                if (g.args().size() != arity) {
                    throw new CheckException(where + ": " + g.name() + " takes " + arity
                            + " type argument(s) but got " + g.args().size());
                }
                List<Ty> args = new ArrayList<>();
                for (Ast.Type a : g.args()) {
                    args.add(resolveType(a, where));
                }
                if (g.name().equals("Secret") && containsSecret(args.get(0))) {
                    throw new CheckException(where + ": Secret cannot wrap another Secret");
                }
                yield new Ty.GenericTy(g.name(), args);
            }
        };
    }

    private Ty resolveName(String name, String where) {
        Optional<Ty> builtin = Builtins.resolve(name);
        if (builtin.isPresent()) {
            return builtin.get();
        }
        Ty.NamedTy declared = typeDecls.get(name);
        if (declared != null) {
            return declared;
        }
        if (entities.containsKey(name)) {
            return Ty.entity(name);
        }
        throw new CheckException(where + ": unknown type '" + name + "'");
    }

    private static boolean containsSecret(Ty ty) {
        return ty.isSecret()
                || (ty instanceof Ty.GenericTy g && g.args().stream().anyMatch(TypeChecker::containsSecret));
    }

    /**
     * A value of type {@code actual} may be used where {@code expected} is wanted: exactly,
     * or by widening a refined type to its base. The reverse never holds without a literal.
     */
    private static boolean assignable(Ty actual, Ty expected) {
        if (actual.equals(expected)) {
            return true;
        }
        return switch (actual) {
            case Ty.NamedTy n -> assignable(n.base(), expected);
            case Ty.AnonRefined a -> assignable(a.base(), expected);
            default -> false;
        };
    }

    /**
     * Require {@code actual} to fit {@code expected}; a literal {@code value} may narrow
     * into a refined expected type when the checker can prove the predicate holds.
     */
    private void fits(String where, Ast.Expr value, Ty actual, Ty expected) {
        if (assignable(actual, expected)) {
            return;
        }
        if (value != null && isLiteral(value) && assignable(actual, expected.erased())) {
            checkLiteralFit(where, value, expected);
            return;
        }
        throw new CheckException(where + " is " + actual + " but expected " + expected);
    }

    private static boolean isLiteral(Ast.Expr e) {
        return e instanceof Ast.IntLit || e instanceof Ast.StrLit
                || e instanceof Ast.BoolLit || e instanceof Ast.MoneyLit;
    }

    /** A human-readable form of a refined type including its predicate, for error messages. */
    private static String describe(Ty ty) {
        if (ty instanceof Ty.NamedTy n) {
            String rule = switch (n.refinement()) {
                case Ast.Range r -> n.base().name() + "(" + (r.lo().isPresent() ? r.lo().getAsLong() : "")
                        + ".." + (r.hi().isPresent() ? r.hi().getAsLong() : "") + ")";
                case Ast.Matching m -> n.base().name() + " matching /" + m.regex() + "/";
                case Ast.Where w -> n.base().name() + " where ...";
            };
            return n.name() + " (" + rule + ")";
        }
        return ty.toString();
    }

    /** Prove a literal satisfies a refined type's predicate, or fail the check. */
    private void checkLiteralFit(String where, Ast.Expr value, Ty expected) {
        Ast.Refinement refinement = switch (expected) {
            case Ty.NamedTy n -> n.refinement();
            case Ty.AnonRefined a -> new Ast.Range(a.lo(), a.hi());
            default -> null;
        };
        if (refinement == null) {
            throw new CheckException(where + " does not fit " + describe(expected));
        }
        boolean ok = switch (refinement) {
            case Ast.Range r -> switch (value) {
                case Ast.IntLit i -> inRange(i.value(), r);
                case Ast.StrLit s -> inRange(s.value().length(), r);
                default -> false;
            };
            case Ast.Matching m ->
                    value instanceof Ast.StrLit s && compileRegex(m.regex(), where).matcher(s.value()).matches();
            // Statically decidable only for Money-sign style predicates; anything else is
            // accepted here and enforced by the generated construction checks at runtime.
            case Ast.Where w -> !(value instanceof Ast.MoneyLit ml)
                    || evalMoneyWhere(w.predicate(), ml.amount()).orElse(true);
        };
        if (!ok) {
            throw new CheckException(where + ": " + literalText(value) + " does not fit " + describe(expected));
        }
    }

    private static boolean inRange(long v, Ast.Range r) {
        return (r.lo().isEmpty() || v >= r.lo().getAsLong()) && (r.hi().isEmpty() || v <= r.hi().getAsLong());
    }

    /** Evaluate an {@code amount <op> n} predicate against a money literal, if it has that shape. */
    private static Optional<Boolean> evalMoneyWhere(Ast.Expr pred, BigDecimal amount) {
        if (!(pred instanceof Ast.BinExpr be)) {
            return Optional.empty();
        }
        if (be.op().equals("and") || be.op().equals("or")) {
            Optional<Boolean> l = evalMoneyWhere(be.left(), amount);
            Optional<Boolean> r = evalMoneyWhere(be.right(), amount);
            if (l.isEmpty() || r.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(be.op().equals("and") ? l.get() && r.get() : l.get() || r.get());
        }
        if (!(be.left() instanceof Ast.NameExpr n && n.name().equals("amount"))
                || !(be.right() instanceof Ast.IntLit i)) {
            return Optional.empty();
        }
        int cmp = amount.compareTo(BigDecimal.valueOf(i.value()));
        return switch (be.op()) {
            case ">" -> Optional.of(cmp > 0);
            case ">=" -> Optional.of(cmp >= 0);
            case "<" -> Optional.of(cmp < 0);
            case "<=" -> Optional.of(cmp <= 0);
            case "==" -> Optional.of(cmp == 0);
            case "!=" -> Optional.of(cmp != 0);
            default -> Optional.empty();
        };
    }

    private static String literalText(Ast.Expr value) {
        return switch (value) {
            case Ast.IntLit i -> Long.toString(i.value());
            case Ast.StrLit s -> "\"" + s.value() + "\"";
            case Ast.BoolLit b -> Boolean.toString(b.value());
            case Ast.MoneyLit m -> m.amount().toPlainString() + m.currency().toLowerCase(java.util.Locale.ROOT);
            default -> value.toString();
        };
    }
}
