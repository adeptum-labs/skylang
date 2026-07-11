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

import com.adeptum.skylang.front.ast.Ast;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Serializes a view's editable state to the JSON the control panel reads: the declared columns and
 * actions, the current {@code appears} folded into named fields, and the curated region/style
 * allow-lists the panel offers. Hand-rolled to stay zero-dependency, mirroring the studio's style.
 */
final class SpecJson {

    /** Placement regions the grammar accepts as a bare {@code ID}. */
    static final List<String> REGIONS = List.of("toolbar", "row", "footer");
    /** Table/row styles the grammar accepts as a bare {@code ID}. */
    static final List<String> STYLES = List.of("compact", "striped", "bordered");

    private SpecJson() {
    }

    static String of(Ast.View view) {
        List<Ast.Appears> appears = view.appears();
        StringBuilder json = new StringBuilder("{");
        json.append("\"kind\":\"view\",\"name\":").append(str(view.name()));
        json.append(",\"columns\":").append(strArray(
                view.shows().projection().map(Ast.Projection::columns).orElse(List.of())));
        json.append(",\"actions\":").append(strArray(
                view.actions().stream().map(Ast.Action::label).toList()));
        json.append(",\"columnOrder\":").append(appears.stream()
                .filter(a -> a instanceof Ast.AppearsColumnOrder).findFirst()
                .map(a -> strArray(((Ast.AppearsColumnOrder) a).columns())).orElse("null"));
        json.append(",\"tableStyle\":").append(style(appears, "table"));
        json.append(",\"rowsStyle\":").append(style(appears, "rows"));
        json.append(",\"placements\":[").append(appears.stream()
                .filter(a -> a instanceof Ast.AppearsPlacement).map(a -> {
                    Ast.AppearsPlacement p = (Ast.AppearsPlacement) a;
                    return "{\"label\":" + str(p.label()) + ",\"region\":" + str(p.region()) + "}";
                }).collect(Collectors.joining(","))).append("]");
        json.append(",\"actionStates\":[").append(appears.stream()
                .filter(a -> a instanceof Ast.AppearsActionState).map(a -> {
                    Ast.AppearsActionState s = (Ast.AppearsActionState) a;
                    return "{\"label\":" + str(s.label()) + ",\"state\":" + str(s.state())
                            + ",\"when\":" + s.when().map(SpecJson::str).orElse("null") + "}";
                }).collect(Collectors.joining(","))).append("]");
        json.append(",\"regions\":").append(strArray(REGIONS));
        json.append(",\"styles\":").append(strArray(STYLES));
        return json.append("}").toString();
    }

    private static String style(List<Ast.Appears> appears, String subject) {
        return appears.stream()
                .filter(a -> a instanceof Ast.AppearsStyle s && s.subject().equals(subject))
                .map(a -> str(((Ast.AppearsStyle) a).value())).findFirst().orElse("null");
    }

    private static String strArray(List<String> items) {
        return "[" + items.stream().map(SpecJson::str).collect(Collectors.joining(",")) + "]";
    }

    private static String str(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append("\"").toString();
    }
}
