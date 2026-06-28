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

package com.adeptum.skylang.synth;

import com.adeptum.skylang.backend.Lowering;
import com.adeptum.skylang.front.ast.Ast;

import java.util.stream.Collectors;

/**
 * Builds the model prompts for one method and extracts the Java body from the model's reply.
 * SkyLang-specific; the {@link Llm} itself stays generic.
 */
public final class PromptBuilder {

    private static final String SYSTEM = """
            You are the code-synthesis backend of the SkyLang compiler, targeting the JVM profile.
            Given a method's signature, intent, contracts, and examples, write the Java body.

            Rules:
            - Output ONLY the Java statements that go inside the method body — no signature, no class,
              no markdown fences, no commentary.
            - Assume Int lowers to long, Text to String, and each entity to a Java record whose
              components are its fields in declaration order (accessed via component methods, e.g. p.stock()).
            - Records are immutable: to "change" a field, construct a new record value.
            - The body must satisfy every `requires`, `ensures`, and `example`.
            """;

    public String system() {
        return SYSTEM;
    }

    public String user(Ast.Module module, Ast.Service service, Ast.Method method) {
        StringBuilder sb = new StringBuilder();

        sb.append("// Entities in scope (their Java record shapes):\n");
        for (Ast.Entity e : module.entities()) {
            String components = e.fields().stream()
                    .map(f -> Lowering.javaType(f.type()) + " " + f.name())
                    .collect(Collectors.joining(", "));
            sb.append("record ").append(e.name()).append("(").append(components).append(")\n");
        }

        sb.append("\n// Method to implement (Java signature):\n");
        String params = method.params().stream()
                .map(p -> Lowering.javaType(p.type()) + " " + p.name())
                .collect(Collectors.joining(", "));
        sb.append("public ").append(Lowering.javaType(method.returnType())).append(' ')
                .append(method.name()).append('(').append(params).append(")\n\n");

        method.intent().ifPresent(intent -> sb.append("Intent: ").append(intent).append("\n\n"));

        if (!method.requires().isEmpty()) {
            sb.append("Preconditions (assume they hold):\n");
            method.requires().forEach(r -> sb.append("  requires ").append(sky(r)).append('\n'));
            sb.append('\n');
        }
        if (!method.ensures().isEmpty()) {
            sb.append("Postconditions (must hold for the result):\n");
            method.ensures().forEach(e -> sb.append("  ensures ").append(sky(e)).append('\n'));
            sb.append('\n');
        }
        if (!method.examples().isEmpty()) {
            sb.append("Examples (must pass):\n");
            for (Ast.Example ex : method.examples()) {
                sb.append("  ").append(sky(ex.call())).append(" -> ").append(renderResult(ex.result())).append('\n');
            }
            sb.append('\n');
        }

        sb.append("Write the Java method body now.");
        return sb.toString();
    }

    /** Extract the Java body from the model reply, tolerating an accidental ```java fence. */
    public String extractBody(String reply) {
        String text = reply.strip();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 3);
            if (start >= 0 && end > start) {
                return text.substring(start + 1, end).strip();
            }
        }
        return text;
    }

    private String renderResult(Ast.Result result) {
        return switch (result) {
            case Ast.ExprResult er -> sky(er.value());
            case Ast.EntityResult ent -> {
                String fields = ent.fields().stream()
                        .map(fe -> fe.field() + " " + sky(fe.expected()))
                        .collect(Collectors.joining(" and "));
                yield "a " + ent.typeName() + (fields.isEmpty() ? "" : " with " + fields);
            }
        };
    }

    private String sky(Ast.Expr expr) {
        return switch (expr) {
            case Ast.IntLit i -> Long.toString(i.value());
            case Ast.StrLit s -> "\"" + s.value() + "\"";
            case Ast.NameExpr n -> n.name();
            case Ast.MemberExpr m -> sky(m.target()) + "." + m.field();
            case Ast.CallExpr c -> c.callee() + "("
                    + c.args().stream().map(this::sky).collect(Collectors.joining(", ")) + ")";
            case Ast.BinExpr b -> sky(b.left()) + " " + b.op() + " " + sky(b.right());
        };
    }
}
