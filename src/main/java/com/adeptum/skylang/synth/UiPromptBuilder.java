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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            "h:inputText", "h:commandButton", "h:graphicImage", "f:facet",
            "f:converter", "f:validateLongRange", "f:validateLength", "f:validateRegex");

    private static final String SYSTEM_BASE = """
            You are the UI-synthesis backend of the SkyLang compiler, targeting the
            jvm-jakarta / Jakarta Faces profile. Given a view's data source, columns, and
            actions, write a Facelets XHTML fragment and a @Named @ViewScoped backing bean.

            Rules:
            - Output EXACTLY two fenced blocks and nothing else: first ```xhtml then ```java.
            - Give every component a stable, explicit id.
            - Every input and command must carry an accessible label.
            - Render only the fields named by the projection and the actions — nothing else.
            - Never render, bind, or otherwise reach a Secret field; entities expose no getters for them.
            - Bind only to properties that exist on the backing bean.
            - Name the backing bean class EXACTLY <View>Bean (the view name given below plus "Bean"),
              so the public class matches its file. Annotate it @jakarta.inject.Named and
              @jakarta.faces.view.ViewScoped and implement java.io.Serializable — import ViewScoped
              from jakarta.faces.view (jakarta.enterprise.context has no ViewScoped).
            - The services under "Services" are injected CDI beans, never static. Declare each as an
              @jakarta.inject.Inject field and call its INSTANCE methods. Call ONLY the methods listed
              there, with exactly their given parameters — never invent a method.
            - Honour each method's return type: a Maybe<T> is a java.util.Optional<T> (a summary shows
              the single value it holds, if present); a [T] is a list of rows; a plain T is one value.
            - Use only the components listed under "Component vocabulary".
            - Each prompted input names its converter or validator under "Actions": nest exactly
              that tag inside the h:inputText (e.g. <f:converter converterId="sky.money"/> for a
              Money input, <f:converter converterId="sky.instant"/> for an Instant input,
              <f:converter converterId="sky.date"/> for a Date input).
            - To place a control in a region, wrap it in <h:panelGroup styleClass="REGION">.
            - To give a table a style (e.g. a density), set styleClass="STYLE" on the h:dataTable.
            - Render the table columns in the order requested under "Appearance".
            - A Bytes field flagged as an image renders as
              <h:graphicImage id="FIELDImage" value="#{bean.FIELDDataUri}" alt="FIELD"/>; the bean
              exposes String getFIELDDataUri() returning "data:image/png;base64," plus the
              Base64-encoded bytes, or "" when the value (or its record) is absent.
            - Each name under "Request params" is a bean property with BOTH a getter and a setter
              of the matching Java type (Boolean, Long, or String) — the page's view params set it.
            """;

    public String system(List<String> vocabulary) {
        return SYSTEM_BASE + "\nComponent vocabulary: " + String.join(", ", vocabulary) + "\n";
    }

    public String user(Ast.Module module, Ast.View view) {
        StringBuilder sb = new StringBuilder();
        Map<String, Ast.TypeDecl> types = Lowering.typesOf(module);

        sb.append("// Entities in scope (their Java record shapes):\n");
        for (Ast.Entity e : module.entities()) {
            String components = e.fields().stream()
                    .map(f -> Lowering.javaType(f.type(), types) + " " + f.name())
                    .collect(Collectors.joining(", "));
            sb.append("record ").append(e.name()).append("(").append(components).append(")\n");
        }

        sb.append("\n// View to render:\n");
        sb.append("view ").append(view.name());
        view.route().ifPresent(r -> sb.append(" at \"").append(r).append("\""));
        sb.append('\n');
        if (!view.params().isEmpty()) {
            sb.append("Request params: ").append(view.params().stream()
                    .map(p -> p.name() + " " + p.type().sky())
                    .collect(Collectors.joining(", "))).append('\n');
        }

        Ast.Shows shows = view.shows();
        String shape = shows.projection().map(Ast.Projection::kind)
                .filter("table"::equals).map(k -> "one row per item")
                .orElse("the single value it returns");
        sb.append("Data source: ").append(shows.query().service()).append('.')
                .append(shows.query().method()).append("() — bind ").append(shape).append(".\n");
        shows.projection().ifPresent(p ->
                sb.append("Columns: ").append(String.join(", ", p.columns())).append('\n'));
        for (String image : com.adeptum.skylang.verify.ViewVerifier.bytesColumns(module, view)) {
            sb.append("Column '").append(image).append("' is Bytes — render it with <h:graphicImage id=\"")
                    .append(image).append("Image\" value=\"#{bean.").append(image)
                    .append("DataUri}\"/> and expose String get")
                    .append(Character.toUpperCase(image.charAt(0))).append(image.substring(1))
                    .append("DataUri() on the bean (\"\" when absent).\n");
        }

        // The exact service methods this view calls: the bean must inject these and match these
        // signatures — never invent a method or guess a return type.
        List<String> signatures = new ArrayList<>();
        appendSignature(module, shows.query().service(), shows.query().method(), signatures);
        for (Ast.Action a : view.actions()) {
            if (a.pageTarget().isEmpty() && a.flowTarget().isEmpty() && a.signTarget().isEmpty()) {
                appendSignature(module, a.service(), a.method(), signatures);
            }
        }
        if (!signatures.isEmpty()) {
            sb.append("Services (inject these; call only these instance methods):\n");
            signatures.stream().distinct().forEach(s -> sb.append("  ").append(s).append('\n'));
        }

        if (!view.actions().isEmpty()) {
            sb.append("Actions:\n");
            for (Ast.Action a : view.actions()) {
                // Sign actions first: a "sign in then page X" also carries that page as its target.
                if (a.signTarget().isPresent()) {
                    boolean in = a.signTarget().get().equals("in");
                    String beanMethod = in ? "signIn" : "signOut";
                    String call = in
                            ? "skySecurity.signIn(\"" + a.pageTarget().orElse("") + "\")"
                            : "skySecurity.signOut()";
                    sb.append("  \"").append(a.label()).append("\" on the page -> sign ")
                            .append(in ? "in" : "out")
                            .append(" — render exactly <h:commandButton value=\"").append(a.label())
                            .append("\" action=\"#{bean.").append(beanMethod)
                            .append("}\"/> inside an h:form, and implement on the bean: public String ")
                            .append(beanMethod).append("() { return ").append(call)
                            .append("; } with an injected SkySecurity skySecurity field.\n");
                    continue;
                }
                if (a.pageTarget().isPresent()) {
                    sb.append("  \"").append(a.label())
                            .append("\" on the page -> navigate to page ").append(a.pageTarget().get())
                            .append(" — render exactly <h:button outcome=\"").append(a.pageTarget().get())
                            .append("\" value=\"").append(a.label())
                            .append("\"/>; no bean method backs this action.\n");
                    continue;
                }
                if (a.flowTarget().isPresent()) {
                    String entry = module.flows().stream()
                            .filter(f -> f.name().equals(a.flowTarget().get()))
                            .findFirst().flatMap(Ast.Flow::entryPage).orElse(a.flowTarget().get());
                    sb.append("  \"").append(a.label())
                            .append("\" on the page -> enter flow ").append(a.flowTarget().get())
                            .append(" at its first step — render exactly <h:button outcome=\"")
                            .append(entry).append("\" value=\"").append(a.label())
                            .append("\"/>; no bean method backs this action.\n");
                    continue;
                }
                String args = a.args().stream()
                        .map(arg -> renderArg(arg, types))
                        .collect(Collectors.joining(", "));
                sb.append("  \"").append(a.label())
                        .append(a.rowVar().isPresent() ? "\" on a row -> " : "\" on the page -> ")
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

    /** Append {@code Service.method(params) -> Return} for a called method, so the bean matches it. */
    private static void appendSignature(Ast.Module module, String service, String method, List<String> into) {
        module.services().stream()
                .filter(s -> s.name().equals(service))
                .flatMap(s -> s.methods().stream())
                .filter(m -> m.name().equals(method))
                .findFirst()
                .ifPresent(m -> {
                    String params = m.params().stream()
                            .map(p -> p.name() + " " + p.type().sky())
                            .collect(Collectors.joining(", "));
                    into.add(service + "." + method + "(" + params + ") -> " + m.returnType().sky());
                });
    }

    private static final String COMPONENT_SYSTEM = """
            You are the UI-synthesis backend of the SkyLang compiler, targeting Jakarta Faces.
            Write ONE Faces composite component as a single fenced ```xhtml block and nothing else.
            Rules:
            - Declare a cc:interface with one cc:attribute per parameter (name and type as given).
            - Render the declared content in cc:implementation, bound through #{cc.attrs.<param>...}.
            - Realise each state-dependent look as a conditional styleClass carrying the state's
              name (e.g. amber, brick) exactly, applied when its condition holds.
            - Give every element a stable id and keep the markup minimal.
            """;

    public String componentSystem() {
        return COMPONENT_SYSTEM;
    }

    public String componentUser(Ast.Module module, Ast.Component component) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Component to render:\n");
        sb.append("component ").append(component.name()).append('(');
        sb.append(component.params().stream()
                .map(p -> p.name() + " " + p.type().sky())
                .collect(Collectors.joining(", "))).append(")\n");
        sb.append("shows ").append(Lowering.skyText(component.shows().value()))
                .append(" as a ").append(component.shows().kind()).append('\n');
        for (Ast.ComponentAppears a : component.appears()) {
            sb.append("appears ").append(a.style()).append(" when ")
                    .append(Lowering.skyText(a.when())).append('\n');
        }
        for (String e : component.expects()) {
            sb.append("expect ").append(e).append('\n');
        }
        sb.append("\nWrite the composite component now.");
        return sb.toString();
    }

    private static final String FLOW_SYSTEM = """
            You are the navigation-synthesis backend of the SkyLang compiler.
            Realise the declared flow as ONE fenced ```json block and nothing else, of the shape:
            {"steps": ["Cart", "Shipping", "Pay"],
             "transitions": {"success": "page OrderConfirmed", "PaymentFailed": "step Pay"}}
            Rules:
            - steps lists every declared step, in the declared order.
            - transitions maps each declared trigger to its declared target.
            - Add nothing that was not declared.
            """;

    public String flowSystem() {
        return FLOW_SYSTEM;
    }

    public String flowUser(Ast.Flow flow) {
        StringBuilder sb = new StringBuilder("// Flow to realise:\nflow ").append(flow.name()).append('\n');
        for (Ast.FlowStep step : flow.steps()) {
            sb.append("step ").append(step.name()).append(" -> ").append(step.target()).append('\n');
        }
        for (Ast.FlowTransition t : flow.transitions()) {
            sb.append("on ").append(t.trigger()).append(" -> ").append(t.target()).append('\n');
        }
        for (String e : flow.expects()) {
            sb.append("expect ").append(e).append('\n');
        }
        sb.append("\nWrite the navigation graph now.");
        return sb.toString();
    }

    /** The single fenced block of the given language from a model reply. */
    public String extractFenced(String reply, String lang) {
        return fenced(reply, lang);
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

    private String renderArg(Ast.ActionArg arg, Map<String, Ast.TypeDecl> types) {
        return switch (arg) {
            case Ast.ExprArg ea -> exprText(ea.value());
            case Ast.AskArg ak -> {
                String hint = askHint(ak.type(), types);
                yield "ask " + ak.type().sky() + (hint.isEmpty() ? "" : " (input: " + hint + ")");
            }
        };
    }

    /** The converter or validator tag a prompted input of this type must carry. */
    private static String askHint(Ast.Type type, Map<String, Ast.TypeDecl> types) {
        return switch (type) {
            case Ast.RangedType r -> rangeHint(r.base(), r.lo(), r.hi());
            case Ast.TypeRef ref -> {
                if (ref.list()) {
                    yield "";
                }
                Ast.TypeDecl d = types.get(ref.name());
                if (d != null) {
                    yield switch (d.refinement()) {
                        case Ast.Range r -> rangeHint(d.base(), r.lo(), r.hi());
                        case Ast.Matching m -> "<f:validateRegex pattern=\"" + m.regex() + "\"/>";
                        case Ast.Where ignored -> "";
                    };
                }
                yield switch (ref.name()) {
                    case "Money" -> "<f:converter converterId=\"sky.money\"/>";
                    case "Instant" -> "<f:converter converterId=\"sky.instant\"/>";
                    case "Date" -> "<f:converter converterId=\"sky.date\"/>";
                    case "DateTime" -> "<f:converter converterId=\"sky.datetime\"/>";
                    case "Email" -> "<f:validateRegex pattern=\""
                            + com.adeptum.skylang.types.Builtins.EMAIL_REGEX + "\"/>";
                    case "Currency" -> "<f:validateRegex pattern=\""
                            + com.adeptum.skylang.types.Builtins.CURRENCY_REGEX + "\"/>";
                    case "Percentage" -> "<f:validateLongRange minimum=\"0\" maximum=\"100\"/>";
                    default -> "";
                };
            }
            case Ast.GenericType ignored -> "";
        };
    }

    private static String rangeHint(String base, java.util.OptionalLong lo, java.util.OptionalLong hi) {
        String tag = base.equals("Int") ? "f:validateLongRange" : "f:validateLength";
        StringBuilder sb = new StringBuilder("<").append(tag);
        lo.ifPresent(b -> sb.append(" minimum=\"").append(b).append('"'));
        hi.ifPresent(b -> sb.append(" maximum=\"").append(b).append('"'));
        return sb.append("/>").toString();
    }

    private String exprText(Ast.Expr e) {
        return switch (e) {
            case Ast.NameExpr n -> n.name();
            case Ast.MemberExpr m -> exprText(m.target()) + "." + m.field();
            case Ast.IntLit i -> Long.toString(i.value());
            case Ast.StrLit s -> "\"" + s.value() + "\"";
            case Ast.BoolLit b -> Boolean.toString(b.value());
            case Ast.MoneyLit m -> m.amount().toPlainString() + m.currency().toLowerCase(java.util.Locale.ROOT);
            case Ast.DurationLit d -> d.amount() + d.unit();
            case Ast.CallExpr c -> c.callee() + "(...)";
            case Ast.BinExpr b -> exprText(b.left()) + " " + b.op() + " " + exprText(b.right());
            case Ast.NotExpr n -> "not " + exprText(n.value());
            case Ast.OldExpr o -> "old(" + exprText(o.value()) + ")";
            case Ast.EmptyCheck ec -> exprText(ec.value()) + " is empty";
            case Ast.AggExpr a -> a.kind() + " of (...)";
        default -> "";   // view arguments never carry quantified or aggregate clauses
            };
    }

    private String renderExpect(Ast.Expect e) {
        return switch (e) {
            case Ast.ExpectColumns c -> "the " + c.subject() + " shows columns " + String.join(", ", c.columns());
            case Ast.ExpectActionKind a -> "the action \"" + a.label() + "\" is a " + a.kind();
            case Ast.ExpectProse p -> p.text();
        };
    }

    private String renderAppears(Ast.Appears a) {
        return switch (a) {
            case Ast.AppearsPlacement p -> "place the \"" + p.label()
                    + "\" control in a region with styleClass \"" + p.region() + "\"";
            case Ast.AppearsStyle s -> "give the " + s.subject() + " the styleClass \"" + s.value() + "\"";
            case Ast.AppearsColumnOrder co -> "order the columns as " + String.join(", ", co.columns());
            case Ast.AppearsActionState st -> "the \"" + st.label() + "\" control is " + st.state()
                    + st.when().map(w -> " when " + w).orElse("");
            case Ast.AppearsWhen w -> {
                String param = firstName(w.when());
                yield "render \"" + String.join(" ", w.subject()) + "\" inside <h:panelGroup styleClass=\""
                        + param + "\" rendered=\"#{bean." + Lowering.skyText(w.when())
                        + "}\"> so it appears only when " + Lowering.skyText(w.when());
            }
            case Ast.AppearsProse p -> p.text();
        };
    }

    /** The first bare name in a condition — the styleClass the conditional element must carry. */
    private static String firstName(Ast.Expr e) {
        return switch (e) {
            case Ast.NameExpr n -> n.name();
            case Ast.BinExpr b -> firstName(b.left());
            case Ast.NotExpr n -> firstName(n.value());
            default -> "conditional";
        };
    }
}
