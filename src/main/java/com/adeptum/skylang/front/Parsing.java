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

import com.adeptum.skylang.front.ast.Ast;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/** Entry point for turning SkyLang source text into an {@link Ast.Module}. */
@Slf4j
public final class Parsing {

    private Parsing() {
    }

    public static Ast.Module parseFile(Path file) throws IOException {
        return parse(Files.readString(file), file.getFileName().toString());
    }

    /**
     * Parse the whole source unit anchored at {@code file}: the file itself plus every sibling
     * {@code .sky} file declaring the same module, merged in filename order. The module header
     * is what groups files — siblings of other modules are simply other units and are ignored.
     */
    public static Ast.Module parseUnit(Path file) throws IOException {
        List<Path> files = unitFiles(file);
        if (files.size() <= 1) {
            return parseFile(file);
        }
        Map<String, String> declaredIn = new HashMap<>();
        List<Ast.TypeDecl> types = new ArrayList<>();
        List<Ast.Policy> policies = new ArrayList<>();
        List<Ast.Entity> entities = new ArrayList<>();
        List<Ast.Service> services = new ArrayList<>();
        List<Ast.View> views = new ArrayList<>();
        List<Ast.Flow> flows = new ArrayList<>();
        List<Ast.Component> components = new ArrayList<>();
        String name = null;
        for (Path part : files) {
            Ast.Module m = parseFile(part);
            name = m.name();
            String source = part.getFileName().toString();
            merge(m.types(), types, Ast.TypeDecl::name, "type", source, declaredIn);
            merge(m.policies(), policies, Ast.Policy::name, "policy", source, declaredIn);
            merge(m.entities(), entities, Ast.Entity::name, "entity", source, declaredIn);
            merge(m.services(), services, Ast.Service::name, "service", source, declaredIn);
            merge(m.views(), views, Ast.View::name, "page", source, declaredIn);
            merge(m.flows(), flows, Ast.Flow::name, "flow", source, declaredIn);
            merge(m.components(), components, Ast.Component::name, "component", source, declaredIn);
        }
        log.debug("merged {} files into module {}", files.size(), name);
        return new Ast.Module(name, types, policies, entities, services, views, flows, components);
    }

    private static <T> void merge(List<T> from, List<T> into, Function<T, String> name,
                                  String kind, String source, Map<String, String> declaredIn) {
        for (T decl : from) {
            String previous = declaredIn.put(kind + " " + name.apply(decl), source);
            if (previous != null && !previous.equals(source)) {
                throw new SkyParseException(kind + " '" + name.apply(decl)
                        + "' is declared in both " + previous + " and " + source);
            }
            into.add(decl);
        }
    }

    /** The unit's files: the anchor plus same-module siblings, sorted by filename. */
    private static List<Path> unitFiles(Path anchor) throws IOException {
        Optional<String> module = moduleHeaderOf(anchor);
        Path dir = anchor.toAbsolutePath().getParent();
        if (module.isEmpty() || dir == null) {
            return List.of(anchor);
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sky"))
                    .filter(p -> module.equals(moduleHeaderOf(p)))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /** A {@code module <name>} header, skipping comments — group 1 is the name. */
    private static final java.util.regex.Pattern MODULE_HEADER =
            java.util.regex.Pattern.compile("(?m)^\\s*module\\s+(\\w+)");

    /** The module a file declares, read from its header; empty when unreadable or absent. */
    public static Optional<String> moduleHeaderOf(Path file) {
        try {
            String source = Files.readString(file)
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("(?m)//.*$", "");
            java.util.regex.Matcher m = MODULE_HEADER.matcher(source);
            return m.find() ? Optional.of(m.group(1)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Ast.Module parse(String source, String sourceName) {
        SkyLangLexer lexer = new SkyLangLexer(CharStreams.fromString(source, sourceName));
        SkyLangParser parser = new SkyLangParser(new CommonTokenStream(lexer));

        // Fail fast with a precise message instead of ANTLR's default error-recovery.
        ThrowingErrorListener listener = new ThrowingErrorListener(sourceName);
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        SkyLangParser.Module_Context tree = parser.module_();
        Ast.Module module = new AstBuilder().build(tree);
        log.debug("parsed {}: module {} — {} entities, {} services, {} views, {} components, {} flows",
                sourceName, module.name(), module.entities().size(), module.services().size(),
                module.views().size(), module.components().size(), module.flows().size());
        return module;
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        private final String sourceName;

        ThrowingErrorListener(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                RecognitionException e) {
            throw new SkyParseException(sourceName + ":" + line + ":" + (charPositionInLine + 1) + ": " + msg);
        }
    }
}
