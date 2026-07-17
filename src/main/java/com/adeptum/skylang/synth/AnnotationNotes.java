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

import com.adeptum.skylang.front.ast.Ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders developer-annotation blocks for the synthesis prompts: each use as
 * {@code @name(arg): intent} with its expect lines, all {@code {param}} placeholders
 * substituted with the use-site argument. Empty when nothing is annotated, so
 * prompts for existing projects stay byte-identical.
 */
final class AnnotationNotes {

    private AnnotationNotes() {
    }

    /** The block for a body prompt: the owning service's uses, then the method's. */
    static String forMethod(Ast.Module module, Ast.Service service, Ast.Method method) {
        List<Ast.AnnotationUse> uses = new ArrayList<>(service.annotations());
        uses.addAll(method.annotations());
        return forTarget(module, "Annotations in force — honor each", uses);
    }

    /** Comment lines under an entity's shape line in the entities-in-scope section. */
    static String forEntity(Ast.Module module, Ast.Entity entity) {
        return forTarget(module, "Annotations on " + entity.name() + " — honor each",
                entity.annotations());
    }

    static String forTarget(Ast.Module module, String heading, List<Ast.AnnotationUse> uses) {
        if (uses.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("// ").append(heading).append(":\n");
        for (Ast.AnnotationUse u : uses) {
            Ast.AnnotationDecl d = declOf(module, u);
            sb.append("//   ").append(u).append(": ").append(substituted(d.intent(), d, u)).append('\n');
            for (String e : d.expects()) {
                sb.append("//     expect ").append(substituted(e, d, u)).append('\n');
            }
        }
        return sb.toString();
    }

    private static Ast.AnnotationDecl declOf(Ast.Module module, Ast.AnnotationUse use) {
        return module.annotationDecls().stream()
                .filter(d -> d.name().equals(use.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("undeclared annotation @" + use.name()));
    }

    private static String substituted(String text, Ast.AnnotationDecl decl, Ast.AnnotationUse use) {
        if (decl.params().isEmpty() || use.arg().isEmpty()) {
            return text;
        }
        String value = switch (use.arg().get()) {
            case Ast.StrLit s -> s.value();
            case Ast.IntLit i -> String.valueOf(i.value());
            default -> use.arg().get().toString();
        };
        return text.replace("{" + decl.params().get(0).name() + "}", value);
    }
}
