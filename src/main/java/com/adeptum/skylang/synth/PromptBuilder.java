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
            - Type lowering: Int -> long, Text -> String, Bool -> boolean, Email -> String,
              Instant -> java.time.Instant, Maybe<T> -> java.util.Optional<T>,
              List/Set/Map -> java.util.List/Set/Map, and each entity to a Java record whose
              components are its fields in declaration order (accessed via component methods, e.g. p.stock()).
            - Money is a class in the same package: Money.of("9.99", "EUR"), a.plus(b), a.minus(b),
              a.times(3L), a.amount(), a.currency(), and it is Comparable. Currencies must match;
              never multiply Money by Money and never convert Money to a floating-point number.
            - Bytes is a class in the same package: Bytes.of(byte[]), Bytes.ofUtf8("..."),
              b.toByteArray(), b.size().
            - Secret<T> wraps a value that must never be logged, printed, rendered, or serialized;
              its toString() is masked. Call reveal() only when the contract needs the raw value,
              and never pass the revealed value anywhere it could be recorded.
            - Declared refined types erase to their base type in Java; their predicates are already
              enforced by guards before the body runs, so assume every parameter satisfies its type.
            - The service declares an effects budget; the body may reach outside pure computation
              ONLY through the effect handles listed under "Effects available". Never use raw
              alternatives: no java.net or java.net.http, no JDBC, no file IO, no mail APIs, and
              no Instant.now(), System.currentTimeMillis(), or new java.util.Date() — the current
              time, when budgeted, is clock.instant().
            - An enum-like entity's instance set is closed: use its listed constants (Role.Member),
              never its constructor.
            - Records are immutable: to "change" a field, construct a new record value.
            - The body must satisfy every `requires`, `ensures`, and `example`.
            """;

    public String system() {
        return SYSTEM;
    }

    public String user(Ast.Module module, Ast.Service service, Ast.Method method) {
        StringBuilder sb = new StringBuilder();
        var types = Lowering.typesOf(module);

        if (!module.types().isEmpty()) {
            sb.append("// Declared refined types (predicates enforced at construction; erased in Java):\n");
            for (Ast.TypeDecl d : module.types()) {
                sb.append(skyTypeDecl(d)).append('\n');
            }
            sb.append('\n');
        }

        sb.append("// Entities in scope (their Java record shapes):\n");
        for (Ast.Entity e : module.entities()) {
            String components = e.fields().stream()
                    .map(f -> Lowering.javaType(f.type(), types) + " " + f.name())
                    .collect(Collectors.joining(", "));
            sb.append("record ").append(e.name()).append("(").append(components).append(")");
            if (!e.values().isEmpty()) {
                sb.append("  // closed set, constants: ").append(e.values().stream()
                        .map(v -> e.name() + "." + v).collect(Collectors.joining(", ")));
            }
            sb.append('\n');
        }

        sb.append('\n').append(effectsAvailable(module, service));

        sb.append("\n// Method to implement (Java signature):\n");
        String params = method.params().stream()
                .map(p -> Lowering.javaType(p.type(), types) + " " + p.name())
                .collect(Collectors.joining(", "));
        sb.append("public ").append(Lowering.javaType(method.returnType(), types)).append(' ')
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

    /** The declared budget as concrete handles, or the statement that the body is pure. */
    private String effectsAvailable(Ast.Module module, Ast.Service service) {
        if (service.uses().isEmpty()) {
            return "// Effects available: none — this service has no effects; the body is pure computation.\n";
        }
        StringBuilder sb = new StringBuilder("// Effects available (the fields below are the ONLY way out):\n");
        for (String effect : service.uses()) {
            switch (effect) {
                case "db" -> {
                    sb.append("//   db — the store:");
                    for (Ast.Entity e : module.entities()) {
                        boolean root = e.values().isEmpty() && e.fields().stream().anyMatch(Ast.Field::id);
                        if (root) {
                            String idType = Lowering.javaType(
                                    e.fields().stream().filter(Ast.Field::id).findFirst().orElseThrow().type(),
                                    Lowering.typesOf(module));
                            sb.append(" db.save(").append(e.name()).append("), db.find").append(e.name())
                                    .append('(').append(idType).append("), db.all").append(e.name())
                                    .append("s(), db.delete").append(e.name()).append('(').append(idType)
                                    .append(");");
                        }
                    }
                    sb.append(" save a referenced entity before the entity that references it.\n");
                }
                case "clock" -> sb.append("//   clock — java.time.Clock; the current time is clock.instant().\n");
                case "mail" -> sb.append("//   mail — mail.send(to, subject, body).\n");
                case "http" -> sb.append("//   http — http.get(url) returning the response body.\n");
                default -> {
                }
            }
        }
        return sb.toString();
    }

    private String skyTypeDecl(Ast.TypeDecl d) {
        String refinement = switch (d.refinement()) {
            case Ast.Range r -> d.base() + "(" + (r.lo().isPresent() ? r.lo().getAsLong() : "")
                    + ".." + (r.hi().isPresent() ? r.hi().getAsLong() : "") + ")";
            case Ast.Matching m -> d.base() + " matching /" + m.regex() + "/";
            case Ast.Where w -> d.base() + " where " + sky(w.predicate());
        };
        return "type " + d.name() + " = " + refinement;
    }

    private String sky(Ast.Expr expr) {
        return switch (expr) {
            case Ast.IntLit i -> Long.toString(i.value());
            case Ast.StrLit s -> "\"" + s.value() + "\"";
            case Ast.BoolLit b -> Boolean.toString(b.value());
            case Ast.MoneyLit m -> m.amount().toPlainString() + m.currency().toLowerCase(java.util.Locale.ROOT);
            case Ast.NameExpr n -> n.name();
            case Ast.MemberExpr m -> sky(m.target()) + "." + m.field();
            case Ast.CallExpr c -> c.callee() + "("
                    + c.args().stream().map(this::sky).collect(Collectors.joining(", ")) + ")";
            case Ast.BinExpr b -> sky(b.left()) + " " + b.op() + " " + sky(b.right());
        };
    }
}
