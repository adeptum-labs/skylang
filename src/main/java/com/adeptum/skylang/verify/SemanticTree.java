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

/**
 * A normalized, pixel-free view of a Faces view's markup: the columns it binds and the controls it
 * exposes. A view's {@code expect} clauses are checked against this structure, so the same
 * assertions hold regardless of the component library that produced the markup.
 */
public record SemanticTree(List<Column> columns, List<Control> controls) {

    /** A data-table column: the row field it binds and its header text. */
    public record Column(String field, String header) {
    }

    /** A control: its role ({@code "button"}, {@code "textbox"}, ...) and accessible name. */
    public record Control(String role, String name) {
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
}
