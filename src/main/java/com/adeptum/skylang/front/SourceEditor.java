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

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Splices {@code appears} predicate lines into a view in the {@code .sky} source, leaving the rest of
 * the file byte-identical. Used to persist a preview edit back into the source on Accept.
 */
public final class SourceEditor {

    private SourceEditor() {
    }

    /** Insert the {@code appears} lines just before the named view's closing brace. */
    public static String addAppears(String source, String viewName, List<String> appearsLines) {
        if (appearsLines.isEmpty()) {
            return source;
        }
        SkyLangParser.ViewContext view = findView(source, viewName);
        int indent = clauseIndent(view);
        int rbraceStart = view.RBRACE().getSymbol().getStartIndex();
        int lineStart = source.lastIndexOf('\n', rbraceStart - 1) + 1;   // start of the line holding '}'

        String block = appearsLines.stream()
                .map(line -> " ".repeat(indent) + line + "\n")
                .collect(Collectors.joining());
        return source.substring(0, lineStart) + block + source.substring(lineStart);
    }

    private static SkyLangParser.ViewContext findView(String source, String viewName) {
        SkyLangLexer lexer = new SkyLangLexer(CharStreams.fromString(source));
        SkyLangParser parser = new SkyLangParser(new CommonTokenStream(lexer));
        for (SkyLangParser.DeclContext decl : parser.module_().decl()) {
            if (decl.view() != null && decl.view().ID().getText().equals(viewName)) {
                return decl.view();
            }
        }
        throw new IllegalArgumentException("no view named '" + viewName + "' in the source");
    }

    /** The column the view's clauses start at, so inserted lines match their indentation. */
    private static int clauseIndent(SkyLangParser.ViewContext view) {
        if (!view.viewClause().isEmpty()) {
            return view.viewClause(0).getStart().getCharPositionInLine();
        }
        return view.getStart().getCharPositionInLine() + 2;
    }
}
