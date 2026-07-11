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

package com.adeptum.skylang.cli;

import com.adeptum.skylang.config.ConfigException;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.SkyParseException;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.types.CheckException;
import com.adeptum.skylang.types.TypeChecker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code sky check} — hard-layer type + contract check only. Fast, offline, no model. */
@Command(name = "check", description = "Type-check the hard layer; no synthesis, no network.")
public final class CheckCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<file.sky>", description = "The SkyLang source file to check.")
    Path file;

    @Option(names = "--profile",
            description = "Target profile (default: the sky.project manifest, else jvm-jakarta).")
    String profile;

    @Override
    public Integer call() {
        try {
            Ast.Module module = Parsing.parseFile(file);
            new TypeChecker().check(module);
            ActiveProfile.activate(profile, file, module);   // the portability boundary is frontend
            checkpoint(module);
            return 0;
        } catch (SkyParseException | CheckException | ConfigException e) {
            System.err.println("error [frontend]: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("error: cannot read " + file + ": " + e.getMessage());
            return 1;
        }
    }

    /** The checkpoint transcript: what the hard layer contains, and that no model ran. */
    private void checkpoint(Ast.Module module) {
        System.out.printf("  %-30s ok%n", "parsing " + file.getFileName() + " ...");
        if (!module.entities().isEmpty()) {
            System.out.print(wrapped("  entities: ", module.entities().stream()
                    .map(Ast.Entity::name).toList()));
        }
        if (!module.types().isEmpty()) {
            System.out.printf("  %-30s ok%n", "refined types: " + module.types().stream()
                    .map(Ast.TypeDecl::name).collect(java.util.stream.Collectors.joining(", ")));
        }
        String values = module.entities().stream()
                .filter(e -> !e.values().isEmpty())
                .map(e -> e.name() + " (" + e.values().size() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        if (!values.isEmpty()) {
            System.out.println("  values: " + values);
        }
        if (!module.services().isEmpty()) {
            System.out.println("  services: " + module.services().stream()
                    .map(s -> s.name() + " (" + s.methods().size() + " method"
                            + (s.methods().size() == 1 ? "" : "s") + ")")
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (!module.views().isEmpty()) {
            System.out.println("  views: " + module.views().stream()
                    .map(Ast.View::name).collect(java.util.stream.Collectors.joining(", ")));
        }
        System.out.printf("  %-30s ok%n", "type-checking hard layer ...");
        System.out.println("  no model calls; nothing generated.");
    }

    /** A comma list wrapped at a readable width, continuations aligned under the first item. */
    private static String wrapped(String head, java.util.List<String> names) {
        StringBuilder sb = new StringBuilder(head);
        String indent = " ".repeat(head.length());
        int column = head.length();
        for (int i = 0; i < names.size(); i++) {
            String piece = names.get(i) + (i < names.size() - 1 ? "," : "");
            if (column + piece.length() > 64 && column > head.length()) {
                sb.append('\n').append(indent);
                column = indent.length();
            } else if (i > 0) {
                sb.append(' ');
                column++;
            }
            sb.append(piece);
            column += piece.length();
        }
        return sb.append('\n').toString();
    }
}
