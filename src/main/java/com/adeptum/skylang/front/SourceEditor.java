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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Edits a view's {@code appears} predicate lines in the {@code .sky} source, leaving the rest of the
 * file byte-identical. Used to persist a preview edit back into the source on Accept — both the
 * natural-language path ({@link #addAppears}) and the deterministic control panel
 * ({@link #setAppears}).
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

    /**
     * Replace <em>all</em> of the named view's {@code appears} clauses with these lines, leaving every
     * other clause and all surrounding formatting byte-identical. The replacement is spliced at the
     * position of the first existing {@code appears} clause (or before the closing brace if there is
     * none), and any further {@code appears} clauses are removed — so a caller that hands the full
     * desired set gets a deterministic replace/reorder/remove. An empty list clears them.
     */
    public static String setAppears(String source, String viewName, List<String> appearsLines) {
        SkyLangParser.ViewContext view = findView(source, viewName);
        int indent = clauseIndent(view);
        List<int[]> spans = appearsLineSpans(source, view);   // {lineStart, lineEndExclusive} per clause

        String block = appearsLines.stream()
                .map(line -> " ".repeat(indent) + line + "\n")
                .collect(Collectors.joining());

        if (spans.isEmpty()) {
            return appearsLines.isEmpty() ? source : addAppears(source, viewName, appearsLines);
        }

        // Splice the new block where the first appears clause was and drop every appears line-span,
        // preserving whatever non-appears clauses sit between them.
        StringBuilder out = new StringBuilder();
        int firstStart = spans.get(0)[0];
        out.append(source, 0, firstStart).append(block);
        int cursor = firstStart;
        for (int[] span : spans) {
            out.append(source, cursor, span[0]);
            cursor = span[1];
        }
        out.append(source, cursor, source.length());
        return out.toString();
    }

    /** The whole-line source ranges each {@code appears} clause of the view occupies, in order. */
    private static List<int[]> appearsLineSpans(String source, SkyLangParser.ViewContext view) {
        List<int[]> spans = new ArrayList<>();
        for (SkyLangParser.ViewClauseContext clause : view.viewClause()) {
            if (clause instanceof SkyLangParser.AppearsClauseContext appears) {
                int start = appears.getStart().getStartIndex();
                int stop = appears.getStop().getStopIndex();
                int lineStart = source.lastIndexOf('\n', start - 1) + 1;
                int newline = source.indexOf('\n', stop);
                int lineEnd = newline == -1 ? source.length() : newline + 1;
                spans.add(new int[]{lineStart, lineEnd});
            }
        }
        return spans;
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
