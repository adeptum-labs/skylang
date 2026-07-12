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

    /**
     * The view's projected non-table columns whose field erases to Bytes (or Maybe of it) —
     * the columns that must render as images.
     */
    public static java.util.Set<String> bytesColumns(Ast.Module module, Ast.View view) {
        java.util.Set<String> columns = new java.util.LinkedHashSet<>();
        collectBytesColumns(module, view.shows(), columns);
        for (Ast.Shows extra : view.moreShows()) {
            collectBytesColumns(module, extra, columns);
        }
        return columns;
    }

    private static void collectBytesColumns(Ast.Module module, Ast.Shows shows, java.util.Set<String> out) {
        if (shows.projection().isEmpty() || shows.projection().get().kind().equals("table")) {
            return;   // table rows keep their text rendering; images are a summary/form concern
        }
        module.services().stream()
                .filter(s -> s.name().equals(shows.query().service()))
                .flatMap(s -> s.methods().stream())
                .filter(m -> m.name().equals(shows.query().method()))
                .findFirst()
                .map(m -> m.returnType() instanceof Ast.GenericType g && !g.args().isEmpty()
                        && g.args().get(0) instanceof Ast.TypeRef ref ? ref.name() : null)
                .flatMap(entity -> module.entities().stream()
                        .filter(e -> e.name().equals(entity)).findFirst())
                .ifPresent(e -> {
                    for (String column : shows.projection().get().columns()) {
                        e.fields().stream()
                                .filter(f -> f.name().equals(column) && isBytes(f.type()))
                                .forEach(f -> out.add(column));
                    }
                });
    }

    private static boolean isBytes(Ast.Type type) {
        if (type instanceof Ast.GenericType g && g.name().equals("Maybe")) {
            return isBytes(g.args().get(0));
        }
        return type instanceof Ast.TypeRef ref && !ref.list() && ref.name().equals("Bytes");
    }

    /** @return a description of each unmet expectation; empty means the markup satisfies the view. */
    public List<String> unmetExpectations(Ast.View view, String markup) {
        return unmetExpectations(view, markup, java.util.Set.of());
    }

    /**
     * As above, additionally requiring each named Bytes column to render as an image —
     * an {@code h:graphicImage} bound to the field's {@code DataUri} bean helper.
     */
    public List<String> unmetExpectations(Ast.View view, String markup, java.util.Set<String> imageColumns) {
        SemanticTree tree = extractor.extract(markup);
        List<String> unmet = new ArrayList<>();
        for (String field : imageColumns) {
            if (!tree.hasImage(field)) {
                unmet.add("expected field '" + field + "' to render as an image"
                        + " (h:graphicImage bound to " + field + "DataUri)");
            }
        }
        for (Ast.Expect e : view.expects()) {
            switch (e) {
                case Ast.ExpectColumns c -> {
                    List<String> bound = new ArrayList<>(tree.columnFields());
                    bound.addAll(tree.imageFields());
                    if (!bound.containsAll(c.columns())) {
                        unmet.add("expected columns " + c.columns() + " but the view binds " + bound);
                    }
                }
                case Ast.ExpectActionKind a -> {
                    if ("button".equals(a.kind()) && !tree.hasButton(a.label())) {
                        unmet.add("expected a button labelled \"" + a.label() + "\"");
                    }
                }
                case Ast.ExpectProse ignored -> {
                    // Prose contracts guide synthesis through the prompt; their render-time
                    // verification arrives with the rendering of the form they describe.
                }
            }
        }
        for (Ast.Appears a : view.appears()) {
            switch (a) {
                case Ast.AppearsPlacement p -> {
                    if (!tree.controlInRegion(p.label(), p.region())) {
                        unmet.add("expected \"" + p.label() + "\" to render in region " + p.region());
                    }
                }
                case Ast.AppearsStyle s -> {
                    if (!tree.tableHasStyle(s.value())) {
                        unmet.add("expected the " + s.subject() + " to be " + s.value());
                    }
                }
                case Ast.AppearsColumnOrder co -> {
                    if (!tree.columnFields().equals(co.columns())) {
                        unmet.add("expected column order " + co.columns() + " but got " + tree.columnFields());
                    }
                }
                case Ast.AppearsActionState ignored -> {
                    // State-dependent looks need a data-driven render; prompt-guided for now.
                }
                case Ast.AppearsProse ignored -> {
                }
            }
        }
        return unmet;
    }
}
