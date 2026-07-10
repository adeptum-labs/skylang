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
            case "Int" -> "long";
            case "Text", "Email" -> "String";
            case "Bool" -> "boolean";
            case "Instant" -> "java.time.Instant";
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
        return exprToJava(expr, env, Set.of());
    }

    /**
     * Translate a contract/expression to Java. {@code env} maps SkyLang names (parameters,
     * {@code result}) to the Java identifiers they are bound to in the generated test;
     * {@code valueEntities} names the enum-like entities so {@code Role.Member} lowers to
     * its constant rather than an accessor call. Binary operators lower to the overloaded
     * helper methods staged into every generated test class, so javac's overload resolution
     * picks primitive or object semantics.
     */
    public static String exprToJava(Ast.Expr expr, Map<String, String> env, Set<String> valueEntities) {
        return switch (expr) {
            case Ast.IntLit i -> i.value() + "L";
            case Ast.StrLit s -> javaString(s.value());
            case Ast.BoolLit b -> String.valueOf(b.value());
            case Ast.MoneyLit m -> "Money.of(\"" + m.amount().toPlainString() + "\", \"" + m.currency() + "\")";
            case Ast.NameExpr n -> env.getOrDefault(n.name(), n.name());
            case Ast.MemberExpr m ->
                    m.target() instanceof Ast.NameExpr n && !env.containsKey(n.name())
                            && valueEntities.contains(n.name())
                            ? n.name() + "." + m.field()
                            : "(" + exprToJava(m.target(), env, valueEntities) + ")." + m.field() + "()";
            case Ast.CallExpr c -> "new " + c.callee() + "(" + c.args().stream()
                    .map(a -> exprToJava(a, env, valueEntities)).collect(Collectors.joining(", ")) + ")";
            case Ast.BinExpr b -> binToJava(b, env, valueEntities);
        };
    }

    private static String binToJava(Ast.BinExpr b, Map<String, String> env, Set<String> valueEntities) {
        String l = exprToJava(b.left(), env, valueEntities);
        String r = exprToJava(b.right(), env, valueEntities);
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
