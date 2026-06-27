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

import java.util.Map;
import java.util.stream.Collectors;

/** Lowers SkyLang types and expressions to their JVM-profile Java forms. */
public final class Lowering {

    private Lowering() {
    }

    /** {@code Int -> long}, {@code Text -> String}, an entity name maps to its class. */
    public static String javaType(Ast.TypeRef type) {
        return switch (type.name()) {
            case "Int" -> "long";
            case "Text" -> "String";
            default -> type.name();
        };
    }

    /** A Java literal/expression for an example argument or expected value. */
    public static String javaValue(Ast.Expr expr) {
        return exprToJava(expr, Map.of());
    }

    /**
     * Translate a contract/expression to Java. {@code env} maps SkyLang names (parameters,
     * {@code result}) to the Java identifiers they are bound to in the generated test.
     */
    public static String exprToJava(Ast.Expr expr, Map<String, String> env) {
        return switch (expr) {
            case Ast.IntLit i -> i.value() + "L";
            case Ast.StrLit s -> javaString(s.value());
            case Ast.NameExpr n -> env.getOrDefault(n.name(), n.name());
            case Ast.MemberExpr m -> "(" + exprToJava(m.target(), env) + ")." + m.field() + "()";
            case Ast.CallExpr c -> "new " + c.callee() + "("
                    + c.args().stream().map(a -> exprToJava(a, env)).collect(Collectors.joining(", ")) + ")";
            case Ast.BinExpr b -> "(" + exprToJava(b.left(), env) + " " + javaOp(b.op()) + " "
                    + exprToJava(b.right(), env) + ")";
        };
    }

    private static String javaOp(String op) {
        return switch (op) {
            case "and" -> "&&";
            case "or" -> "||";
            default -> op;   // + - * / == != < <= > >= map straight across
        };
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
