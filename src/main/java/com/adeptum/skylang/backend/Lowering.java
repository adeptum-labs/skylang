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

package com.adeptum.skylang.backend;

import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.types.Builtins;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/** Lowers SkyLang types and expressions to their JVM-profile Java forms. */
public final class Lowering {

    private Lowering() {
    }

    /** The module's declared refined types by name, for erasing named types to their base. */
    public static Map<String, Ast.TypeDecl> typesOf(Ast.Module module) {
        Map<String, Ast.TypeDecl> types = new LinkedHashMap<>();
        for (Ast.TypeDecl d : module.types()) {
            types.put(d.name(), d);
        }
        return types;
    }

    /**
     * {@code Int -> long}, {@code Text -> String}, {@code Money/Bytes/Secret} map to staged
     * support classes, {@code Maybe<T> -> Optional}, containers to {@code java.util}, and a
     * declared refined type erases to its base. Entities map to their record class.
     */
    public static String javaType(Ast.Type type, Map<String, Ast.TypeDecl> types) {
        return switch (type) {
            case Ast.TypeRef ref -> ref.list()
                    ? "java.util.List<" + boxed(javaName(ref.name(), types)) + ">"
                    : javaName(ref.name(), types);
            case Ast.RangedType r -> javaName(r.base(), types);
            case Ast.GenericType g -> {
                String args = g.args().stream()
                        .map(a -> boxed(javaType(a, types)))
                        .collect(Collectors.joining(", "));
                yield switch (g.name()) {
                    case "Maybe" -> "java.util.Optional<" + args + ">";
                    case "Secret" -> "Secret<" + args + ">";
                    default -> "java.util." + g.name() + "<" + args + ">";
                };
            }
        };
    }

    /** Lowering without declared-type context; named refined types cannot appear. */
    public static String javaType(Ast.Type type) {
        return javaType(type, Map.of());
    }

    private static String javaName(String name, Map<String, Ast.TypeDecl> types) {
        Ast.TypeDecl declared = types.get(name);
        if (declared != null) {
            return javaName(declared.base(), types);
        }
        return switch (name) {
            case "Int", "Percentage" -> "long";
            case "Text", "Email", "Currency" -> "String";
            case "Bool" -> "boolean";
            case "Instant" -> "java.time.Instant";
            case "Date" -> "java.time.LocalDate";
            case "DateTime" -> "java.time.LocalDateTime";
            case "Duration" -> "java.time.Duration";
            default -> name;   // Money, Bytes, and entities map to their staged class
        };
    }

    private static String boxed(String javaType) {
        return switch (javaType) {
            case "long" -> "Long";
            case "boolean" -> "Boolean";
            default -> javaType;
        };
    }

    /** The names of the module's enum-like entities, whose members lower to constants. */
    public static Set<String> valueEntities(Ast.Module module) {
        return module.entities().stream()
                .filter(e -> !e.values().isEmpty())
                .map(Ast.Entity::name)
                .collect(Collectors.toSet());
    }

    /** A Java literal/expression for an example argument or expected value. */
    public static String javaValue(Ast.Expr expr) {
        return exprToJava(expr, Map.of(), Set.of());
    }

    public static String javaValue(Ast.Expr expr, Set<String> valueEntities) {
        return exprToJava(expr, Map.of(), valueEntities);
    }

    public static String exprToJava(Ast.Expr expr, Map<String, String> env) {
        return exprToJava(expr, env, Set.of(), null);
    }

    public static String exprToJava(Ast.Expr expr, Map<String, String> env, Set<String> valueEntities) {
        return exprToJava(expr, env, valueEntities, null);
    }

    /** Module-aware lowering: helper calls, value constants and 'all' sources resolve. */
    public static String exprToJava(Ast.Expr expr, Map<String, String> env, Ast.Module module) {
        return exprToJava(expr, env, valueEntities(module), module);
    }

    /**
     * Translate a contract/expression to Java. {@code env} maps SkyLang names (parameters,
     * {@code result}) to the Java identifiers they are bound to in the generated test;
     * {@code valueEntities} names the enum-like entities so {@code Role.Member} lowers to
     * its constant rather than an accessor call. Binary operators lower to the overloaded
     * helper methods staged into every generated test class, so javac's overload resolution
     * picks primitive or object semantics.
     */
    private static String exprToJava(Ast.Expr expr, Map<String, String> env, Set<String> valueEntities,
                                     Ast.Module module) {
        return switch (expr) {
            case Ast.IntLit i -> i.value() + "L";
            case Ast.StrLit s -> javaString(s.value());
            case Ast.BoolLit b -> String.valueOf(b.value());
            case Ast.MoneyLit m -> "Money.of(\"" + m.amount().toPlainString() + "\", \"" + m.currency() + "\")";
            case Ast.DurationLit d -> "java.time.Duration.of" + durationUnit(d.unit()) + "(" + d.amount() + ")";
            case Ast.NameExpr n -> env.getOrDefault(n.name(), n.name());
            case Ast.MemberExpr m -> {
                if (m.target() instanceof Ast.NameExpr n && !env.containsKey(n.name())
                        && valueEntities.contains(n.name())) {
                    yield n.name() + "." + m.field();
                }
                String target = exprToJava(m.target(), env, valueEntities, module);
                // length/size are reserved measurements, dispatched at runtime by the helper.
                yield m.field().equals("length") || m.field().equals("size")
                        ? "len(" + target + ")"
                        : "(" + target + ")." + m.field() + "()";
            }
            case Ast.CallExpr c -> callToJava(c, env, valueEntities, module);
            case Ast.BinExpr b -> binToJava(b, env, valueEntities, module);
            case Ast.NotExpr n -> "!(" + exprToJava(n.value(), env, valueEntities, module) + ")";
            case Ast.EmptyCheck e -> "(" + exprToJava(e.value(), env, valueEntities, module) + ").isEmpty()";
            case Ast.OldExpr o ->
                    throw new UnsupportedOperationException("old(...) must be snapshotted before lowering");
            case Ast.AggExpr a -> aggToJava(a, env, valueEntities, module);
            case Ast.ForallExpr f -> forallToJava(f, env, valueEntities, module);
            case Ast.BcryptHash b ->
                    "isBcryptHash(" + exprToJava(b.value(), env, valueEntities, module) + ")";
        };
    }

    /**
     * {@code every product in result has category == category} lowers to an allMatch over the
     * list. Equality goes through the hasEq helper, which unwraps a Maybe field (absent
     * satisfies nothing); other comparisons apply to the raw field.
     */
    private static String forallToJava(Ast.ForallExpr f, Map<String, String> env,
                                       Set<String> valueEntities, Ast.Module module) {
        if (!(f.predicate() instanceof Ast.BinExpr be && be.left() instanceof Ast.NameExpr field)) {
            throw new UnsupportedOperationException(
                    "an every-clause predicate is '<field> <op> <value>'");
        }
        String source = exprToJava(f.source(), env, valueEntities, module);
        String lhs = f.var() + "." + field.name() + "()";
        String rhs = exprToJava(be.right(), env, valueEntities, module);
        String body = switch (be.op()) {
            case "==" -> "hasEq(" + lhs + ", " + rhs + ")";
            case "!=" -> "!hasEq(" + lhs + ", " + rhs + ")";
            case "<" -> "lt(" + lhs + ", " + rhs + ")";
            case "<=" -> "le(" + lhs + ", " + rhs + ")";
            case ">" -> "gt(" + lhs + ", " + rhs + ")";
            case ">=" -> "ge(" + lhs + ", " + rhs + ")";
            default -> throw new UnsupportedOperationException(
                    "an every-clause compares with ==, !=, <, <=, > or >=");
        };
        return "(" + source + ").stream().allMatch(" + f.var() + " -> " + body + ")";
    }

    /** An entity constructor, max/min through the helpers, or an effect-free module method. */
    private static String callToJava(Ast.CallExpr c, Map<String, String> env, Set<String> valueEntities,
                                     Ast.Module module) {
        String args = c.args().stream()
                .map(a -> exprToJava(a, env, valueEntities, module))
                .collect(Collectors.joining(", "));
        if (c.callee().equals("max") || c.callee().equals("min")) {
            return c.callee() + "(" + args + ")";
        }
        if (module != null && c.callee().endsWith("_with")
                && module.entities().stream().noneMatch(e -> e.name().equals(c.callee()))) {
            String fixture = fixtureWitness(c, module);
            if (fixture != null) {
                return fixture;
            }
        }
        if (module != null && module.entities().stream().noneMatch(e -> e.name().equals(c.callee()))) {
            String owner = module.services().stream()
                    .filter(s -> s.methods().stream().anyMatch(m -> m.name().equals(c.callee())))
                    .map(Ast.Service::name).findFirst().orElse(null);
            if (owner != null) {
                return "new " + owner + "()." + c.callee() + "(" + args + ")";
            }
        }
        return "new " + c.callee() + "(" + args + ")";
    }

    /**
     * Aggregates fold a stream: {@code count} filters and counts; {@code sum} maps and adds
     * through the runtime-dispatching sumOf helper, so Int and Money elements both work.
     */
    private static String aggToJava(Ast.AggExpr a, Map<String, String> env, Set<String> valueEntities,
                                    Ast.Module module) {
        String source = switch (a.source()) {
            case Ast.AllOf all -> "db.all" + entityByWord(all.word(), module) + "s()";
            case Ast.SourceExpr s -> "(" + exprToJava(s.expr(), env, valueEntities, module) + ")";
        };
        Map<String, String> inner = new LinkedHashMap<>(env);
        inner.put(a.var(), a.var());
        String filter = a.where()
                .map(w -> ".filter(" + a.var() + " -> " + exprToJava(w, inner, valueEntities, module) + ")")
                .orElse("");
        if (a.kind().equals("count")) {
            return source + ".stream()" + filter + ".count()";
        }
        return "sumOf(" + source + ".stream()" + filter + ".map(" + a.var() + " -> (Object) ("
                + exprToJava(a.value(), inner, valueEntities, module) + ")).toList())";
    }

    /** {@code wallet_with(100eur)} — the witness the checker validated, as a constructor call. */
    private static String fixtureWitness(Ast.CallExpr c, Ast.Module module) {
        String word = c.callee().substring(0, c.callee().length() - "_with".length());
        Ast.Entity entity = module.entities().stream()
                .filter(e -> {
                    String lower = e.name().toLowerCase(java.util.Locale.ROOT);
                    return lower.equals(word.toLowerCase(java.util.Locale.ROOT));
                })
                .findFirst().orElse(null);
        if (entity == null) {
            return null;
        }
        Map<String, Ast.TypeDecl> types = typesOf(module);
        Map<String, Ast.Expr> pins = new LinkedHashMap<>();
        for (Ast.Expr arg : c.args()) {
            for (Ast.Field f : entity.fields()) {
                if (!pins.containsKey(f.name()) && literalMatches(arg, f.type(), types)) {
                    pins.put(f.name(), arg);
                    break;
                }
            }
        }
        long id = Math.floorMod(pins.values().stream().map(Lowering::skyText)
                .collect(Collectors.joining(",")).hashCode(), 997) + 1;
        return entityWitness(entity.name(), pins, id, module, types, valueEntities(module));
    }

    /** Whether the literal's kind matches the field's erased base — the checker's rule. */
    private static boolean literalMatches(Ast.Expr literal, Ast.Type type, Map<String, Ast.TypeDecl> types) {
        String base = switch (type) {
            case Ast.TypeRef ref -> {
                Ast.TypeDecl d = types.get(ref.name());
                yield ref.list() ? "List" : d != null ? d.base() : ref.name();
            }
            case Ast.RangedType r -> r.base();
            case Ast.GenericType g -> g.name();
        };
        return switch (literal) {
            case Ast.IntLit ignored -> base.equals("Int") || base.equals("Percentage");
            case Ast.StrLit ignored -> base.equals("Text") || base.equals("Email")
                    || base.equals("Currency");
            case Ast.BoolLit ignored -> base.equals("Bool");
            case Ast.MoneyLit ignored -> base.equals("Money");
            case Ast.DurationLit ignored -> base.equals("Duration");
            default -> false;
        };
    }

    /** A full-constructor witness: pinned fields, the given id, distinct uniques, defaults elsewhere. */
    public static String entityWitness(String entity, Map<String, Ast.Expr> pins, long id,
                                       Ast.Module module, Map<String, Ast.TypeDecl> types,
                                       Set<String> values) {
        Ast.Entity e = module.entities().stream()
                .filter(x -> x.name().equals(entity)).findFirst().orElseThrow();
        List<String> args = new java.util.ArrayList<>();
        for (Ast.Field f : e.fields()) {
            if (pins.containsKey(f.name())) {
                args.add(javaValue(pins.get(f.name()), values));
            } else if (f.id()) {
                args.add(f.type() instanceof Ast.TypeRef ref && ref.name().equals("Text")
                        ? "\"w" + id + "\"" : id + "L");
            } else if (f.unique()) {
                // Distinct witnesses must not collide on a unique column. A scoped column keeps
                // this per-column distinctness too: column-distinct implies tuple-distinct.
                String javaType = javaType(f.type(), types);
                args.add(switch (javaType) {
                    case "long" -> (1000 + id) + "L";
                    case "String" -> f.type() instanceof Ast.TypeRef ref && ref.name().equals("Email")
                            ? "\"w" + id + "@example.com\"" : "\"w" + id + "\"";
                    default -> witnessFallback(entity, f, types, module);
                });
            } else {
                args.add(witnessFallback(entity, f, types, module));
            }
        }
        return "new " + entity + "(" + String.join(", ", args) + ")";
    }

    private static String witnessFallback(String entity, Ast.Field f, Map<String, Ast.TypeDecl> types,
                                          Ast.Module module) {
        String fallback = defaultJavaValue(f.type(), types, module);
        if (fallback == null) {
            throw new IllegalStateException("cannot derive a witness for "
                    + entity + "." + f.name());
        }
        return fallback;
    }

    /** Match "products" to the module's Product entity, as the checker did. */
    private static String entityByWord(String word, Ast.Module module) {
        String bare = word.toLowerCase(java.util.Locale.ROOT);
        String singular = bare.endsWith("s") ? bare.substring(0, bare.length() - 1) : bare;
        return module.entities().stream()
                .map(Ast.Entity::name)
                .filter(n -> {
                    String lower = n.toLowerCase(java.util.Locale.ROOT);
                    return lower.equals(bare) || lower.equals(singular);
                })
                .findFirst().orElseThrow();
    }

    private static String binToJava(Ast.BinExpr b, Map<String, String> env, Set<String> valueEntities,
                                    Ast.Module module) {
        String l = exprToJava(b.left(), env, valueEntities, module);
        String r = exprToJava(b.right(), env, valueEntities, module);
        return switch (b.op()) {
            case "and" -> "(" + l + " && " + r + ")";
            case "or" -> "(" + l + " || " + r + ")";
            case "==" -> "eq(" + l + ", " + r + ")";
            case "!=" -> "!eq(" + l + ", " + r + ")";
            case "<" -> "lt(" + l + ", " + r + ")";
            case "<=" -> "le(" + l + ", " + r + ")";
            case ">" -> "gt(" + l + ", " + r + ")";
            case ">=" -> "ge(" + l + ", " + r + ")";
            case "+" -> "plus(" + l + ", " + r + ")";
            case "-" -> "minus(" + l + ", " + r + ")";
            case "*" -> "times(" + l + ", " + r + ")";
            case "/" -> "div(" + l + ", " + r + ")";
            default -> throw new IllegalArgumentException("unknown operator '" + b.op() + "'");
        };
    }

    /**
     * Java statements enforcing a refined type's predicate on {@code var} at a construction
     * point, one per line, or an empty string when the type carries no checkable rule.
     * Where-predicates are emitted for the {@code amount}-comparison shapes; anything beyond
     * them has no static lowering and is left to the synthesized body's contracts.
     */
    public static String javaCheck(String var, Ast.Type type, Map<String, Ast.TypeDecl> types, String label) {
        return switch (type) {
            case Ast.RangedType r -> rangeCheck(var, r.base(), r.lo(), r.hi(), label,
                    "Int".equals(r.base()) ? "Int" + rangeText(r.lo(), r.hi()) : "Text" + rangeText(r.lo(), r.hi()));
            case Ast.TypeRef ref -> {
                if (ref.list()) {
                    yield "";
                }
                if (ref.name().equals("Email")) {
                    yield matchesCheck(var, Builtins.EMAIL_REGEX, label, "Email");
                }
                if (ref.name().equals("Currency")) {
                    yield matchesCheck(var, Builtins.CURRENCY_REGEX, label, "Currency");
                }
                if (ref.name().equals("Percentage")) {
                    yield rangeCheck(var, "Int", java.util.OptionalLong.of(0),
                            java.util.OptionalLong.of(100), label, "Percentage");
                }
                Ast.TypeDecl d = types.get(ref.name());
                if (d == null) {
                    yield "";
                }
                yield switch (d.refinement()) {
                    case Ast.Range r -> rangeCheck(var, d.base(), r.lo(), r.hi(), label, d.name());
                    case Ast.Matching m -> matchesCheck(var, m.regex(), label, d.name());
                    case Ast.Where w -> whereCheck(var, d.base(), w.predicate(), label, d.name());
                };
            }
            case Ast.GenericType ignored -> "";
        };
    }

    private static String rangeText(OptionalLong lo, OptionalLong hi) {
        return "(" + (lo.isPresent() ? lo.getAsLong() : "") + ".." + (hi.isPresent() ? hi.getAsLong() : "") + ")";
    }

    private static String rangeCheck(String var, String base, OptionalLong lo, OptionalLong hi,
                                     String label, String rule) {
        String v = "Int".equals(base) ? var : var + ".length()";
        StringBuilder cond = new StringBuilder();
        lo.ifPresent(b -> cond.append(v).append(" < ").append(b).append("L"));
        hi.ifPresent(b -> cond.append(cond.isEmpty() ? "" : " || ").append(v).append(" > ").append(b).append("L"));
        return cond.isEmpty() ? "" : throwUnless("!(" + cond + ")", label, rule);
    }

    private static String matchesCheck(String var, String regex, String label, String rule) {
        return throwUnless(var + ".matches(" + javaString(regex) + ")", label, rule);
    }

    private static String whereCheck(String var, String base, Ast.Expr pred, String label, String rule) {
        String cond = whereCondition(var, base, pred);
        return cond == null ? "" : throwUnless(cond, label, rule);
    }

    private static String whereCondition(String var, String base, Ast.Expr pred) {
        if (!(pred instanceof Ast.BinExpr be)) {
            return null;
        }
        if (be.op().equals("and") || be.op().equals("or")) {
            String l = whereCondition(var, base, be.left());
            String r = whereCondition(var, base, be.right());
            return l == null || r == null ? null
                    : "(" + l + (be.op().equals("and") ? " && " : " || ") + r + ")";
        }
        if (!(be.right() instanceof Ast.IntLit i) || !isComparison(be.op())) {
            return null;
        }
        if (be.left() instanceof Ast.NameExpr n && n.name().equals("amount") && base.equals("Money")) {
            return var + ".amount().compareTo(java.math.BigDecimal.valueOf(" + i.value() + "L)) " + be.op() + " 0";
        }
        if (be.left() instanceof Ast.NameExpr n && n.name().equals("value") && base.equals("Int")) {
            return var + " " + be.op() + " " + i.value() + "L";
        }
        return null;
    }

    private static boolean isComparison(String op) {
        return switch (op) {
            case "<", "<=", ">", ">=", "==", "!=" -> true;
            default -> false;
        };
    }

    private static String throwUnless(String condition, String label, String rule) {
        return "if (!(" + condition + ")) throw new IllegalArgumentException(\""
                + label + " violates " + rule + "\");\n";
    }

    /**
     * A Java expression producing a valid value of the given type, for the witness
     * arguments of generated raises/requires tests — or null when none is derivable
     * (a regex-refined text, a secret). Entities recurse through their required fields.
     */
    public static String defaultJavaValue(Ast.Type type, Map<String, Ast.TypeDecl> types, Ast.Module module) {
        if (type instanceof Ast.TypeRef ref && ref.list()) {
            return "java.util.List.of()";
        }
        if (type instanceof Ast.GenericType g) {
            return switch (g.name()) {
                case "Maybe" -> "java.util.Optional.empty()";
                case "List" -> "java.util.List.of()";
                case "Set" -> "java.util.Set.of()";
                case "Map" -> "java.util.Map.of()";
                default -> null;   // Secret has no safe default
            };
        }
        if (type instanceof Ast.RangedType r) {
            return r.base().equals("Int")
                    ? Math.max(r.lo().orElse(1), 1) + "L"
                    : "\"x\"";
        }
        Ast.TypeRef ref = (Ast.TypeRef) type;
        Ast.TypeDecl declared = types.get(ref.name());
        if (declared != null) {
            return switch (declared.refinement()) {
                case Ast.Range range -> declared.base().equals("Int")
                        ? Math.max(range.lo().orElse(1), 1) + "L" : "\"x\"";
                case Ast.Matching ignored -> null;
                case Ast.Where ignored -> declared.base().equals("Money") ? "Money.of(\"1\", \"EUR\")" : null;
            };
        }
        return switch (ref.name()) {
            case "Int", "Percentage" -> "1L";
            case "Text" -> "\"x\"";
            case "Bool" -> "true";
            case "Email" -> "\"a@example.com\"";
            case "Currency" -> "\"EUR\"";
            case "Money" -> "Money.of(\"1\", \"EUR\")";
            case "Instant" -> "java.time.Instant.parse(\"2026-01-01T00:00:00Z\")";
            case "Date" -> "java.time.LocalDate.parse(\"2026-01-01\")";
            case "DateTime" -> "java.time.LocalDateTime.parse(\"2026-01-01T00:00:00\")";
            case "Duration" -> "java.time.Duration.ofHours(1)";
            case "Bytes" -> "Bytes.ofUtf8(\"x\")";
            default -> defaultEntityValue(ref.name(), types, module);
        };
    }

    private static String defaultEntityValue(String name, Map<String, Ast.TypeDecl> types, Ast.Module module) {
        for (Ast.Entity e : module.entities()) {
            if (!e.name().equals(name)) {
                continue;
            }
            if (!e.values().isEmpty()) {
                return name + "." + e.values().get(0).name();
            }
            // The omitting constructor covers the trailing defaulted fields.
            List<String> args = new java.util.ArrayList<>();
            for (int i = 0; i < requiredFields(e); i++) {
                String value = defaultJavaValue(e.fields().get(i).type(), types, module);
                if (value == null) {
                    return null;
                }
                args.add(value);
            }
            return "new " + name + "(" + String.join(", ", args) + ")";
        }
        return null;
    }

    private static int requiredFields(Ast.Entity e) {
        int required = e.fields().size();
        while (required > 0 && e.fields().get(required - 1).defaultValue().isPresent()) {
            required--;
        }
        return required;
    }

    /** The {@code java.time.Duration.of…} factory suffix for a duration unit ({@code d h m s}). */
    private static String durationUnit(String unit) {
        return switch (unit) {
            case "d" -> "Days";
            case "h" -> "Hours";
            case "m" -> "Minutes";
            default -> "Seconds";   // "s"
        };
    }

    /** The expression as written in SkyLang, for guard and assertion messages. */
    public static String skyText(Ast.Expr expr) {
        return switch (expr) {
            case Ast.IntLit i -> Long.toString(i.value());
            case Ast.StrLit s -> "\"" + s.value() + "\"";
            case Ast.BoolLit b -> Boolean.toString(b.value());
            case Ast.MoneyLit m -> m.amount().toPlainString() + m.currency().toLowerCase(java.util.Locale.ROOT);
            case Ast.DurationLit d -> d.amount() + d.unit();
            case Ast.NameExpr n -> n.name();
            case Ast.MemberExpr m -> skyText(m.target()) + "." + m.field();
            case Ast.CallExpr c -> c.callee() + "("
                    + c.args().stream().map(Lowering::skyText).collect(Collectors.joining(", ")) + ")";
            case Ast.BinExpr b -> skyText(b.left()) + " " + b.op() + " " + skyText(b.right());
            case Ast.NotExpr n -> "not " + skyText(n.value());
            case Ast.OldExpr o -> "old(" + skyText(o.value()) + ")";
            case Ast.EmptyCheck e -> skyText(e.value()) + " is empty";
            case Ast.AggExpr a -> a.kind() + " of (...)";
            case Ast.ForallExpr f -> "every " + f.var() + " in " + skyText(f.source())
                    + " has " + skyText(f.predicate());
            case Ast.BcryptHash b -> skyText(b.value()) + " is a bcrypt hash";
        };
    }

    /**
     * The construction checks a module's require-policies impose on values of the named
     * declared type — plain-Java conditions (no helper methods exist at construction sites),
     * raising the policy's error.
     */
    public static String policyChecks(String var, Ast.Type type, Ast.Module module) {
        if (!(type instanceof Ast.TypeRef ref) || ref.list()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Ast.TypeDecl> types = typesOf(module);
        for (Ast.Policy p : module.policies()) {
            if (!(p.whenever() instanceof Ast.Constructed c) || !c.typeWord().equals(ref.name())
                    || !(p.rule() instanceof Ast.RequireRule rule)) {
                continue;
            }
            Ast.TypeDecl declared = types.get(ref.name());
            boolean text = declared != null && javaName(declared.base(), types).equals("String");
            List<String> conditions = new java.util.ArrayList<>();
            for (Ast.ReqTerm term : rule.terms()) {
                String condition = switch (term) {
                    case Ast.Contains ignored ->
                            var + ".chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))";
                    case Ast.TermExpr te -> plainCondition(te.expr(), var, text);
                    case Ast.ProseTerm ignored -> null;   // prose binds through the prompt, not a guard
                };
                if (condition != null) {
                    conditions.add(condition);
                }
            }
            String consequence = rule.raise()
                    .map(error -> "throw new " + error + "();")
                    .orElse("throw new IllegalArgumentException(\"policy " + p.name() + "\");");
            sb.append("if (!(").append(String.join(" && ", conditions)).append(")) ")
                    .append(consequence).append('\n');
        }
        return sb.toString();
    }

    /**
     * Policy predicates range over {@code value} and {@code length} only, so they lower to
     * plain Java operators; string equality goes through equals.
     */
    private static String plainCondition(Ast.Expr e, String var, boolean text) {
        return switch (e) {
            case Ast.NameExpr n -> switch (n.name()) {
                case "value" -> var;
                case "length" -> var + ".length()";
                default -> n.name();
            };
            case Ast.IntLit i -> i.value() + "L";
            case Ast.StrLit s -> javaString(s.value());
            case Ast.BoolLit b -> String.valueOf(b.value());
            case Ast.NotExpr n -> "!(" + plainCondition(n.value(), var, text) + ")";
            case Ast.BinExpr b -> {
                String l = plainCondition(b.left(), var, text);
                String r = plainCondition(b.right(), var, text);
                yield switch (b.op()) {
                    case "and" -> "(" + l + " && " + r + ")";
                    case "or" -> "(" + l + " || " + r + ")";
                    case "==" -> stringOperand(b, text) ? l + ".equals(" + r + ")" : l + " == " + r;
                    case "!=" -> stringOperand(b, text) ? "!" + l + ".equals(" + r + ")" : l + " != " + r;
                    default -> l + " " + b.op() + " " + r;
                };
            }
            default -> throw new IllegalStateException("policy predicates lower to plain Java only");
        };
    }

    private static boolean stringOperand(Ast.BinExpr b, boolean text) {
        return b.left() instanceof Ast.StrLit || b.right() instanceof Ast.StrLit
                || text && (b.left() instanceof Ast.NameExpr n && n.name().equals("value")
                        || b.right() instanceof Ast.NameExpr m && m.name().equals("value"));
    }

    public static String javaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
