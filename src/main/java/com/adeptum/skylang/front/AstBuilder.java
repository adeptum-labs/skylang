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
        List<Ast.Entity> entities = new ArrayList<>();
        List<Ast.Service> services = new ArrayList<>();
        for (SkyLangParser.DeclContext decl : ctx.decl()) {
            if (decl.entity() != null) {
                entities.add(entity(decl.entity()));
            } else {
                services.add(service(decl.service()));
            }
        }
        return new Ast.Module(ctx.ID().getText(), entities, services);
    }

    // ----- entities ----------------------------------------------------------

    private Ast.Entity entity(SkyLangParser.EntityContext ctx) {
        List<Ast.Field> fields = new ArrayList<>();
        for (SkyLangParser.FieldContext f : ctx.field()) {
            fields.add(field(f));
        }
        return new Ast.Entity(ctx.ID().getText(), fields);
    }

    private Ast.Field field(SkyLangParser.FieldContext ctx) {
        boolean id = false;
        OptionalLong min = OptionalLong.empty();
        for (SkyLangParser.AnnotationContext a : ctx.annotation()) {
            String name = a.ID().getText();
            switch (name) {
                case "id" -> id = true;
                case "min" -> {
                    if (a.INT() == null) {
                        throw new IllegalArgumentException("@min requires an integer argument, e.g. @min(0)");
                    }
                    min = OptionalLong.of(Long.parseLong(a.INT().getText()));
                }
                default -> throw new IllegalArgumentException("unknown annotation @" + name);
            }
        }
        return new Ast.Field(ctx.ID().getText(), type(ctx.type()), id, min);
    }

    // ----- services & methods ------------------------------------------------

    private Ast.Service service(SkyLangParser.ServiceContext ctx) {
        List<Ast.Method> methods = new ArrayList<>();
        for (SkyLangParser.MethodContext m : ctx.method()) {
            methods.add(method(m));
        }
        return new Ast.Service(ctx.ID().getText(), methods);
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

        for (SkyLangParser.ClauseContext c : ctx.clause()) {
            if (c instanceof SkyLangParser.IntentClauseContext ic) {
                intent = Optional.of(unquote(ic.STRING().getText()));
            } else if (c instanceof SkyLangParser.RequiresClauseContext rc) {
                requires.add(expr(rc.expr()));
            } else if (c instanceof SkyLangParser.EnsuresClauseContext ec) {
                ensures.add(expr(ec.expr()));
            } else if (c instanceof SkyLangParser.ExampleClauseContext xc) {
                examples.add(example(xc));
            }
        }

        return new Ast.Method(ctx.ID().getText(), params, type(ctx.type()),
                intent, requires, ensures, examples);
    }

    private Ast.TypeRef type(SkyLangParser.TypeContext ctx) {
        return new Ast.TypeRef(ctx.ID().getText());
    }

    // ----- examples ----------------------------------------------------------

    private Ast.Example example(SkyLangParser.ExampleClauseContext ctx) {
        Ast.CallExpr call = call(ctx.call());
        return new Ast.Example(call, result(ctx.exampleResult()));
    }

    private Ast.Result result(SkyLangParser.ExampleResultContext ctx) {
        if (ctx instanceof SkyLangParser.ExprResultContext er) {
            return new Ast.ExprResult(expr(er.expr()));
        }
        SkyLangParser.EntityResultContext en = (SkyLangParser.EntityResultContext) ctx;
        String typeName = en.ID(1).getText();   // ID(0) is the soft keyword "a"
        List<Ast.FieldExpect> fields = new ArrayList<>();
        if (en.withClause() != null) {
            for (SkyLangParser.FieldExpectContext fe : en.withClause().fieldExpect()) {
                fields.add(new Ast.FieldExpect(fe.ID().getText(), expr(fe.expr())));
            }
        }
        return new Ast.EntityResult(typeName, fields);
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
            case SkyLangParser.NameExprContext c -> new Ast.NameExpr(c.ID().getText());
            default -> throw new IllegalStateException("unhandled expr node: " + ctx.getClass().getSimpleName());
        };
    }

    // ----- helpers -----------------------------------------------------------

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
