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

import com.adeptum.skylang.Pipeline;
import com.adeptum.skylang.backend.Lowering;
import com.adeptum.skylang.backend.Profile;
import com.adeptum.skylang.config.ConfigException;
import com.adeptum.skylang.freeze.Hashing;
import com.adeptum.skylang.freeze.Lock;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.SkyParseException;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.types.CheckException;
import com.adeptum.skylang.types.TypeChecker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code sky why Service.method} — code comprehension as a query: the method's
 * specification, its freeze status, and the frozen body it was proven against.
 * Fully offline; never calls the model.
 */
@Command(name = "why", description = "Explain one method: its specification, freeze status, and frozen body.")
public final class WhyCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<file.sky>", description = "The SkyLang source file.")
    Path file;

    @Parameters(index = "1", paramLabel = "<Service.method>", description = "The method to explain.")
    String target;

    @Override
    public Integer call() {
        Ast.Module module;
        ActiveProfile.Activation active;
        try {
            module = Parsing.parseFile(file);
            new TypeChecker().check(module);
            active = ActiveProfile.activate(null, file, module);
        } catch (SkyParseException | CheckException | ConfigException e) {
            System.err.println("error [frontend]: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("error: cannot read " + file + ": " + e.getMessage());
            return 1;
        }

        String[] parts = target.split("\\.", 2);
        if (parts.length != 2) {
            System.err.println("error: name the method as Service.method");
            return 1;
        }
        Ast.Service service = module.services().stream()
                .filter(s -> s.name().equals(parts[0])).findFirst().orElse(null);
        Ast.Method method = service == null ? null : service.methods().stream()
                .filter(m -> m.name().equals(parts[1])).findFirst().orElse(null);
        if (method == null) {
            System.err.println("error: no method " + target + " in " + module.name());
            return 1;
        }

        print(module, service, method, active);
        return 0;
    }

    private void print(Ast.Module module, Ast.Service service, Ast.Method method,
                       ActiveProfile.Activation active) {
        String params = method.params().stream()
                .map(p -> p.name() + " " + p.type().sky())
                .collect(Collectors.joining(", "));
        System.out.println(service.name() + "." + method.name()
                + "(" + params + ") -> " + method.returnType().sky());
        if (!service.uses().isEmpty()) {
            System.out.println("  uses      " + String.join(", ", service.uses()));
        }
        method.intent().ifPresent(i -> System.out.println("  intent    \"" + i + "\""));
        method.requires().forEach(r -> System.out.println("  requires  " + Lowering.skyText(r)));
        method.ensures().forEach(e -> System.out.println("  ensures   " + Lowering.skyText(e)));
        for (Ast.Raise r : method.raises()) {
            System.out.println("  raises    " + r.error() + " when " + condition(r.condition()));
        }
        method.examples().forEach(ex -> System.out.println("  example   " + Lowering.skyText(ex.call())));
        method.specs().forEach(sp -> System.out.println("  spec      \"" + sp.title() + "\""));

        String key = module.name() + "." + service.name() + "." + method.name();
        String hash = Pipeline.methodSpecHash(module, method, active.profile(),
                active.deps().declared());
        Lock lock = Lock.load(file.toAbsolutePath().getParent().resolve("sky.lock"));
        var frozen = lock.get(key);
        if (frozen.isEmpty()) {
            System.out.println("  status    unfrozen — no verified body yet (run sky build)");
            return;
        }
        boolean current = frozen.get().specHash().equals(hash);
        System.out.println("  status    " + (current
                ? "frozen @ " + Hashing.shortHash(hash)
                : "stale — the specification changed since the freeze (run sky build)"));
        String origin = method.nativeBody().isPresent() ? " (native, hand-written)" : " (synthesized, verified)";
        System.out.println();
        System.out.println("  body" + origin + ":");
        frozen.get().body().lines().forEach(line -> System.out.println("    " + line));
    }

    private static String condition(Ast.RaiseCondition condition) {
        return switch (condition) {
            case Ast.CondExpr c -> Lowering.skyText(c.expr());
            case Ast.NoSuch ns -> "no " + ns.entityWord() + " has that " + ns.fieldWord();
            case Ast.AlreadyRegistered ar -> Lowering.skyText(ar.value()) + " already registered";
        };
    }
}
