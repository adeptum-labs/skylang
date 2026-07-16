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

import java.util.List;
import java.util.Set;

/**
 * A normalized, pixel-free view of a Faces view's markup: the columns it binds, the controls it
 * exposes (with the style regions they render within), and the table's style classes. A view's
 * {@code expect} and {@code appears} clauses are checked against this structure, so the same
 * assertions hold regardless of the component library that produced the markup.
 */
public record SemanticTree(List<Column> columns, List<Control> controls, Set<String> tableClasses,
                           List<String> imageFields, List<Conditional> conditionals,
                           List<Navigation> navigations) {

    public SemanticTree(List<Column> columns, List<Control> controls, Set<String> tableClasses) {
        this(columns, controls, tableClasses, List.of(), List.of(), List.of());
    }

    public SemanticTree(List<Column> columns, List<Control> controls, Set<String> tableClasses,
                        List<String> imageFields) {
        this(columns, controls, tableClasses, imageFields, List.of(), List.of());
    }

    public SemanticTree(List<Column> columns, List<Control> controls, Set<String> tableClasses,
                        List<String> imageFields, List<Conditional> conditionals) {
        this(columns, controls, tableClasses, imageFields, conditionals, List.of());
    }

    /** A navigation control ({@code h:button}/{@code h:link}): its label and its outcome view. */
    public record Navigation(String name, String outcome) {
    }

    /** A data-table column: the row field it binds and its header text. */
    public record Column(String field, String header) {
    }

    /** An element rendered conditionally: its style classes and its {@code rendered} expression. */
    public record Conditional(Set<String> classes, String rendered) {
    }

    /** A control: its role ({@code "button"}, {@code "textbox"}, ...), accessible name, and enclosing region classes. */
    public record Control(String role, String name, Set<String> regions) {
    }

    public List<String> columnFields() {
        return columns.stream().map(Column::field).toList();
    }

    public boolean hasColumns(List<String> fields) {
        return columnFields().containsAll(fields);
    }

    public boolean hasButton(String label) {
        return controls.stream().anyMatch(c -> c.role().equals("button") && label.equals(c.name()));
    }

    /** True if a control named {@code label} renders inside a region carrying the class {@code region}. */
    public boolean controlInRegion(String label, String region) {
        return controls.stream().anyMatch(c -> label.equals(c.name()) && c.regions().contains(region));
    }

    /** True if the data table carries the style class {@code style}. */
    public boolean tableHasStyle(String style) {
        return tableClasses.contains(style);
    }

    /** True if the named field renders as an image (an {@code h:graphicImage} data-URI binding). */
    public boolean hasImage(String field) {
        return imageFields.contains(field);
    }

    /** True if some element renders conditionally on the named param and carries it as a class. */
    public boolean hasConditional(String param) {
        return conditionals.stream()
                .anyMatch(c -> c.classes().contains(param) && c.rendered().contains(param));
    }

    /** True if a navigation control named {@code label} leads to the view {@code outcome}. */
    public boolean navigatesTo(String label, String outcome) {
        return navigations.stream()
                .anyMatch(n -> label.equals(n.name()) && outcome.equals(n.outcome()));
    }
}
