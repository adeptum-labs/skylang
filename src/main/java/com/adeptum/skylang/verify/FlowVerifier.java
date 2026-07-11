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
import com.adeptum.skylang.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disposes a synthesized flow by walking its navigation graph — the JSON the model proposed —
 * through every declared step and transition, checking each {@code expect} against the walk.
 * A flow that lets the user skip a step, or wander past success, is rejected and regenerated.
 */
public final class FlowVerifier {

    /** The parsed navigation graph: step -> next step, plus trigger -> target transitions. */
    public record Graph(List<String> steps, Map<String, String> transitions) {
    }

    /** Parse the synthesized graph, or null when the reply is not the expected shape. */
    public Graph parse(String json) {
        try {
            Object root = Json.parse(json);
            if (!(root instanceof Map<?, ?> map) || !(map.get("steps") instanceof List<?> steps)) {
                return null;
            }
            Map<String, String> transitions = new LinkedHashMap<>();
            if (map.get("transitions") instanceof Map<?, ?> t) {
                t.forEach((k, v) -> transitions.put(String.valueOf(k), String.valueOf(v)));
            }
            return new Graph(steps.stream().map(String::valueOf).toList(), transitions);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return each unmet expectation; empty means the graph realises the declared flow. */
    public List<String> unmetExpectations(Ast.Flow flow, Graph graph) {
        List<String> unmet = new ArrayList<>();
        if (graph == null) {
            unmet.add("the flow graph is not readable JSON with a steps array");
            return unmet;
        }
        List<String> declared = flow.steps().stream().map(Ast.FlowStep::name).toList();
        if (!graph.steps().equals(declared)) {
            unmet.add("expected the steps " + declared + " in order, got " + graph.steps());
        }
        for (Ast.FlowTransition t : flow.transitions()) {
            if (!graph.transitions().containsKey(t.trigger())) {
                unmet.add("expected a transition on " + t.trigger());
            }
        }
        for (String expect : flow.expects()) {
            checkExpect(flow, graph, expect, unmet);
        }
        return unmet;
    }

    /**
     * The two resolvable expectation shapes: {@code step X is reachable only after Y} (the
     * declared order must place Y before X) and {@code no step follows success} (the success
     * transition must leave the steps, to a page). Other prose stays prompt-carried.
     */
    private void checkExpect(Ast.Flow flow, Graph graph, String expect, List<String> unmet) {
        List<String> steps = graph.steps();
        java.util.regex.Matcher only = java.util.regex.Pattern
                .compile("step (\\w+) is reachable only after (\\w+)").matcher(expect);
        if (only.matches()) {
            int target = steps.indexOf(only.group(1));
            int gate = steps.indexOf(only.group(2));
            if (target < 0 || gate < 0 || gate >= target) {
                unmet.add("expected " + only.group(1) + " only after " + only.group(2)
                        + " in the walk " + steps);
            }
            return;
        }
        if (expect.equals("no step follows success")) {
            String target = graph.transitions().get("success");
            if (target == null || steps.contains(target.replace("page ", ""))
                    && !target.startsWith("page ")) {
                unmet.add("expected success to leave the flow, but it goes to " + target);
            }
        }
    }

    /** The walk lines the build transcript prints: the forward path, then each failure loop. */
    public List<String> walkLines(Ast.Flow flow, Graph graph) {
        List<String> lines = new ArrayList<>();
        lines.add("walked: " + String.join(" -> ", graph.steps()) + " -> success");
        for (Ast.FlowTransition t : flow.transitions()) {
            if (!t.trigger().equals("success")) {
                String target = graph.transitions().getOrDefault(t.trigger(), "?");
                lines.add("walked: " + graph.steps().get(graph.steps().size() - 1)
                        + " -> " + t.trigger() + " -> " + target.replace("step ", ""));
            }
        }
        return lines;
    }
}
