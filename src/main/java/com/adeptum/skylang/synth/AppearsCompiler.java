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

import java.util.List;
import java.util.stream.Collectors;

/**
 * Compiles a natural-language UI edit into {@code appears} predicate lines — the durable, verifiable
 * vocabulary a preview edit becomes, so a fuzzy instruction turns into a structural contract the
 * compiler can then synthesize toward and check.
 */
@lombok.extern.slf4j.Slf4j
public final class AppearsCompiler {

    private static final String SYSTEM = """
            You translate a natural-language UI edit into SkyLang `appears` predicates — durable,
            structural contracts on a view. Output ONLY `appears` lines (one per line) and nothing
            else. Use exactly these forms:
              appears action "LABEL" in REGION   — place a control in a region (REGION is one lowercase word)
              appears rows is VALUE              — a row/table style or density (VALUE is one lowercase word)
              appears table is VALUE             — a table style
              appears columns (a, b, ...)        — the column order, using the view's field names
            Only reference action labels and column fields the view already declares. If the request
            cannot be expressed with these forms, output nothing.
            """;

    private final Llm llm;

    public AppearsCompiler(Llm llm) {
        this.llm = llm;
    }

    /** @return the {@code appears} source lines the instruction compiles to (possibly empty). */
    public List<String> compile(Ast.View view, String instruction) {
        String reply = llm.complete(SYSTEM, user(view, instruction));
        List<String> lines = reply.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("appears "))
                .toList();
        log.debug("appears for {} from \"{}\": {}", view.name(), instruction, lines);
        return lines;
    }

    private String user(Ast.View view, String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("View ").append(view.name()).append('\n');

        String actions = view.actions().stream()
                .map(a -> "\"" + a.label() + "\"")
                .collect(Collectors.joining(", "));
        sb.append("Actions: ").append(actions.isEmpty() ? "(none)" : actions).append('\n');

        if (view.shows() != null) {
            view.shows().projection().ifPresent(p ->
                    sb.append("Columns: ").append(String.join(", ", p.columns())).append('\n'));
        }

        if (!view.appears().isEmpty()) {
            sb.append("Current appearance:\n");
            for (Ast.Appears a : view.appears()) {
                sb.append("  ").append(renderAppears(a)).append('\n');
            }
        }

        sb.append("\nInstruction: ").append(instruction).append('\n');
        sb.append("Reply with the appears lines.");
        return sb.toString();
    }

    /** Render one {@code appears} predicate back to its canonical {@code .sky} line. */
    public static String renderAppears(Ast.Appears a) {
        return switch (a) {
            case Ast.AppearsPlacement p -> "appears action \"" + p.label() + "\" in " + p.region();
            case Ast.AppearsStyle s -> "appears " + s.subject() + " is " + s.value();
            case Ast.AppearsColumnOrder co -> "appears columns (" + String.join(", ", co.columns()) + ")";
            case Ast.AppearsActionState st -> "appears action \"" + st.label() + "\" is " + st.state()
                    + st.when().map(w -> " when " + w).orElse("");
            case Ast.AppearsWhen w -> "appears " + String.join(" ", w.subject()) + " when "
                    + com.adeptum.skylang.backend.Lowering.skyText(w.when());
            case Ast.AppearsProse p -> "appears " + p.text();
        };
    }
}
