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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Facelets fragment into a {@link SemanticTree} — the structure a view's {@code expect}
 * clauses are checked against. Deterministic and offline: it reads the component markup directly,
 * with no rendering and no servlet container.
 */
public final class SemanticTreeExtractor {

    /** A value binding such as {@code #{row.stock}} — group 1 is the var, group 2 the field. */
    private static final Pattern BINDING = Pattern.compile("#\\{\\s*(\\w+)\\.(\\w+)\\s*}");

    public SemanticTree extract(String markup) {
        Document doc = Jsoup.parse(markup, "", Parser.xmlParser());

        List<SemanticTree.Column> columns = new ArrayList<>();
        Set<String> tableClasses = new LinkedHashSet<>();
        for (Element table : doc.getElementsByTag("h:dataTable")) {
            tableClasses.addAll(classesOf(table));
            String var = table.attr("var");
            for (Element column : table.getElementsByTag("h:column")) {
                String field = boundField(column, var);
                if (field != null) {
                    columns.add(new SemanticTree.Column(field, header(column)));
                }
            }
        }

        List<SemanticTree.Control> controls = new ArrayList<>();
        for (Element button : doc.getElementsByTag("h:commandButton")) {
            controls.add(new SemanticTree.Control("button", label(button), regionsOf(button)));
        }
        for (Element link : doc.getElementsByTag("h:commandLink")) {
            controls.add(new SemanticTree.Control("button", label(link), regionsOf(link)));
        }
        for (Element input : doc.getElementsByTag("h:inputText")) {
            controls.add(new SemanticTree.Control("textbox", label(input), regionsOf(input)));
        }

        return new SemanticTree(columns, controls, tableClasses);
    }

    /** The style-class tokens directly on an element — both {@code class} and Faces {@code styleClass}. */
    private static Set<String> classesOf(Element el) {
        Set<String> classes = new LinkedHashSet<>();
        for (String attr : new String[]{"class", "styleClass"}) {
            String value = el.attr(attr);
            if (!value.isBlank()) {
                classes.addAll(List.of(value.trim().split("\\s+")));
            }
        }
        return classes;
    }

    /** The union of style classes on an element and all its ancestors — the regions it renders within. */
    private static Set<String> regionsOf(Element el) {
        Set<String> regions = new LinkedHashSet<>(classesOf(el));
        for (Element parent : el.parents()) {
            regions.addAll(classesOf(parent));
        }
        return regions;
    }

    /** The first row field bound by a {@code value} expression inside this column, or null. */
    private static String boundField(Element column, String var) {
        for (Element e : column.getAllElements()) {
            Matcher m = BINDING.matcher(e.attr("value"));
            if (m.find() && m.group(1).equals(var)) {
                return m.group(2);
            }
        }
        return null;
    }

    /** The header text of a column — the first literal {@code value} in its header facet. */
    private static String header(Element column) {
        for (Element facet : column.getElementsByTag("f:facet")) {
            if ("header".equals(facet.attr("name"))) {
                for (Element e : facet.getAllElements()) {
                    String value = e.attr("value");
                    if (!value.isEmpty() && !BINDING.matcher(value).find()) {
                        return value.strip();
                    }
                }
                return facet.text().strip();
            }
        }
        return "";
    }

    /** The accessible name of a control: its literal {@code value}, else its text content. */
    private static String label(Element control) {
        String value = control.attr("value");
        return value.isEmpty() ? control.text().strip() : value;
    }
}
