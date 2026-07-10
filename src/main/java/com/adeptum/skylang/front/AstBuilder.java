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

package com.adeptum.skylang.front;

import com.adeptum.skylang.front.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Walks the ANTLR parse tree into the immutable {@link Ast}. Kept as explicit recursive
 * methods (rather than a generic visitor) so the mapping from grammar rule to AST node is
 * readable in one place.
 */
public final class AstBuilder {

    public Ast.Module build(SkyLangParser.Module_Context ctx) {
        List<Ast.TypeDecl> types = new ArrayList<>();
        List<Ast.Policy> policies = new ArrayList<>();
        List<Ast.Entity> entities = new ArrayList<>();
        List<Ast.Service> services = new ArrayList<>();
        List<Ast.View> views = new ArrayList<>();
        for (SkyLangParser.DeclContext decl : ctx.decl()) {
            if (decl.entity() != null) {
                entities.add(entity(decl.entity()));
            } else if (decl.service() != null) {
                services.add(service(decl.service()));
            } else if (decl.typeDecl() != null) {
                types.add(typeDecl(decl.typeDecl()));
            } else if (decl.policy() != null) {
                policies.add(policy(decl.policy()));
            } else {
                views.add(view(decl.view()));
            }
        }
        return new Ast.Module(ctx.ID().getText(), types, policies, entities, services, views);
    }

    // ----- policies --------------------------------------------------------------

    private Ast.Policy policy(SkyLangParser.PolicyContext ctx) {
        return new Ast.Policy(ctx.ID().getText(), whenever(ctx.wheneverPhrase()), policyRule(ctx.policyRule()));
    }

    private Ast.Whenever whenever(SkyLangParser.WheneverPhraseContext ctx) {
        String article = ctx.ID(0).getText();
        String type = ctx.ID(1).getText();
        String verb = ctx.ID(2).getText();
        if (!article.equals("a") && !article.equals("an")) {
            throw new IllegalArgumentException(badWhenever());
        }
        if (verb.equals("constructed") && ctx.ID().size() == 3) {
            return new Ast.Constructed(type);
        }
        if (verb.equals("passed") && ctx.ID().size() == 6
                && ctx.ID(3).getText().equals("to")
                && (ctx.ID(4).getText().equals("a") || ctx.ID(4).getText().equals("an"))
                && ctx.ID(5).getText().equals("logger")) {
            return new Ast.PassedToLogger(type);
        }
        throw new IllegalArgumentException(badWhenever());
    }

    private static String badWhenever() {
        return "unrecognized whenever phrase; say 'whenever a Password is constructed'"
                + " or 'whenever a Secret is passed to a logger'";
    }

    private Ast.PolicyRule policyRule(SkyLangParser.PolicyRuleContext ctx) {
        if (ctx instanceof SkyLangParser.ForbidRuleContext) {
            return new Ast.ForbidRule();
        }
        SkyLangParser.RequireRuleContext rr = (SkyLangParser.RequireRuleContext) ctx;
        List<Ast.ReqTerm> terms = new ArrayList<>();
        for (SkyLangParser.RequireTermContext t : rr.requireTerm()) {
            terms.add(switch (t) {
                case SkyLangParser.ContainsTermContext c -> {
                    if (!c.ID(0).getText().equals("a") && !c.ID(0).getText().equals("an")) {
                        throw new IllegalArgumentException("say e.g. 'contains a symbol'");
                    }
                    yield new Ast.Contains(c.ID(1).getText());
                }
                case SkyLangParser.ExprTermContext e -> new Ast.TermExpr(expr(e.expr()));
                default -> throw new IllegalStateException("unhandled require term");
            });
        }
        Optional<String> raise = rr.RAISE() == null ? Optional.empty() : Optional.of(rr.ID().getText());
        return new Ast.RequireRule(terms, raise);
    }

    // ----- named refined types -------------------------------------------------

    private Ast.TypeDecl typeDecl(SkyLangParser.TypeDeclContext ctx) {
        String name = ctx.ID().getText();
        return switch (ctx.refinedType()) {
            case SkyLangParser.RangeRefinementContext r ->
                    new Ast.TypeDecl(name, r.ID().getText(), range(r.range()));
            case SkyLangParser.RegexRefinementContext r ->
                    new Ast.TypeDecl(name, r.ID().getText(), new Ast.Matching(regex(r.REGEX().getText())));
            case SkyLangParser.WhereRefinementContext r ->
                    new Ast.TypeDecl(name, r.ID().getText(), new Ast.Where(expr(r.expr())));
            default -> throw new IllegalStateException("unhandled refinement: " + ctx.refinedType().getClass());
        };
    }

    private static Ast.Range range(SkyLangParser.RangeContext ctx) {
        OptionalLong lo = ctx.lo == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(ctx.lo.getText()));
        OptionalLong hi = ctx.hi == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(ctx.hi.getText()));
        return new Ast.Range(lo, hi);
    }

    /** Strip the slash delimiters from a REGEX token and resolve escaped slashes. */
    private static String regex(String raw) {
        return raw.substring(1, raw.length() - 1).replace("\\/", "/");
    }

    // ----- entities ----------------------------------------------------------

    private Ast.Entity entity(SkyLangParser.EntityContext ctx) {
        List<Ast.Field> fields = new ArrayList<>();
        for (SkyLangParser.FieldContext f : ctx.field()) {
            fields.add(field(f));
        }
        List<String> values = new ArrayList<>();
        if (ctx.valuesClause() != null) {
            for (var v : ctx.valuesClause().ID()) {
                values.add(v.getText());
            }
        }
        return new Ast.Entity(ctx.ID().getText(), fields, values);
    }

    private Ast.Field field(SkyLangParser.FieldContext ctx) {
        boolean id = false;
        boolean unique = false;
        OptionalLong min = OptionalLong.empty();
        for (SkyLangParser.AnnotationContext a : ctx.annotation()) {
            String name = a.ID().getText();
            switch (name) {
                case "id" -> id = true;
                case "unique" -> unique = true;
                case "min" -> {
                    if (a.INT() == null) {
                        throw new IllegalArgumentException("@min requires an integer argument, e.g. @min(0)");
                    }
                    min = OptionalLong.of(Long.parseLong(a.INT().getText()));
                }
                default -> throw new IllegalArgumentException("unknown annotation @" + name);
            }
        }
        Optional<Ast.Expr> defaultValue = ctx.expr() == null ? Optional.empty() : Optional.of(expr(ctx.expr()));
        return new Ast.Field(ctx.ID().getText(), type(ctx.type()), id, min, unique, defaultValue);
    }

    // ----- services & methods ------------------------------------------------

    private Ast.Service service(SkyLangParser.ServiceContext ctx) {
        List<Ast.Method> methods = new ArrayList<>();
        for (SkyLangParser.MethodContext m : ctx.method()) {
            methods.add(method(m));
        }
        // ID(0) is the service name; any further IDs are the declared effects.
        List<String> uses = new ArrayList<>();
        for (int i = 1; i < ctx.ID().size(); i++) {
            uses.add(ctx.ID(i).getText());
        }
        return new Ast.Service(ctx.ID(0).getText(), methods, uses);
    }

    private Ast.Method method(SkyLangParser.MethodContext ctx) {
        List<Ast.Param> params = new ArrayList<>();
        if (ctx.params() != null) {
            for (SkyLangParser.ParamContext p : ctx.params().param()) {
                params.add(new Ast.Param(p.ID().getText(), type(p.type())));
            }
        }

        Optional<String> intent = Optional.empty();
        List<Ast.Expr> requires = new ArrayList<>();
        List<Ast.Expr> ensures = new ArrayList<>();
        List<Ast.Example> examples = new ArrayList<>();
        List<Ast.Raise> raises = new ArrayList<>();
        List<Ast.Spec> specs = new ArrayList<>();
        Optional<String> nativeBody = Optional.empty();
        String nativeKeyword = "java";

        for (SkyLangParser.ClauseContext c : ctx.clause()) {
            if (c instanceof SkyLangParser.IntentClauseContext ic) {
                intent = Optional.of(unquote(ic.STRING().getText()));
            } else if (c instanceof SkyLangParser.RequiresClauseContext rc) {
                requires.add(expr(rc.expr()));
            } else if (c instanceof SkyLangParser.EnsuresClauseContext ec) {
                ensures.add(expr(ec.expr()));
            } else if (c instanceof SkyLangParser.ExampleClauseContext xc) {
                examples.add(example(xc));
            } else if (c instanceof SkyLangParser.RaisesClauseContext rz) {
                raises.add(new Ast.Raise(rz.ID().getText(), raisesCondition(rz.raisesCondition())));
            } else if (c instanceof SkyLangParser.SpecClauseContext sc) {
                specs.add(spec(sc));
            } else if (c instanceof SkyLangParser.NativeClauseContext nc) {
                String raw = nc.NATIVE_BLOCK().getText();
                nativeKeyword = raw.substring(0, raw.indexOf('{')).strip();
                nativeBody = Optional.of(raw.substring(raw.indexOf('{') + 1, raw.lastIndexOf('}')).strip());
            }
        }

        return new Ast.Method(ctx.ID().getText(), params, type(ctx.type()),
                intent, requires, ensures, examples, raises, specs, nativeBody, nativeKeyword);
    }

    private Ast.Spec spec(SkyLangParser.SpecClauseContext ctx) {
        Optional<Ast.Expr> given = ctx.GIVEN() == null
                ? Optional.empty()
                : Optional.of(expr(ctx.expr()));
        List<Ast.ThenAssert> then = new ArrayList<>();
        for (SkyLangParser.ThenAssertContext t : ctx.thenAssert()) {
            then.add(switch (t) {
                case SkyLangParser.ThenRaisesContext r -> new Ast.ThenRaises(r.ID().getText());
                case SkyLangParser.ThenExprContext e -> new Ast.ThenExpr(expr(e.expr()));
                default -> throw new IllegalStateException("unhandled then assertion");
            });
        }
        return new Ast.Spec(unquote(ctx.STRING().getText()), given, call(ctx.call()), then);
    }

    /** The when-vocabulary: fixed phrases with soft keywords, or a formal condition. */
    private Ast.RaiseCondition raisesCondition(SkyLangParser.RaisesConditionContext ctx) {
        return switch (ctx) {
            case SkyLangParser.ExistenceConditionContext e -> {
                if (!e.ID(0).getText().equals("no") || !e.ID(2).getText().equals("that")) {
                    throw new IllegalArgumentException("unrecognized raises condition; say e.g."
                            + " 'when no product has that id'");
                }
                yield new Ast.NoSuch(e.ID(1).getText(), e.ID(3).getText());
            }
            case SkyLangParser.PhraseConditionContext p -> {
                if (!p.ID(0).getText().equals("already") || !p.ID(1).getText().equals("registered")) {
                    throw new IllegalArgumentException("unrecognized raises condition; say e.g."
                            + " 'when email already registered'");
                }
                yield new Ast.AlreadyRegistered(expr(p.expr()));
            }
            case SkyLangParser.ExprConditionContext c -> new Ast.CondExpr(expr(c.expr()));
            default -> throw new IllegalStateException("unhandled raises condition: " + ctx.getClass());
        };
    }

    private Ast.Type type(SkyLangParser.TypeContext ctx) {
        return switch (ctx) {
            case SkyLangParser.NamedTypeContext named -> new Ast.TypeRef(named.ID().getText());
            case SkyLangParser.RangedTypeContext ranged -> {
                Ast.Range r = range(ranged.range());
                yield new Ast.RangedType(ranged.ID().getText(), r.lo(), r.hi());
            }
            case SkyLangParser.GenericTypeContext generic -> {
                List<Ast.Type> args = new ArrayList<>();
                for (SkyLangParser.TypeContext a : generic.type()) {
                    args.add(type(a));
                }
                yield new Ast.GenericType(generic.ID().getText(), args);
            }
            // [E] stays a legacy list TypeRef (its string form feeds frozen spec hashes);
            // a complex element type generalizes to List<T>.
            case SkyLangParser.ListTypeContext list ->
                    list.type() instanceof SkyLangParser.NamedTypeContext named
                            ? new Ast.TypeRef(named.ID().getText(), true)
                            : new Ast.GenericType("List", List.of(type(list.type())));
            default -> throw new IllegalStateException("unhandled type node: " + ctx.getClass().getSimpleName());
        };
    }

    // ----- views -------------------------------------------------------------

    private Ast.View view(SkyLangParser.ViewContext ctx) {
        Optional<String> route = ctx.route() == null
                ? Optional.empty()
                : Optional.of(unquote(ctx.route().STRING().getText()));

        Ast.Shows shows = null;
        List<Ast.Action> actions = new ArrayList<>();
        List<Ast.Expect> expects = new ArrayList<>();
        List<Ast.Appears> appears = new ArrayList<>();

        for (SkyLangParser.ViewClauseContext c : ctx.viewClause()) {
            if (c instanceof SkyLangParser.ShowsClauseContext sc) {
                shows = shows(sc);
            } else if (c instanceof SkyLangParser.ActionClauseContext ac) {
                actions.add(action(ac));
            } else if (c instanceof SkyLangParser.ExpectClauseContext ec) {
                expects.add(expect(ec.expectPred()));
            } else if (c instanceof SkyLangParser.AppearsClauseContext apc) {
                appears.add(appears(apc.appearsPred()));
            }
        }
        return new Ast.View(ctx.ID().getText(), route, shows, actions, expects, appears);
    }

    private Ast.Shows shows(SkyLangParser.ShowsClauseContext ctx) {
        SkyLangParser.ViewQueryContext q = ctx.viewQuery();
        Ast.QualifiedCall query = new Ast.QualifiedCall(q.ID(0).getText(), q.ID(1).getText(), args(q.args()));
        Optional<Ast.Projection> projection = ctx.projection() == null
                ? Optional.empty()
                : Optional.of(projection(ctx.projection()));
        return new Ast.Shows(query, projection);
    }

    private Ast.Projection projection(SkyLangParser.ProjectionContext ctx) {
        // ID(0) is the article ("a"); ID(1) is the kind ("table"/"form"); the rest are the columns.
        String kind = ctx.ID(1).getText();
        List<String> columns = new ArrayList<>();
        for (int i = 2; i < ctx.ID().size(); i++) {
            columns.add(ctx.ID(i).getText());
        }
        return new Ast.Projection(kind, columns);
    }

    private Ast.Action action(SkyLangParser.ActionClauseContext ctx) {
        SkyLangParser.ActionTargetContext t = ctx.actionTarget();
        List<Ast.ActionArg> args = new ArrayList<>();
        for (SkyLangParser.ActionArgContext a : t.actionArg()) {
            args.add(actionArg(a));
        }
        return new Ast.Action(unquote(ctx.STRING().getText()), ctx.ID().getText(),
                t.ID(0).getText(), t.ID(1).getText(), args);
    }

    private Ast.ActionArg actionArg(SkyLangParser.ActionArgContext ctx) {
        if (ctx.ASK() != null) {
            return new Ast.AskArg(type(ctx.type()));
        }
        return new Ast.ExprArg(expr(ctx.expr()));
    }

    private Ast.Expect expect(SkyLangParser.ExpectPredContext ctx) {
        if (ctx instanceof SkyLangParser.ExpectColumnsContext ec) {
            String subject = ec.ID(0).getText();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i < ec.ID().size(); i++) {
                columns.add(ec.ID(i).getText());
            }
            return new Ast.ExpectColumns(subject, columns);
        }
        SkyLangParser.ExpectActionKindContext ak = (SkyLangParser.ExpectActionKindContext) ctx;
        return new Ast.ExpectActionKind(unquote(ak.STRING().getText()), ak.ID().getText());
    }

    private Ast.Appears appears(SkyLangParser.AppearsPredContext ctx) {
        if (ctx instanceof SkyLangParser.AppearsPlacementContext p) {
            return new Ast.AppearsPlacement(unquote(p.STRING().getText()), p.ID().getText());
        }
        if (ctx instanceof SkyLangParser.AppearsStyleContext s) {
            return new Ast.AppearsStyle(s.ID(0).getText(), s.ID(1).getText());
        }
        SkyLangParser.AppearsColumnOrderContext co = (SkyLangParser.AppearsColumnOrderContext) ctx;
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < co.ID().size(); i++) {
            columns.add(co.ID(i).getText());
        }
        return new Ast.AppearsColumnOrder(columns);
    }

    // ----- examples ----------------------------------------------------------

    private Ast.Example example(SkyLangParser.ExampleClauseContext ctx) {
        Ast.CallExpr call = call(ctx.call());
        Optional<Ast.Seed> seed = ctx.seed() == null ? Optional.empty() : Optional.of(seed(ctx.seed()));
        return new Ast.Example(call, result(ctx.exampleResult()), seed);
    }

    private Ast.Seed seed(SkyLangParser.SeedContext ctx) {
        String article = ctx.ID(0).getText();
        if (!article.equals("a") && !article.equals("an")) {
            throw new IllegalArgumentException("a seed reads 'on a <Entity> with <field> <value>'");
        }
        return new Ast.Seed(ctx.ID(1).getText(), fieldExpects(ctx.withClause()));
    }

    private List<Ast.FieldExpect> fieldExpects(SkyLangParser.WithClauseContext ctx) {
        List<Ast.FieldExpect> fields = new ArrayList<>();
        if (ctx != null) {
            for (SkyLangParser.FieldExpectContext fe : ctx.fieldExpect()) {
                fields.add(new Ast.FieldExpect(fe.ID().getText(), expr(fe.expr())));
            }
        }
        return fields;
    }

    private Ast.Result result(SkyLangParser.ExampleResultContext ctx) {
        return switch (ctx) {
            case SkyLangParser.ExprResultContext er -> new Ast.ExprResult(expr(er.expr()));
            case SkyLangParser.RaisesResultContext rr -> new Ast.RaisesResult(rr.ID().getText());
            case SkyLangParser.FieldsResultContext fr -> {
                List<Ast.FieldExpect> fields = new ArrayList<>();
                for (SkyLangParser.FieldExpectContext fe : fr.fieldExpect()) {
                    fields.add(new Ast.FieldExpect(fe.ID().getText(), expr(fe.expr())));
                }
                yield new Ast.FieldsResult(fields);
            }
            case SkyLangParser.EntityResultContext en ->
                    new Ast.EntityResult(en.ID(1).getText(), fieldExpects(en.withClause()));
            default -> throw new IllegalStateException("unhandled example result: " + ctx.getClass());
        };
    }

    private Ast.CallExpr call(SkyLangParser.CallContext ctx) {
        return new Ast.CallExpr(ctx.ID().getText(), args(ctx.args()));
    }

    private List<Ast.Expr> args(SkyLangParser.ArgsContext ctx) {
        List<Ast.Expr> out = new ArrayList<>();
        if (ctx != null) {
            for (SkyLangParser.ExprContext e : ctx.expr()) {
                out.add(expr(e));
            }
        }
        return out;
    }

    // ----- expressions -------------------------------------------------------

    private Ast.Expr expr(SkyLangParser.ExprContext ctx) {
        return switch (ctx) {
            case SkyLangParser.ParenExprContext c -> expr(c.expr());
            case SkyLangParser.MemberExprContext c -> new Ast.MemberExpr(expr(c.expr()), c.ID().getText());
            case SkyLangParser.CallExprContext c -> new Ast.CallExpr(c.ID().getText(), args(c.args()));
            case SkyLangParser.MulExprContext c -> new Ast.BinExpr(c.op.getText(), expr(c.expr(0)), expr(c.expr(1)));
            case SkyLangParser.AddExprContext c -> new Ast.BinExpr(c.op.getText(), expr(c.expr(0)), expr(c.expr(1)));
            case SkyLangParser.CmpExprContext c -> new Ast.BinExpr(c.op.getText(), expr(c.expr(0)), expr(c.expr(1)));
            case SkyLangParser.LogicExprContext c -> new Ast.BinExpr(c.op.getText(), expr(c.expr(0)), expr(c.expr(1)));
            case SkyLangParser.IntLitContext c -> new Ast.IntLit(Long.parseLong(c.INT().getText()));
            case SkyLangParser.StrLitContext c -> new Ast.StrLit(unquote(c.STRING().getText()));
            case SkyLangParser.MoneyLitContext c -> moneyLit(c.MONEY().getText());
            case SkyLangParser.TrueLitContext ignored -> new Ast.BoolLit(true);
            case SkyLangParser.FalseLitContext ignored -> new Ast.BoolLit(false);
            case SkyLangParser.NotExprContext c -> new Ast.NotExpr(expr(c.expr()));
            case SkyLangParser.OldExprContext c -> new Ast.OldExpr(expr(c.expr()));
            case SkyLangParser.IsExprContext c -> isExpr(c);
            case SkyLangParser.AggExprContext c -> aggExpr(c);
            case SkyLangParser.NameExprContext c -> new Ast.NameExpr(c.ID().getText());
            default -> throw new IllegalStateException("unhandled expr node: " + ctx.getClass().getSimpleName());
        };
    }

    /** {@code x is empty} / {@code x is not empty} read as emptiness; other {@code is} forms are equality. */
    private Ast.Expr isExpr(SkyLangParser.IsExprContext ctx) {
        Ast.Expr left = expr(ctx.expr(0));
        boolean negated = ctx.NOT() != null;
        if (ctx.expr(1) instanceof SkyLangParser.NameExprContext n && n.ID().getText().equals("empty")) {
            Ast.Expr check = new Ast.EmptyCheck(left);
            return negated ? new Ast.NotExpr(check) : check;
        }
        return new Ast.BinExpr(negated ? "!=" : "==", left, expr(ctx.expr(1)));
    }

    private Ast.Expr aggExpr(SkyLangParser.AggExprContext ctx) {
        String kind = ctx.ID(0).getText();
        if (!kind.equals("sum") && !kind.equals("count")) {
            throw new IllegalArgumentException("unknown aggregate '" + kind + " of' (use sum or count)");
        }
        Ast.AggSource source = switch (ctx.aggSource()) {
            case SkyLangParser.AllSourceContext a -> {
                if (!a.ID(0).getText().equals("all")) {
                    throw new IllegalArgumentException("unrecognized aggregate source; say e.g."
                            + " 'for p in all products'");
                }
                yield new Ast.AllOf(a.ID(1).getText());
            }
            case SkyLangParser.ExprSourceContext e -> new Ast.SourceExpr(expr(e.expr()));
            default -> throw new IllegalStateException("unhandled aggregate source");
        };
        Optional<Ast.Expr> where = ctx.WHERE() == null
                ? Optional.empty()
                : Optional.of(expr(ctx.expr(1)));
        return new Ast.AggExpr(kind, expr(ctx.expr(0)), ctx.ID(1).getText(), source, where);
    }

    // ----- helpers -----------------------------------------------------------

    /** Split a MONEY token like {@code 9.99eur} into its exact amount and upper-cased currency. */
    private static Ast.MoneyLit moneyLit(String raw) {
        String amount = raw.substring(0, raw.length() - 3);
        String currency = raw.substring(raw.length() - 3).toUpperCase(java.util.Locale.ROOT);
        return new Ast.MoneyLit(new java.math.BigDecimal(amount), currency);
    }

    /** Strip the surrounding double quotes from a STRING token and resolve simple escapes. */
    private static String unquote(String raw) {
        String inner = raw.substring(1, raw.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '\\' && i + 1 < inner.length()) {
                char next = inner.charAt(++i);
                sb.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> next;   // \" \\ and anything else -> literal char
                });
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
