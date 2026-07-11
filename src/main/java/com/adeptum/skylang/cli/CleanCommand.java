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
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code sky clean} — delete the disposable staged project. The lock is untouched, so the
 * next build re-materialises the directory deterministically without regenerating anything.
 */
@Command(name = "clean", description = "Delete the build directory; sky.lock is preserved.")
public final class CleanCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<file.sky>",
            description = "The SkyLang source file whose project to clean. "
                    + "Default: the directory's sole .sky file.")
    Path file;

    @Override
    public Integer call() {
        try {
            file = SourceFiles.resolve(file);
        } catch (ConfigException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
        Path build = file.toAbsolutePath().getParent().resolve("build");
        if (!Files.isDirectory(build)) {
            System.out.println("  nothing to remove (no build directory)");
            return 0;
        }
        try (Stream<Path> targets = Files.list(build)) {
            for (Path target : targets.sorted().toList()) {
                deleteRecursively(target);
                System.out.println("  removed build/" + target.getFileName());
            }
            Files.deleteIfExists(build);
        } catch (IOException e) {
            System.err.println("error: cannot remove " + build + ": " + e.getMessage());
            return 1;
        }
        System.out.println("  (sky.lock preserved; next build re-materializes the project)");
        return 0;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
