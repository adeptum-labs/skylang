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

    /** entities named in a raises clause — failure types, never usable as data. */
    private final java.util.Set<String> errorEntities = new java.util.HashSet<>();

    /** unqualified module methods callable from contracts, with their service's budget. */
    private record Helper(MethodSig sig, List<String> uses, boolean ambiguous) {
    }

    private final Map<String, Helper> helpers = new HashMap<>();

    /** the service whose method is being checked; aggregates and phrases consult its budget. */
    private Ast.Service currentService;

    public void check(Ast.Module module) {
        registerTypeDecls(module);
        registerEntities(module);
        if (module.services().stream().anyMatch(s -> s.uses().contains("db"))) {
            validatePersistability(module);
        }
        registerErrorsAndHelpers(module);
        checkPolicies(module);

        // Check every method and index its signature for view resolution.
        Map<String, Map<String, MethodSig>> services = new HashMap<>();
        for (Ast.Service s : module.services()) {
            Map<String, MethodSig> methods = services.computeIfAbsent(s.name(), k -> new HashMap<>());
            for (Ast.Method m : s.methods()) {
                checkMethod(s, m);
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

    /** the module's entities with their raw declarations, for phrase and @id lookups. */
    private List<Ast.Entity> moduleEntities = List.of();

    private void registerEntities(Ast.Module module) {
        moduleEntities = module.entities();
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
                if (f.name().equals("length") || f.name().equals("size")) {
                    throw new CheckException(where + ": 'length' and 'size' are reserved for"
                            + " collection and byte-sequence measurements");
                }
                Ty ty = resolveType(f.type(), where);
                if (f.min().isPresent() && !ty.isInt()) {
                    throw new CheckException("@min is only valid on Int fields, but "
                            + e.name() + "." + f.name() + " is " + ty);
                }
                if (f.unique() && ty instanceof Ty.GenericTy) {
                    throw new CheckException("@unique is not valid on " + ty + " fields ("
                            + e.name() + "." + f.name() + ")");
                }
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
        // Defaults are checked only after every value set is registered, so a field may
        // default to a constant of an entity declared later in the file (role Role = Role.Member).
        for (Ast.Entity e : module.entities()) {
            for (Ast.Field f : e.fields()) {
                String where = "field '" + e.name() + "." + f.name() + "'";
                f.defaultValue().ifPresent(v -> checkDefault(where, v, entities.get(e.name()).get(f.name())));
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
        // Only what can actually reach the store must map to it: rows (identified entities)
        // and the components embedded in them. A value entity that only travels as a method
        // argument — a Cart — never persists and owes the storage nothing.
        java.util.Set<String> embedded = new java.util.HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(identified);
        while (!queue.isEmpty()) {
            for (Ty ty : entities.getOrDefault(queue.poll(), new LinkedHashMap<>()).values()) {
                Ty element = ty instanceof Ty.GenericTy g && !g.args().isEmpty()
                        ? g.args().get(0) : ty;
                if (element instanceof Ty.EntityTy et && !identified.contains(et.name())
                        && !valueSets.containsKey(et.name()) && embedded.add(et.name())) {
                    queue.add(et.name());
                }
            }
        }
        for (Ast.Entity e : module.entities()) {
            if (!e.values().isEmpty()) {
                continue;   // a closed value set persists as its carrier text
            }
            boolean component = !identified.contains(e.name());
            if (component && !embedded.contains(e.name())) {
                continue;   // a pure value argument; nothing stores it
            }
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

    /**
     * The pre-pass over services: validate budgets, mark the entities that raises clauses
     * turn into error types, and index every method for contract helper calls.
     */
    private void registerErrorsAndHelpers(Ast.Module module) {
        for (Ast.Service s : module.services()) {
            checkEffects(s);
            for (Ast.Method m : s.methods()) {
                java.util.List<String> named = new java.util.ArrayList<>();
                m.raises().forEach(r -> named.add(r.error()));
                m.examples().forEach(ex -> {
                    if (ex.result() instanceof Ast.RaisesResult rr) {
                        named.add(rr.error());
                    }
                });
                m.specs().forEach(spec -> spec.then().forEach(t -> {
                    if (t instanceof Ast.ThenRaises tr) {
                        named.add(tr.error());
                    }
                }));
                for (String error : named) {
                    if (!entities.containsKey(error)) {
                        throw new CheckException(s.name() + "." + m.name() + " raises unknown error '"
                                + error + "' — declare it as an entity");
                    }
                    errorEntities.add(error);
                }
            }
        }
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                boolean ambiguous = helpers.containsKey(m.name());
                helpers.put(m.name(), new Helper(signatureOf(s.name(), m), s.uses(), ambiguous));
            }
        }
        for (Ast.Policy p : module.policies()) {
            if (p.rule() instanceof Ast.RequireRule rr && rr.raise().isPresent()) {
                String error = rr.raise().get();
                if (!entities.containsKey(error)) {
                    throw new CheckException("policy " + p.name() + " raises unknown error '"
                            + error + "' — declare it as an entity");
                }
                errorEntities.add(error);
            }
        }
        for (Ast.Entity e : module.entities()) {
            for (Ast.Field f : e.fields()) {
                if (f.type() instanceof Ast.TypeRef ref && errorEntities.contains(ref.name())) {
                    throw new CheckException("field '" + e.name() + "." + f.name() + "': '" + ref.name()
                            + "' is an error (named in raises) and cannot be used as data");
                }
            }
        }
    }

    // ----- policies --------------------------------------------------------------

    /** Validate every policy: the whenever resolves, the shape fits, the predicate types. */
    private void checkPolicies(Ast.Module module) {
        for (Ast.Policy p : module.policies()) {
            String where = "policy " + p.name();
            switch (p.whenever()) {
                case Ast.Constructed c -> {
                    if (!(p.rule() instanceof Ast.RequireRule rr)) {
                        throw new CheckException(where + ": a construction rule states what to require;"
                                + " forbid pairs with 'is passed to a logger'");
                    }
                    Ty value = typeDecls.containsKey(c.typeWord())
                            ? typeDecls.get(c.typeWord())
                            : entities.containsKey(c.typeWord()) ? Ty.entity(c.typeWord()) : null;
                    if (value == null) {
                        throw new CheckException(where + ": cannot resolve 'a " + c.typeWord()
                                + " is constructed' — no declared type or entity matches '"
                                + c.typeWord() + "'");
                    }
                    checkRequireTerms(where, rr, value);
                }
                case Ast.PassedToLogger l -> {
                    if (!l.typeWord().equals("Secret")) {
                        throw new CheckException(where + ": only 'a Secret is passed to a logger'"
                                + " is resolvable here");
                    }
                    if (!(p.rule() instanceof Ast.ForbidRule)) {
                        throw new CheckException(where + ": the logger rule is a forbid");
                    }
                }
                case Ast.Posted po -> {
                    if (!entities.containsKey(po.typeWord())) {
                        throw new CheckException(where + ": cannot resolve 'a " + po.typeWord()
                                + " is posted' — no entity matches '" + po.typeWord() + "'");
                    }
                    if (!(p.rule() instanceof Ast.RequireRule rr) || rr.raise().isEmpty()) {
                        throw new CheckException(where + ": a posted rule states what to require"
                                + " and which error to raise");
                    }
                }
            }
        }
    }

    private void checkRequireTerms(String where, Ast.RequireRule rule, Ty value) {
        Map<String, Ty> env = value.erased().equals(Ty.TEXT)
                ? Map.of("value", value, "length", Ty.INT)
                : Map.of("value", value);
        for (Ast.ReqTerm term : rule.terms()) {
            switch (term) {
                case Ast.TermExpr te -> {
                    Ty t = infer(te.expr(), env, where + " require");
                    if (!t.isBool()) {
                        throw new CheckException(where + ": require must be boolean, got " + t);
                    }
                }
                case Ast.Contains c -> {
                    if (!value.erased().equals(Ty.TEXT)) {
                        throw new CheckException(where + ": 'contains a " + c.what()
                                + "' needs a Text-based value");
                    }
                    if (!c.what().equals("symbol")) {
                        throw new CheckException(where + ": only 'contains a symbol' is resolvable here");
                    }
                }
                case Ast.ProseTerm ignored -> throw new CheckException(where
                        + ": a construction rule needs a checkable predicate, not prose");
            }
        }
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

    private void checkMethod(Ast.Service svc, Ast.Method m) {
        String service = svc.name();
        String where = service + "." + m.name();
        currentService = svc;

        if (m.intent().isEmpty() && m.examples().isEmpty() && m.specs().isEmpty()
                && m.nativeBody().isEmpty()) {
            throw new CheckException(where
                    + " has no driver: give it an intent, an example, a spec, or a native block");
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

        // raises: each names a declared error and a condition the checker can resolve.
        for (Ast.Raise r : m.raises()) {
            checkRaise(where + " raises " + r.error(), r, params, svc);
        }

        // old(result...) reads pre-call store state: it needs the db and a way to find the row.
        if (m.ensures().stream().anyMatch(TypeChecker::mentionsOldResult)) {
            requireOldResultShape(where, svc, m, returnType);
        }

        // examples: concrete inputs (no parameter names in scope) -> expected result.
        for (Ast.Example ex : m.examples()) {
            checkExample(where, svc, m, params, returnType, ex);
        }

        // specs: given pins state, when performs the call, then asserts the outcome.
        for (Ast.Spec spec : m.specs()) {
            checkSpec(where + " spec \"" + spec.title() + "\"", svc, m, params, returnType, spec);
        }
        currentService = null;
    }

    private void checkSpec(String where, Ast.Service svc, Ast.Method m, Map<String, Ty> params,
                           Ty returnType, Ast.Spec spec) {
        spec.given().ifPresent(g -> checkGiven(where + " given", g, params));

        if (!spec.when().callee().equals(m.name())) {
            throw new CheckException(where + ": when must call " + m.name() + ", not '"
                    + spec.when().callee() + "'");
        }
        checkArgCount(where + " when", m.name(), spec.when().args().size(), m.params().size());
        List<Ty> paramTypes = List.copyOf(params.values());
        for (int i = 0; i < spec.when().args().size(); i++) {
            Ast.Expr arg = spec.when().args().get(i);
            Ty at = infer(arg, params, where + " when argument " + (i + 1));
            fits(where + " when argument " + (i + 1), arg, at, paramTypes.get(i));
        }

        // Every entity witness the when-call names must be constructible: each field either
        // pinned in given or of a type with a derivable sample value.
        for (Ast.Param p : m.params()) {
            boolean referenced = spec.when().args().stream()
                    .anyMatch(a -> a instanceof Ast.NameExpr ne && ne.name().equals(p.name()));
            if (!referenced || !(params.get(p.name()) instanceof Ty.EntityTy et)
                    || valueSets.containsKey(et.name())) {
                continue;
            }
            java.util.Set<String> pinned = pinnedFields(spec.given().orElse(null), p.name());
            for (Ast.Entity e : moduleEntities) {
                if (!e.name().equals(et.name())) {
                    continue;
                }
                for (Ast.Field f : e.fields()) {
                    if (!pinned.contains(f.name()) && !f.id() && !derivableWitness(f.type())) {
                        throw new CheckException(where + ": pin " + p.name() + "." + f.name()
                                + " in given — its type has no derivable witness value");
                    }
                }
            }
        }

        boolean raisesInSpec = spec.then().stream().anyMatch(t -> t instanceof Ast.ThenRaises);
        Map<String, Ty> env = new LinkedHashMap<>(params);
        if (!raisesInSpec) {
            env.put("result", returnType);
        }
        for (Ast.ThenAssert t : spec.then()) {
            if (!(t instanceof Ast.ThenExpr te)) {
                continue;
            }
            String twhere = where + " then";
            Ty ty = infer(te.expr(), env, twhere);
            if (!ty.isBool()) {
                throw new CheckException(twhere + " must be a boolean assertion, got " + ty);
            }
            if (mentionsEntityParamField(te.expr(), params)) {
                requireDb(twhere, svc, "asserting stored state after the call");
            }
        }
    }

    /** {@code given} builds the starting state: an and-chain of equality pins on parameters. */
    private void checkGiven(String where, Ast.Expr g, Map<String, Ty> params) {
        if (g instanceof Ast.BinExpr and && and.op().equals("and")) {
            checkGiven(where, and.left(), params);
            checkGiven(where, and.right(), params);
            return;
        }
        if (!(g instanceof Ast.BinExpr pin) || !pin.op().equals("==")) {
            throw new CheckException(where + " pins parameter state with '==' constraints joined by 'and'");
        }
        Ty target = switch (pin.left()) {
            case Ast.NameExpr n when params.containsKey(n.name()) -> params.get(n.name());
            case Ast.MemberExpr me when me.target() instanceof Ast.NameExpr n
                    && params.containsKey(n.name()) -> infer(me, params, where);
            default -> throw new CheckException(where
                    + " pins a parameter or a parameter's field on the left of each '=='");
        };
        Ty valueTy = infer(pin.right(), Map.of(), where);
        fits(where, pin.right(), valueTy, target);
    }

    /** The fields of one parameter that the given chain pins. */
    private static java.util.Set<String> pinnedFields(Ast.Expr given, String param) {
        java.util.Set<String> pinned = new java.util.HashSet<>();
        collectPinned(given, param, pinned);
        return pinned;
    }

    private static void collectPinned(Ast.Expr g, String param, java.util.Set<String> pinned) {
        if (g == null) {
            return;
        }
        if (g instanceof Ast.BinExpr and && and.op().equals("and")) {
            collectPinned(and.left(), param, pinned);
            collectPinned(and.right(), param, pinned);
            return;
        }
        if (g instanceof Ast.BinExpr pin && pin.left() instanceof Ast.MemberExpr me
                && me.target() instanceof Ast.NameExpr n && n.name().equals(param)) {
            pinned.add(me.field());
        }
    }

    /** True when the harness can invent a valid value of this type on its own. */
    private boolean derivableWitness(Ast.Type type) {
        if (type instanceof Ast.GenericType g) {
            return !g.name().equals("Secret");
        }
        if (type instanceof Ast.TypeRef ref && !ref.list()) {
            Ty.NamedTy declared = typeDecls.get(ref.name());
            return declared == null || !(declared.refinement() instanceof Ast.Matching);
        }
        return true;
    }

    /** True when the assertion reads a field of an entity-typed parameter (stored state). */
    private boolean mentionsEntityParamField(Ast.Expr e, Map<String, Ty> params) {
        return switch (e) {
            case Ast.MemberExpr m -> m.target() instanceof Ast.NameExpr n
                    && params.get(n.name()) instanceof Ty.EntityTy
                    || mentionsEntityParamField(m.target(), params);
            case Ast.BinExpr b ->
                    mentionsEntityParamField(b.left(), params) || mentionsEntityParamField(b.right(), params);
            case Ast.NotExpr n -> mentionsEntityParamField(n.value(), params);
            case Ast.CallExpr c -> c.args().stream().anyMatch(a -> mentionsEntityParamField(a, params));
            default -> false;
        };
    }

    private void checkRaise(String where, Ast.Raise r, Map<String, Ty> params, Ast.Service svc) {
        switch (r.condition()) {
            case Ast.CondExpr c -> {
                Ty t = infer(c.expr(), params, where);
                if (!t.isBool()) {
                    throw new CheckException(where + ": the when-condition must be boolean, got " + t);
                }
            }
            case Ast.NoSuch ns -> {
                String entity = entityByWord(ns.entityWord());
                if (entity == null) {
                    throw new CheckException(where + ": cannot resolve 'no " + ns.entityWord()
                            + " ...' — no entity matches '" + ns.entityWord()
                            + "'. State the condition concretely.");
                }
                boolean field = entities.get(entity).containsKey(ns.fieldWord());
                if (!field || !params.containsKey(ns.fieldWord())) {
                    throw new CheckException(where + ": 'no " + ns.entityWord() + " has that "
                            + ns.fieldWord() + "' needs a '" + ns.fieldWord() + "' field on " + entity
                            + " and a matching parameter");
                }
                requireDb(where, svc, "the existence phrase");
            }
            case Ast.AlreadyRegistered ar -> {
                if (!(ar.value() instanceof Ast.NameExpr n) || !params.containsKey(n.name())) {
                    throw new CheckException(where + ": 'already registered' needs a parameter to check");
                }
                boolean unique = false;
                for (Ast.Entity e : moduleEntities) {
                    unique |= e.fields().stream().anyMatch(f -> f.unique() && f.name().equals(n.name()));
                }
                if (!unique) {
                    throw new CheckException(where + ": 'already registered' needs an entity field named '"
                            + n.name() + "' declared @unique");
                }
                requireDb(where, svc, "the uniqueness phrase");
            }
            case Ast.StatusIs si -> {
                String entity = entityByWord(si.entityWord());
                if (entity == null) {
                    throw new CheckException(where + ": cannot resolve 'the " + si.entityWord()
                            + "\\u2019s ...' — no entity matches '" + si.entityWord() + "'");
                }
                Ty fieldTy = entities.get(entity).get(si.fieldWord());
                if (fieldTy == null) {
                    throw new CheckException(where + ": " + entity + " has no field '" + si.fieldWord()
                            + "'" + didYouMean(si.fieldWord(), entities.get(entity).keySet()));
                }
                if (!(fieldTy instanceof Ty.EntityTy statusEntity)
                        || !valueSets.containsKey(statusEntity.name())) {
                    throw new CheckException(where + ": '" + si.fieldWord()
                            + "' must be a closed value set to name its states");
                }
                for (String state : si.values()) {
                    if (!valueSets.get(statusEntity.name()).contains(state)) {
                        throw new CheckException(where + ": " + statusEntity.name()
                                + " has no value '" + state + "'");
                    }
                }
                requireDb(where, svc, "the status phrase");
            }
            case Ast.Prose ignored -> {
                // Free prose names the failure for the reader and the model; the examples
                // pin the behaviour, so there is nothing to resolve statically.
            }
        }
    }

    private static void requireDb(String where, Ast.Service svc, String what) {
        if (!svc.uses().contains("db")) {
            throw new CheckException(where + ": " + what + " needs the db effect on the service");
        }
    }

    /** Match a phrase word like "product" or "products" to a declared entity, case-insensitively. */
    private String entityByWord(String word) {
        String bare = word.toLowerCase(java.util.Locale.ROOT);
        String singular = bare.endsWith("s") ? bare.substring(0, bare.length() - 1) : bare;
        for (String name : entities.keySet()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals(bare) || lower.equals(singular)) {
                return name;
            }
        }
        return null;
    }

    private static boolean mentionsOldResult(Ast.Expr expr) {
        return switch (expr) {
            case Ast.OldExpr o -> mentionsResult(o.value());
            case Ast.BinExpr b -> mentionsOldResult(b.left()) || mentionsOldResult(b.right());
            case Ast.NotExpr n -> mentionsOldResult(n.value());
            case Ast.MemberExpr m -> mentionsOldResult(m.target());
            case Ast.CallExpr c -> c.args().stream().anyMatch(TypeChecker::mentionsOldResult);
            default -> false;
        };
    }

    private static boolean mentionsResult(Ast.Expr expr) {
        return switch (expr) {
            case Ast.NameExpr n -> n.name().equals("result");
            case Ast.MemberExpr m -> mentionsResult(m.target());
            case Ast.BinExpr b -> mentionsResult(b.left()) || mentionsResult(b.right());
            default -> false;
        };
    }

    /** {@code old(result...)} is a pre-call lookup: db plus a parameter naming the row's @id. */
    private void requireOldResultShape(String where, Ast.Service svc, Ast.Method m, Ty returnType) {
        requireDb(where + " ensures", svc, "old(result...)");
        String idField = returnType instanceof Ty.EntityTy et
                ? moduleEntities.stream().filter(e -> e.name().equals(et.name()))
                        .flatMap(e -> e.fields().stream()).filter(Ast.Field::id)
                        .map(Ast.Field::name).findFirst().orElse(null)
                : null;
        boolean locatable = idField != null && m.params().stream().anyMatch(p -> p.name().equals(idField));
        if (!locatable) {
            throw new CheckException(where + " ensures: old(result...) needs the method to return an @id"
                    + " entity and take a parameter named after its id field");
        }
    }

    private void checkExample(String where, Ast.Service svc, Ast.Method m, Map<String, Ty> params,
                              Ty returnType, Ast.Example ex) {
        if (!ex.call().callee().equals(m.name())) {
            throw new CheckException(where + " example calls '" + ex.call().callee()
                    + "' but the method is '" + m.name() + "'");
        }
        ex.seed().ifPresent(seed -> checkSeed(where + " example seed", svc, m, seed));
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
            case Ast.RaisesResult rr -> {
                // Declared-ness is validated in the pre-pass; nothing further to type here.
            }
            case Ast.FieldsResult fr -> {
                if (!(returnType instanceof Ty.EntityTy et)) {
                    throw new CheckException(where + " example expects result fields but the method"
                            + " returns " + returnType);
                }
                Map<String, Ty> fields = entities.get(et.name());
                for (Ast.FieldExpect fe : fr.fields()) {
                    Ty fieldTy = fields.get(fe.field());
                    if (fieldTy == null) {
                        throw new CheckException(where + " example refers to unknown field '"
                                + et.name() + "." + fe.field() + "'"
                                + didYouMean(fe.field(), fields.keySet()));
                    }
                    String feWhere = where + " example field '" + fe.field() + "'";
                    Ty valTy = infer(fe.expected(), Map.of(), feWhere);
                    fits(feWhere, fe.expected(), valTy, fieldTy);
                }
            }
            case Ast.ExprResult er -> {
                String resultWhere = where + " example result";
                Ty rt = infer(er.value(), Map.of(), resultWhere);
                // A value against a Maybe return is the present case; it fits the inner type.
                Ty target = returnType instanceof Ty.GenericTy g && g.kind().equals("Maybe")
                        ? g.args().get(0) : returnType;
                fits(resultWhere, er.value(), rt, target);
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
                                + ent.typeName() + "." + fe.field() + "'"
                                + didYouMean(fe.field(), fields.keySet()));
                    }
                    String feWhere = where + " example field '" + fe.field() + "'";
                    Ty valTy = infer(fe.expected(), Map.of(), feWhere);
                    fits(feWhere, fe.expected(), valTy, fieldTy);
                }
            }
            case Ast.NothingResult ignored -> {
                if (!(returnType instanceof Ty.GenericTy g) || !g.kind().equals("Maybe")) {
                    throw new CheckException(where + " example expects nothing, but the method"
                            + " returns " + returnType + " — only a Maybe can be absent");
                }
            }
            case Ast.WhoseResult wr -> checkWhoseResult(where, wr, returnType);
        }
    }

    /** {@code -> a User whose email is ...}: the fields exist and every expectation types. */
    private void checkWhoseResult(String where, Ast.WhoseResult wr, Ty returnType) {
        if (!(returnType instanceof Ty.EntityTy et) || !et.name().equalsIgnoreCase(wr.typeName())
                || !entities.containsKey(et.name())) {
            throw new CheckException(where + " example expects a " + wr.typeName()
                    + " but the method returns " + returnType);
        }
        Map<String, Ty> fields = entities.get(et.name());
        for (Ast.WhoseExpect we : wr.expects()) {
            Ty fieldTy = fields.get(we.field());
            if (fieldTy == null) {
                throw new CheckException(where + " example refers to unknown field '"
                        + et.name() + "." + we.field() + "'" + didYouMean(we.field(), fields.keySet()));
            }
            String feWhere = where + " example field '" + we.field() + "'";
            if (we.kind() == Ast.WhoseKind.IS_SET) {
                if (!(fieldTy instanceof Ty.GenericTy m && m.kind().equals("Maybe"))) {
                    throw new CheckException(feWhere + ": 'is set' needs a Maybe field, not " + fieldTy);
                }
                continue;
            }
            Ast.Expr value = we.value().orElseThrow();
            if (value instanceof Ast.NameExpr n && fieldTy instanceof Ty.EntityTy fieldEntity
                    && valueSets.containsKey(fieldEntity.name())) {
                // A bare constant qualifies against the field's closed value set: Placed, Member.
                if (!valueSets.get(fieldEntity.name()).contains(n.name())) {
                    throw new CheckException(feWhere + ": " + fieldEntity.name()
                            + " has no value '" + n.name() + "'");
                }
                continue;
            }
            Ty valTy = infer(value, Map.of(), feWhere);
            if (fieldTy instanceof Ty.GenericTy secret && secret.kind().equals("Secret")) {
                // The comparison is against the revealed value; text against Secret<Bytes> is
                // the not-the-raw-password idiom and compares byte forms.
                Ty inner = secret.args().get(0);
                if (!(inner.erased().equals(Ty.BYTES) && valTy.erased().equals(Ty.TEXT))) {
                    fits(feWhere, value, valTy, inner);
                }
                continue;
            }
            fits(feWhere, value, valTy, fieldTy);
        }
    }

    /** {@code on a Product with stock 5}: a stored row the harness must be able to construct. */
    private void checkSeed(String where, Ast.Service svc, Ast.Method m, Ast.Seed seed) {
        requireDb(where, svc, "seeding stored state");
        Map<String, Ty> fields = entities.get(seed.entityName());
        if (fields == null || valueSets.containsKey(seed.entityName())
                || !identified.contains(seed.entityName())) {
            throw new CheckException(where + ": '" + seed.entityName()
                    + "' must be an @id entity to seed");
        }
        for (Ast.FieldExpect fe : seed.fields()) {
            Ty fieldTy = fields.get(fe.field());
            if (fieldTy == null) {
                throw new CheckException(where + ": '" + fe.field() + "' is not a field of "
                        + seed.entityName());
            }
            Ty valTy = infer(fe.expected(), Map.of(), where);
            fits(where + " field '" + fe.field() + "'", fe.expected(), valTy, fieldTy);
        }
        String idField = moduleEntities.stream().filter(e -> e.name().equals(seed.entityName()))
                .flatMap(e -> e.fields().stream()).filter(Ast.Field::id)
                .map(Ast.Field::name).findFirst().orElseThrow();
        boolean pinned = seed.fields().stream().anyMatch(fe -> fe.field().equals(idField));
        boolean fromParam = m.params().stream().anyMatch(p -> p.name().equals(idField));
        if (!pinned && !fromParam) {
            throw new CheckException(where + ": the seeded row needs its " + idField
                    + " — pin it in the seed or take a parameter named '" + idField + "'");
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
                boolean sized = target.erased().equals(Ty.BYTES)
                        || target instanceof Ty.GenericTy g
                                && (g.kind().equals("List") || g.kind().equals("Set") || g.kind().equals("Map"));
                if (sized && (me.field().equals("length") || me.field().equals("size"))) {
                    yield Ty.INT;   // byte sequences and collections expose their length to contracts
                }
                if (!(target instanceof Ty.EntityTy et)) {
                    throw new CheckException(where + ": cannot read field '" + me.field() + "' of non-entity " + target);
                }
                Ty ft = entities.get(et.name()).get(me.field());
                if (ft == null) {
                    throw new CheckException(where + ": " + target + " has no field '" + me.field() + "'"
                            + didYouMean(me.field(), entities.get(et.name()).keySet()));
                }
                yield ft;
            }
            case Ast.CallExpr ce -> inferConstructor(ce, env, where);
            case Ast.BinExpr be -> inferBinary(be, env, where);
            case Ast.NotExpr ne -> {
                Ty t = infer(ne.value(), env, where);
                if (!t.isBool()) {
                    throw new CheckException(where + ": 'not' requires a boolean, got " + t);
                }
                yield Ty.BOOL;
            }
            case Ast.EmptyCheck ec -> {
                Ty t = infer(ec.value(), env, where);
                boolean collection = t instanceof Ty.GenericTy g
                        && (g.kind().equals("List") || g.kind().equals("Set") || g.kind().equals("Map"));
                if (!collection) {
                    throw new CheckException(where + ": 'is empty' needs a collection, got " + t);
                }
                yield Ty.BOOL;
            }
            case Ast.OldExpr oe -> {
                // Ensures is the one context where `result` is in scope; old() rides with it.
                if (!env.containsKey("result")) {
                    throw new CheckException(where + ": old(...) may only appear in ensures");
                }
                yield infer(oe.value(), env, where);
            }
            case Ast.AggExpr ae -> inferAggregate(ae, env, where);
            case Ast.ForallExpr fe -> inferForall(fe, env, where);
            case Ast.BcryptHash bh -> {
                Ty target = infer(bh.value(), env, where + " bcrypt-hash");
                boolean hashable = target.erased().equals(Ty.TEXT) || target.erased().equals(Ty.BYTES)
                        || target instanceof Ty.GenericTy g && g.kind().equals("Secret");
                if (!hashable) {
                    throw new CheckException(where + ": 'is a bcrypt hash' applies to Text, Bytes or"
                            + " a Secret of either, not " + target);
                }
                yield Ty.BOOL;
            }
        };
    }

    /**
     * {@code every product in result has category == category}: the source is a list of
     * entities, the predicate compares one of the element's fields (left) against a value
     * from the enclosing scope (right). A Maybe field compares against its inner type —
     * an absent value satisfies nothing.
     */
    private Ty inferForall(Ast.ForallExpr fe, Map<String, Ty> env, String where) {
        Ty sourceTy = infer(fe.source(), env, where + " every-source");
        if (!(sourceTy instanceof Ty.GenericTy list) || !list.kind().equals("List")
                || !(list.args().get(0) instanceof Ty.EntityTy element)) {
            throw new CheckException(where + ": 'every " + fe.var() + " in ...' needs a list of"
                    + " entities, got " + sourceTy);
        }
        if (!(fe.predicate() instanceof Ast.BinExpr be && be.left() instanceof Ast.NameExpr fieldName)) {
            throw new CheckException(where + ": an every-clause predicate is '<field> <op> <value>'");
        }
        Map<String, Ty> fields = entities.get(element.name());
        Ty fieldTy = fields.get(fieldName.name());
        if (fieldTy == null) {
            throw new CheckException(where + ": " + element.name() + " has no field '"
                    + fieldName.name() + "'" + didYouMean(fieldName.name(), fields.keySet()));
        }
        Ty valueTy = infer(be.right(), env, where + " every-value");
        boolean equality = be.op().equals("==") || be.op().equals("!=");
        if (equality && fieldTy instanceof Ty.GenericTy maybe && maybe.kind().equals("Maybe")) {
            fits(where + " every-value", be.right(), valueTy, maybe.args().get(0));
        } else {
            fits(where + " every-value", be.right(), valueTy, fieldTy);
        }
        return Ty.BOOL;
    }

    private Ty inferAggregate(Ast.AggExpr ag, Map<String, Ty> env, String where) {
        Ty element = switch (ag.source()) {
            case Ast.SourceExpr s -> {
                Ty t = infer(s.expr(), env, where);
                if (!(t instanceof Ty.GenericTy g
                        && (g.kind().equals("List") || g.kind().equals("Set")))) {
                    throw new CheckException(where + ": the aggregate source must be a collection, got " + t);
                }
                yield g.args().get(0);
            }
            case Ast.AllOf all -> {
                String entity = entityByWord(all.word());
                if (entity == null) {
                    throw new CheckException(where + ": cannot resolve 'all " + all.word()
                            + "' to a stored entity");
                }
                if (currentService == null || !currentService.uses().contains("db")) {
                    throw new CheckException(where + ": 'all " + all.word() + "' needs the db effect");
                }
                yield Ty.entity(entity);
            }
        };
        Map<String, Ty> inner = new LinkedHashMap<>(env);
        inner.put(ag.var(), element);
        ag.where().ifPresent(w -> {
            Ty t = infer(w, inner, where + " where");
            if (!t.isBool()) {
                throw new CheckException(where + ": the where filter must be boolean, got " + t);
            }
        });
        Ty valueTy = infer(ag.value(), inner, where);
        if (ag.kind().equals("count")) {
            return Ty.INT;
        }
        Ty e = valueTy.erased();
        if (!e.equals(Ty.INT) && !e.equals(Ty.MONEY)) {
            throw new CheckException(where + ": sum of needs Int or Money elements, got " + valueTy);
        }
        return e;
    }

    /** A call in an expression: an entity constructor, max/min, or an effect-free helper method. */
    private Ty inferConstructor(Ast.CallExpr ce, Map<String, Ty> env, String where) {
        if (valueSets.containsKey(ce.callee())) {
            throw new CheckException(where + ": the value set of " + ce.callee() + " is closed; use its"
                    + " declared constants (" + ce.callee() + "." + valueSets.get(ce.callee()).get(0) + ", ...)");
        }
        if (ce.callee().equals("max") || ce.callee().equals("min")) {
            return inferExtreme(ce, env, where);
        }
        LinkedHashMap<String, Ty> fields = entities.get(ce.callee());
        if (fields == null) {
            if (ce.callee().endsWith("_with")) {
                return inferFixture(ce, where);
            }
            return inferHelperCall(ce, env, where);
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

    /** {@code max(a, b)} / {@code min(a, b)} over two values of the same ordered type. */
    private Ty inferExtreme(Ast.CallExpr ce, Map<String, Ty> env, String where) {
        if (ce.args().size() != 2) {
            throw new CheckException(where + ": " + ce.callee() + " takes two arguments");
        }
        Ty l = infer(ce.args().get(0), env, where).erased();
        Ty r = infer(ce.args().get(1), env, where).erased();
        boolean ordered = l.equals(r) && (l.equals(Ty.INT) || l.equals(Ty.MONEY) || l.equals(Ty.INSTANT));
        if (!ordered) {
            throw new CheckException(where + ": " + ce.callee() + " needs two values of the same ordered"
                    + " type, got " + l + " and " + r);
        }
        return l;
    }

    /**
     * {@code wallet_with(100eur)} — a fixture witness: each literal pins the one field of
     * its type, defaults fill the rest, and anything underivable must be pinned.
     */
    private Ty inferFixture(Ast.CallExpr ce, String where) {
        String word = ce.callee().substring(0, ce.callee().length() - "_with".length());
        String entity = entityByWord(word);
        if (entity == null || valueSets.containsKey(entity) || errorEntities.contains(entity)) {
            throw new CheckException(where + ": cannot resolve fixture '" + ce.callee()
                    + "' — no entity matches '" + word + "'");
        }
        LinkedHashMap<String, Ty> fields = entities.get(entity);
        java.util.Set<String> pinned = new java.util.HashSet<>();
        for (Ast.Expr arg : ce.args()) {
            if (!isLiteral(arg)) {
                throw new CheckException(where + ": fixture arguments are literals");
            }
            Ty at = infer(arg, Map.of(), where);
            List<String> candidates = fields.entrySet().stream()
                    .filter(f -> !pinned.contains(f.getKey()))
                    .filter(f -> at.erased().equals(f.getValue().erased()))
                    .map(Map.Entry::getKey).toList();
            if (candidates.isEmpty()) {
                throw new CheckException(where + ": " + entity + " has no unpinned " + at
                        + " field to take " + literalText(arg));
            }
            if (candidates.size() > 1) {
                throw new CheckException(where + ": ambiguous fixture value " + literalText(arg)
                        + " — " + entity + " has " + String.join(", ", candidates));
            }
            String field = candidates.get(0);
            fits(where + " fixture field '" + field + "'", arg, at, fields.get(field));
            pinned.add(field);
        }
        for (Ast.Entity e : moduleEntities) {
            if (!e.name().equals(entity)) {
                continue;
            }
            for (Ast.Field f : e.fields()) {
                if (!pinned.contains(f.name()) && !f.id() && !derivableWitness(f.type())) {
                    throw new CheckException(where + ": pin " + entity + "." + f.name()
                            + " — its type has no derivable witness value");
                }
            }
        }
        return Ty.entity(entity);
    }

    /** A contract may call any module method that declares no effects. */
    private Ty inferHelperCall(Ast.CallExpr ce, Map<String, Ty> env, String where) {
        Helper helper = helpers.get(ce.callee());
        if (helper == null) {
            throw new CheckException(where + ": '" + ce.callee()
                    + "' is neither an entity constructor nor a module method");
        }
        if (helper.ambiguous()) {
            throw new CheckException(where + ": '" + ce.callee()
                    + "' is declared by more than one service; contracts need an unambiguous name");
        }
        if (!helper.uses().isEmpty()) {
            throw new CheckException(where + ": contracts may only call effect-free methods, but '"
                    + ce.callee() + "' uses " + String.join(", ", helper.uses()));
        }
        checkArgCount(where, ce.callee(), ce.args().size(), helper.sig().params().size());
        for (int i = 0; i < ce.args().size(); i++) {
            Ty at = infer(ce.args().get(i), env, where);
            fits(where + ": " + ce.callee() + " argument " + (i + 1), ce.args().get(i), at,
                    helper.sig().params().get(i));
        }
        return helper.sig().returnType();
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
            if (errorEntities.contains(name)) {
                throw new CheckException(where + ": '" + name
                        + "' is an error (named in raises) and cannot be used as data");
            }
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

    /** A typo hint: the nearest existing name, when one is close enough to be a likely slip. */
    private static String didYouMean(String wrong, java.util.Collection<String> candidates) {
        String best = null;
        int bestDistance = Math.max(2, wrong.length() / 3) + 1;
        for (String candidate : candidates) {
            int d = editDistance(wrong.toLowerCase(java.util.Locale.ROOT),
                    candidate.toLowerCase(java.util.Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best == null ? "" : "\n  -> did you mean '" + best + "'?";
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int subst = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(subst, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
