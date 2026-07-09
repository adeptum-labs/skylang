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

import com.adeptum.skylang.front.ast.Ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Disposes a synthesized view by checking its {@code expect} clauses against the structure of the
 * generated markup — pixel-free and offline. A view is accepted only when nothing is unmet, exactly
 * as a method body is accepted only when its contracts pass.
 */
public final class ViewVerifier {

    private final SemanticTreeExtractor extractor = new SemanticTreeExtractor();

    /** @return a description of each unmet expectation; empty means the markup satisfies the view. */
    public List<String> unmetExpectations(Ast.View view, String markup) {
        SemanticTree tree = extractor.extract(markup);
        List<String> unmet = new ArrayList<>();
        for (Ast.Expect e : view.expects()) {
            switch (e) {
                case Ast.ExpectColumns c -> {
                    if (!tree.hasColumns(c.columns())) {
                        unmet.add("expected columns " + c.columns() + " but the view binds " + tree.columnFields());
                    }
                }
                case Ast.ExpectActionKind a -> {
                    if ("button".equals(a.kind()) && !tree.hasButton(a.label())) {
                        unmet.add("expected a button labelled \"" + a.label() + "\"");
                    }
                }
            }
        }
        return unmet;
    }
}
