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

package com.adeptum.skylang.types;

import com.adeptum.skylang.front.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Design-time feasibility warnings for pages — contradictions the render verifier can only
 * discover the slow way, after synthesis, as an opaque assertion failure. Reported at
 * {@code sky check} and before any model call, with the reason and concrete ways out.
 *
 * <p>The first pattern it catches: a page that shows an optional ({@code Maybe<T>}) value yet
 * expects, unconditionally, a control that only exists when that value is present. The renderer
 * has no way to make the value present during verification, so the expectation cannot hold — the
 * two halves of the page (its "value present" and "value absent" states) are mutually exclusive.
 */
public final class ViewFeasibility {

    private ViewFeasibility() {
    }

    /** @return one message per page whose expectations contradict its data, empty when all are sound. */
    public static List<String> contradictions(Ast.Module module) {
        List<String> found = new ArrayList<>();
        for (Ast.View view : module.views()) {
            optionalContentContradiction(module, view).ifPresent(found::add);
        }
        return found;
    }

    private static Optional<String> optionalContentContradiction(Ast.Module module, Ast.View view) {
        Ast.QualifiedCall query = view.shows().query();

        // A query with arguments (e.g. a route-bound Orders.find(id)) can be exercised with a value
        // that resolves; only a parameterless, ambient optional (Session.current()) has no input the
        // verifier can vary to make it present, so it is the one that cannot be seeded.
        if (!query.args().isEmpty()) {
            return Optional.empty();
        }
        Optional<Ast.Method> shown = method(module, query.service(), query.method());
        boolean optional = shown
                .map(m -> m.returnType() instanceof Ast.GenericType g && g.name().equals("Maybe"))
                .orElse(false);
        if (!optional) {
            return Optional.empty();
        }

        // Controls bound to the shown record ("on the account") cannot exist when it is absent.
        Set<String> boundToRecord = view.actions().stream()
                .filter(a -> a.rowVar() != null && !a.rowVar().isBlank())
                .map(Ast.Action::label)
                .collect(Collectors.toSet());

        List<String> unverifiable = new ArrayList<>();
        for (Ast.Expect expect : view.expects()) {
            if (expect instanceof Ast.ExpectActionKind kind && boundToRecord.contains(kind.label())) {
                unverifiable.add("action \"" + kind.label() + "\" is a " + kind.kind());
            }
        }
        if (unverifiable.isEmpty()) {
            return Optional.empty();
        }

        String source = query.service() + "." + query.method() + "()";
        String type = shown.get().returnType().sky();
        StringBuilder sb = new StringBuilder();
        sb.append("page ").append(view.name())
                .append(" cannot be verified as written.\n");
        sb.append("  It shows ").append(source).append(", which returns ").append(type)
                .append(" — a value that can be absent, and is absent in the state the\n")
                .append("  renderer produces (nothing supplies one during verification). These expectations\n")
                .append("  need it present:\n");
        unverifiable.forEach(e -> sb.append("    - expect ").append(e).append('\n'));
        sb.append("  but such a control has no ").append(elementName(view))
                .append(" to render when ").append(source).append(" is empty.\n");
        sb.append("  Resolve it one of these ways:\n");
        sb.append("    - Model only the absent state on this page (the control shown when there is no\n")
                .append("      value), and defer the present-value half until an effect can supply one.\n");
        sb.append("    - Split ").append(view.name())
                .append(" into a present-value page and an absent-value page, each verifiable alone.\n");
        sb.append("    - Make ").append(source)
                .append(" yield a value during verification, if the present render is what you mean\n")
                .append("      to check.");
        return Optional.of(sb.toString());
    }

    /** The row variable the page binds the shown record to ("the account"), for the message. */
    private static String elementName(Ast.View view) {
        return view.actions().stream()
                .map(Ast.Action::rowVar)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .map(v -> "`" + v + "`")
                .orElse("value");
    }

    private static Optional<Ast.Method> method(Ast.Module module, String service, String name) {
        return module.services().stream()
                .filter(s -> s.name().equals(service))
                .flatMap(s -> s.methods().stream())
                .filter(m -> m.name().equals(name))
                .findFirst();
    }
}
