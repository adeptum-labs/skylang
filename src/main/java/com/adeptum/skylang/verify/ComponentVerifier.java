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

package com.adeptum.skylang.verify;

import com.adeptum.skylang.backend.Lowering;
import com.adeptum.skylang.front.ast.Ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Disposes a synthesized composite component structurally: the markup must declare a typed
 * interface for each parameter, bind the shown value, and carry each declared state style.
 * A widget missing its own contract never freezes, exactly like a method body.
 */
public final class ComponentVerifier {

    /** @return each unmet expectation; empty means the markup realises the declaration. */
    public List<String> unmetExpectations(Ast.Component component, String markup) {
        List<String> unmet = new ArrayList<>();
        if (!markup.contains("cc:interface") || !markup.contains("cc:implementation")) {
            unmet.add("a composite component declares cc:interface and cc:implementation");
        }
        for (Ast.Param p : component.params()) {
            if (!markup.contains("name=\"" + p.name() + "\"")) {
                unmet.add("expected a cc:attribute for parameter '" + p.name() + "'");
            }
        }
        String shown = lastField(component.shows().value());
        if (shown != null && !markup.contains(shown)) {
            unmet.add("expected the markup to bind the shown value ("
                    + Lowering.skyText(component.shows().value()) + ")");
        }
        for (Ast.ComponentAppears a : component.appears()) {
            if (!markup.contains(a.style())) {
                unmet.add("expected the state style '" + a.style() + "' ("
                        + Lowering.skyText(a.when()) + ")");
            }
        }
        return unmet;
    }

    private static String lastField(Ast.Expr shown) {
        return shown instanceof Ast.MemberExpr me ? me.field() : null;
    }
}
