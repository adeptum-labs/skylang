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

/** Entry point for turning SkyLang source text into an {@link Ast.Module}. */
@Slf4j
public final class Parsing {

    private Parsing() {
    }

    public static Ast.Module parseFile(Path file) throws IOException {
        return parse(Files.readString(file), file.getFileName().toString());
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
