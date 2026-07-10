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

import com.adeptum.skylang.backend.Lowering;
import com.adeptum.skylang.backend.TsStager;
import com.adeptum.skylang.front.ast.Ast;

import java.util.stream.Collectors;

/**
 * Prompts for the ts-node profile: same specification, TypeScript representations. The core's
 * guarantees do not move — Int is still 64-bit (a bigint), contracts still gate the body.
 */
public final class TsPromptBuilder {

    private static final String SYSTEM = """
            You implement one TypeScript method body for a specification written in SkyLang.
            - Output ONLY the TypeScript statements that go inside the method body — no signature,
              no class, no markdown fences, no commentary.
            - Type lowering: Int -> bigint (use 1n literals; never number), Text -> string,
              Bool -> boolean, [E] -> E[], and each entity to a class whose readonly fields are
              its declared fields in order (constructed with `new`, accessed as properties, e.g.
              p.stock).
            - Entities are immutable: to "change" a field, construct a new instance.
            - The service's effect handles are constructor fields: this.db (save/find/all) for db,
              this.clock.instant() for clock. Never use raw alternatives — no fetch, no fs, no
              Date.now().
            - A violated `requires` precondition throws a RangeError before any other work.
            - Each `raises Error when <condition>` names an error class in scope: throw exactly
              that class under exactly that condition.
            - The body must satisfy every `requires`, `ensures`, `raises`, and `example`.
            """;

    public String system() {
        return SYSTEM;
    }

    public String user(Ast.Module module, Ast.Service service, Ast.Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Entities in scope (their TypeScript class shapes):\n");
        for (Ast.Entity e : module.entities()) {
            if (e.fields().isEmpty()) {
                sb.append("class ").append(e.name()).append(" extends Error {}\n");
                continue;
            }
            String fields = e.fields().stream()
                    .map(f -> "readonly " + f.name() + ": " + TsStager.tsType(f.type()))
                    .collect(Collectors.joining(", "));
            sb.append("class ").append(e.name()).append(" { ").append(fields).append(" }\n");
        }
        if (!service.uses().isEmpty()) {
            sb.append("\n// Effects available on this: ")
                    .append(service.uses().stream()
                            .map(u -> "db".equals(u) ? "this.db" : "this." + u)
                            .collect(Collectors.joining(", ")))
                    .append('\n');
        }
        sb.append("\n// Method to implement (TypeScript signature):\n");
        String params = method.params().stream()
                .map(p -> p.name() + ": " + TsStager.tsType(p.type()))
                .collect(Collectors.joining(", "));
        sb.append(method.name()).append('(').append(params).append("): ")
                .append(TsStager.tsType(method.returnType())).append("\n\n");
        method.intent().ifPresent(intent -> sb.append("Intent: ").append(intent).append("\n\n"));
        method.requires().forEach(r -> sb.append("requires ").append(Lowering.skyText(r)).append('\n'));
        method.ensures().forEach(e -> sb.append("ensures  ").append(Lowering.skyText(e)).append('\n'));
        for (Ast.Raise r : method.raises()) {
            sb.append("raises   ").append(r.error()).append('\n');
        }
        for (Ast.Example ex : method.examples()) {
            sb.append("example  ").append(Lowering.skyText(ex.call())).append('\n');
        }
        sb.append("\nReturn the body statements now.\n");
        return sb.toString();
    }
}
