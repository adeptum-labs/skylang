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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Commands run bare inside a project directory: when no source file is named, the sole
 * {@code .sky} file next to the manifest is the project's source.
 */
final class SourceFiles {

    private SourceFiles() {
    }

    static Path resolve(Path given) {
        return resolve(given, Path.of("."));
    }

    static Path resolve(Path given, Path workingDir) {
        if (given != null) {
            return given;
        }
        List<Path> sources;
        try (Stream<Path> files = Files.list(workingDir)) {
            sources = files.filter(p -> p.getFileName().toString().endsWith(".sky"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + workingDir, e);
        }
        if (sources.size() == 1) {
            return sources.get(0);
        }
        if (sources.isEmpty()) {
            throw new ConfigException("no .sky file in this directory — name one explicitly");
        }
        throw new ConfigException("several .sky files here (" + sources.stream()
                .map(p -> p.getFileName().toString())
                .reduce((a, b) -> a + ", " + b).orElse("")
                + ") — name one explicitly");
    }
}
