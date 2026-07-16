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

package com.adeptum.skylang.preview;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.SourceEditor;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.AppearsCompiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A single, deterministic edit to a view's appearance — the vocabulary the visual control panel
 * emits, applied without the model. Each op reworks the view's {@code appears} set and routes
 * through {@link SourceEditor#setAppears}, so a change is a pure source rewrite that replaces,
 * reorders, or removes clauses while leaving everything else byte-identical. The set is
 * sealed-extensible: a new control is a new record here plus a widget in the panel.
 */
public sealed interface StructuredChange {

    /** The view this change targets. */
    String view();

    /** A short, human-readable summary for the edit transcript. */
    String describe();

    /** Rework the view's current {@code appears} set into the desired one. */
    List<Ast.Appears> transform(List<Ast.Appears> current);

    /**
     * Build a change from the studio's form fields (`op`, `view`, and the op's parameters).
     * Throws {@link IllegalArgumentException} on an unknown op or a missing field.
     */
    static StructuredChange fromForm(Map<String, String> form) {
        String op = form.getOrDefault("op", "");
        String view = required(form, "view");
        return switch (op) {
            case "setColumnOrder" -> new SetColumnOrder(view, splitCsv(required(form, "columns")));
            case "setTableStyle" -> new SetTableStyle(view, required(form, "value"));
            case "setRowsStyle" -> new SetRowsStyle(view, required(form, "value"));
            case "setActionRegion" -> new SetActionRegion(view, required(form, "label"), required(form, "region"));
            case "clearActionRegion" -> new ClearActionRegion(view, required(form, "label"));
            case "setActionState" -> new SetActionState(view, required(form, "label"), required(form, "state"),
                    Optional.ofNullable(nullIfBlank(form.get("when"))));
            default -> throw new IllegalArgumentException("unknown structured-change op '" + op + "'");
        };
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return value.trim();
    }

    private static List<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Apply this change to the {@code .sky} source, returning the rewritten source. */
    default String applyTo(String source) {
        Ast.View target = Parsing.parse(source, "preview.sky").views().stream()
                .filter(v -> v.name().equals(view()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no view named '" + view() + "'"));
        List<String> lines = canonical(transform(target.appears())).stream()
                .map(AppearsCompiler::renderAppears)
                .toList();
        return SourceEditor.setAppears(source, view(), lines);
    }

    // ----- the ops --------------------------------------------------------------

    record SetColumnOrder(String view, List<String> columns) implements StructuredChange {
        @Override
        public List<Ast.Appears> transform(List<Ast.Appears> current) {
            List<Ast.Appears> next = without(current, a -> a instanceof Ast.AppearsColumnOrder);
            next.add(new Ast.AppearsColumnOrder(List.copyOf(columns)));
            return next;
        }

        @Override
        public String describe() {
            return "columns of " + view + " → (" + String.join(", ", columns) + ")";
        }
    }

    record SetTableStyle(String view, String value) implements StructuredChange {
        @Override
        public List<Ast.Appears> transform(List<Ast.Appears> current) {
            return withStyle(current, "table", value);
        }

        @Override
        public String describe() {
            return view + " table is " + value;
        }
    }

    record SetRowsStyle(String view, String value) implements StructuredChange {
        @Override
        public List<Ast.Appears> transform(List<Ast.Appears> current) {
            return withStyle(current, "rows", value);
        }

        @Override
        public String describe() {
            return view + " rows is " + value;
        }
    }

    record SetActionRegion(String view, String label, String region) implements StructuredChange {
        @Override
        public List<Ast.Appears> transform(List<Ast.Appears> current) {
            List<Ast.Appears> next = without(current,
                    a -> a instanceof Ast.AppearsPlacement p && p.label().equals(label));
            next.add(new Ast.AppearsPlacement(label, region));
            return next;
        }

        @Override
        public String describe() {
            return "\"" + label + "\" in " + region;
        }
    }

    record ClearActionRegion(String view, String label) implements StructuredChange {
        @Override
        public List<Ast.Appears> transform(List<Ast.Appears> current) {
            return without(current, a -> a instanceof Ast.AppearsPlacement p && p.label().equals(label));
        }

        @Override
        public String describe() {
            return "\"" + label + "\" placement cleared";
        }
    }

    record SetActionState(String view, String label, String state, Optional<String> when)
            implements StructuredChange {
        @Override
        public List<Ast.Appears> transform(List<Ast.Appears> current) {
            List<Ast.Appears> next = without(current,
                    a -> a instanceof Ast.AppearsActionState s && s.label().equals(label));
            next.add(new Ast.AppearsActionState(label, state, when));
            return next;
        }

        @Override
        public String describe() {
            return "\"" + label + "\" is " + state + when.map(w -> " when " + w).orElse("");
        }
    }

    // ----- shared helpers -------------------------------------------------------

    private static List<Ast.Appears> without(List<Ast.Appears> current, Predicate<Ast.Appears> drop) {
        List<Ast.Appears> next = new ArrayList<>();
        for (Ast.Appears a : current) {
            if (!drop.test(a)) {
                next.add(a);
            }
        }
        return next;
    }

    private static List<Ast.Appears> withStyle(List<Ast.Appears> current, String subject, String value) {
        List<Ast.Appears> next = without(current,
                a -> a instanceof Ast.AppearsStyle s && s.subject().equals(subject));
        next.add(new Ast.AppearsStyle(subject, value));
        return next;
    }

    /** A stable order so equivalent edits produce byte-identical source (and a stable freeze hash). */
    private static List<Ast.Appears> canonical(List<Ast.Appears> appears) {
        List<Ast.Appears> sorted = new ArrayList<>(appears);
        sorted.sort(Comparator.comparingInt(StructuredChange::rank).thenComparing(StructuredChange::key));
        return sorted;
    }

    private static int rank(Ast.Appears a) {
        return switch (a) {
            case Ast.AppearsColumnOrder ignored -> 0;
            case Ast.AppearsStyle ignored -> 1;
            case Ast.AppearsPlacement ignored -> 2;
            case Ast.AppearsActionState ignored -> 3;
            case Ast.AppearsWhen ignored -> 4;
            case Ast.AppearsSigned ignored -> 5;
            case Ast.AppearsProse ignored -> 6;
        };
    }

    private static String key(Ast.Appears a) {
        return switch (a) {
            case Ast.AppearsStyle s -> s.subject();
            case Ast.AppearsPlacement p -> p.label();
            case Ast.AppearsActionState s -> s.label();
            default -> "";
        };
    }
}
