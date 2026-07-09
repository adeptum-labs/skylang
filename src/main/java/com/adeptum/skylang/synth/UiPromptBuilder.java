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

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the model prompts for one view and extracts the two synthesized artifacts — a Facelets
 * fragment and its backing bean — from the model's reply. The soft layer of a view: the compiler
 * later verifies the result against the view's structural expectations and stories.
 */
public final class UiPromptBuilder {

    /** The two synthesized artifacts for a view: its Facelets markup and its backing bean. */
    public record UiArtifact(String markup, String bean) {
    }

    /** The always-available standard-component vocabulary; profiles may widen it (PrimeFaces, OmniFaces). */
    public static final List<String> STANDARD = List.of(
            "h:form", "h:dataTable", "h:column", "h:outputText", "h:outputLabel",
            "h:inputText", "h:commandButton", "f:facet");

    private static final String SYSTEM_BASE = """
            You are the UI-synthesis backend of the SkyLang compiler, targeting the
            jvm-jakarta / Jakarta Faces profile. Given a view's data source, columns, and
            actions, write a Facelets XHTML fragment and a @Named @ViewScoped backing bean.

            Rules:
            - Output EXACTLY two fenced blocks and nothing else: first ```xhtml then ```java.
            - Give every component a stable, explicit id.
            - Every input and command must carry an accessible label.
            - Render only the fields named by the projection and the actions — nothing else.
            - Bind only to properties that exist on the backing bean.
            - Use only the components listed under "Component vocabulary".
            - To place a control in a region, wrap it in <h:panelGroup styleClass="REGION">.
            - To give a table a style (e.g. a density), set styleClass="STYLE" on the h:dataTable.
            - Render the table columns in the order requested under "Appearance".
            """;

    public String system(List<String> vocabulary) {
        return SYSTEM_BASE + "\nComponent vocabulary: " + String.join(", ", vocabulary) + "\n";
    }

    public String user(Ast.Module module, Ast.View view) {
        StringBuilder sb = new StringBuilder();

        sb.append("// Entities in scope (their Java record shapes):\n");
        for (Ast.Entity e : module.entities()) {
            String components = e.fields().stream()
                    .map(f -> Lowering.javaType(f.type()) + " " + f.name())
                    .collect(Collectors.joining(", "));
            sb.append("record ").append(e.name()).append("(").append(components).append(")\n");
        }

        sb.append("\n// View to render:\n");
        sb.append("view ").append(view.name());
        view.route().ifPresent(r -> sb.append(" at \"").append(r).append("\""));
        sb.append('\n');

        Ast.Shows shows = view.shows();
        sb.append("Data source: ").append(shows.query().service()).append('.')
                .append(shows.query().method()).append("() — one row per item.\n");
        shows.projection().ifPresent(p ->
                sb.append("Columns: ").append(String.join(", ", p.columns())).append('\n'));

        if (!view.actions().isEmpty()) {
            sb.append("Actions:\n");
            for (Ast.Action a : view.actions()) {
                String args = a.args().stream().map(this::renderArg).collect(Collectors.joining(", "));
                sb.append("  \"").append(a.label()).append("\" on a row -> ")
                        .append(a.service()).append('.').append(a.method())
                        .append('(').append(args).append(")\n");
            }
        }

        if (!view.expects().isEmpty()) {
            sb.append("Must satisfy:\n");
            for (Ast.Expect e : view.expects()) {
                sb.append("  ").append(renderExpect(e)).append('\n');
            }
        }

        if (!view.appears().isEmpty()) {
            sb.append("Appearance:\n");
            for (Ast.Appears a : view.appears()) {
                sb.append("  ").append(renderAppears(a)).append('\n');
            }
        }

        sb.append("\nWrite the Facelets fragment and the backing bean now.");
        return sb.toString();
    }

    /** Split the model reply into its {@code ```xhtml} and {@code ```java} fenced blocks. */
    public UiArtifact extractArtifacts(String reply) {
        return new UiArtifact(fenced(reply, "xhtml"), fenced(reply, "java"));
    }

    private static String fenced(String text, String lang) {
        int open = text.indexOf("```" + lang);
        if (open < 0) {
            throw new IllegalArgumentException("model reply is missing a ```" + lang + " block");
        }
        int bodyStart = text.indexOf('\n', open);
        int end = bodyStart < 0 ? -1 : text.indexOf("```", bodyStart + 1);
        if (bodyStart < 0 || end < 0) {
            throw new IllegalArgumentException("unterminated ```" + lang + " block in model reply");
        }
        return text.substring(bodyStart + 1, end).strip();
    }

    private String renderArg(Ast.ActionArg arg) {
        return switch (arg) {
            case Ast.ExprArg ea -> exprText(ea.value());
            case Ast.AskArg ak -> "ask " + ak.type().name();
        };
    }

    private String exprText(Ast.Expr e) {
        return switch (e) {
            case Ast.NameExpr n -> n.name();
            case Ast.MemberExpr m -> exprText(m.target()) + "." + m.field();
            case Ast.IntLit i -> Long.toString(i.value());
            case Ast.StrLit s -> "\"" + s.value() + "\"";
            case Ast.CallExpr c -> c.callee() + "(...)";
            case Ast.BinExpr b -> exprText(b.left()) + " " + b.op() + " " + exprText(b.right());
        };
    }

    private String renderExpect(Ast.Expect e) {
        return switch (e) {
            case Ast.ExpectColumns c -> "the " + c.subject() + " shows columns " + String.join(", ", c.columns());
            case Ast.ExpectActionKind a -> "the action \"" + a.label() + "\" is a " + a.kind();
        };
    }

    private String renderAppears(Ast.Appears a) {
        return switch (a) {
            case Ast.AppearsPlacement p -> "place the \"" + p.label()
                    + "\" control in a region with styleClass \"" + p.region() + "\"";
            case Ast.AppearsStyle s -> "give the " + s.subject() + " the styleClass \"" + s.value() + "\"";
            case Ast.AppearsColumnOrder co -> "order the columns as " + String.join(", ", co.columns());
        };
    }
}
