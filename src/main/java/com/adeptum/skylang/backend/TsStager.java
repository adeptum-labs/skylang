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

package com.adeptum.skylang.backend;

import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.types.CheckException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The ts-node backend: materialise {@code build/ts-node/} as a conventional Node project —
 * entities as validated classes, services with the frozen bodies spliced in, and every
 * contract and example as a node:test test — then let tsc and node take it from there.
 * Same meanings as the JVM staging; TypeScript representations.
 */
public final class TsStager {

    private Map<String, Ast.Entity> stagedEntities = Map.of();

    public void stage(Ast.Module module, Map<String, String> bodies, Path dir) {
        stage(module, bodies, java.util.List.of(), dir);
    }

    public void stage(Ast.Module module, Map<String, String> bodies,
                      java.util.List<com.adeptum.skylang.deps.Resolved> deps, Path dir) {
        stagedEntities = module.entities().stream()
                .collect(Collectors.toMap(Ast.Entity::name, e -> e, (a, b) -> a, LinkedHashMap::new));
        try {
            Path src = dir.resolve("src");
            Files.createDirectories(src);
            write(dir.resolve("package.json"), packageJson(module, deps));
            write(dir.resolve("tsconfig.json"), tsconfig());
            write(src.resolve("node-shims.d.ts"), NODE_SHIMS);
            if (usesEffect(module, "db")) {
                write(src.resolve("Db.ts"), DB);
            }
            if (usesEffect(module, "clock")) {
                write(src.resolve("SkyClock.ts"), SKY_CLOCK);
            }
            Set<String> errors = errorEntities(module);
            if (!errors.isEmpty()) {
                write(src.resolve("errors.ts"), errors.stream()
                        .map(e -> "export class " + e + " extends Error {}")
                        .collect(Collectors.joining("\n", "", "\n")));
            }
            for (Ast.Entity e : module.entities()) {
                if (!errors.contains(e.name()) && !e.fields().isEmpty()) {
                    write(src.resolve(e.name() + ".ts"), entityClass(e));
                }
            }
            for (Ast.Service s : module.services()) {
                write(src.resolve(s.name() + ".ts"), serviceClass(module, s, bodies));
                write(src.resolve(s.name() + ".test.ts"), testFile(module, s, errors));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage " + dir, e);
        }
    }

    private static boolean usesEffect(Ast.Module module, String effect) {
        return module.services().stream().anyMatch(s -> s.uses().contains(effect));
    }

    /** Entities named by raises clauses or {@code -> raises} examples are error classes. */
    static Set<String> errorEntities(Ast.Module module) {
        Set<String> errors = new LinkedHashSet<>();
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                m.raises().forEach(r -> errors.add(r.error()));
                for (Ast.Example ex : m.examples()) {
                    if (ex.result() instanceof Ast.RaisesResult rr) {
                        errors.add(rr.error());
                    }
                }
            }
        }
        return errors;
    }

    // ---- entities -------------------------------------------------------------------------

    private String entityClass(Ast.Entity e) {
        StringBuilder sb = new StringBuilder();
        appendImports(sb, e.fields().stream().map(Ast.Field::type).toList(), e.name());
        sb.append("export class ").append(e.name()).append(" {\n");
        sb.append("  static readonly idField = \"").append(idField(e)).append("\";\n\n");
        sb.append("  constructor(\n");
        for (Ast.Field f : e.fields()) {
            sb.append("    readonly ").append(f.name()).append(": ").append(tsType(f.type())).append(",\n");
        }
        sb.append("  ) {\n");
        for (Ast.Field f : e.fields()) {
            f.min().ifPresent(min -> sb.append("    if (this.").append(f.name()).append(" < ").append(min)
                    .append("n) throw new RangeError(\"").append(e.name()).append('.').append(f.name())
                    .append(" must be >= ").append(min).append("\");\n"));
        }
        sb.append("  }\n}\n");
        return sb.toString();
    }

    static String idField(Ast.Entity e) {
        return e.fields().stream().filter(Ast.Field::id).map(Ast.Field::name)
                .findFirst().orElse(e.fields().isEmpty() ? "id" : e.fields().get(0).name());
    }

    // ---- services -------------------------------------------------------------------------

    private String serviceClass(Ast.Module module, Ast.Service s, Map<String, String> bodies) {
        StringBuilder sb = new StringBuilder();
        List<Ast.Type> referenced = s.methods().stream()
                .flatMap(m -> java.util.stream.Stream.concat(
                        m.params().stream().map(Ast.Param::type),
                        java.util.stream.Stream.of(m.returnType())))
                .toList();
        appendImports(sb, referenced, s.name());
        appendEffectImports(sb, s);
        Set<String> errors = errorEntities(module);
        Set<String> raised = s.methods().stream()
                .flatMap(m -> m.raises().stream().map(Ast.Raise::error))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        raised.retainAll(errors);
        if (!raised.isEmpty()) {
            sb.append("import { ").append(String.join(", ", raised)).append(" } from \"./errors.js\";\n");
        }
        sb.append('\n').append("export class ").append(s.name()).append(" {\n");
        if (!s.uses().isEmpty()) {
            sb.append("  constructor(").append(effectParams(s)).append(") {}\n");
        }
        for (Ast.Method m : s.methods()) {
            String params = m.params().stream()
                    .map(p -> p.name() + ": " + tsType(p.type()))
                    .collect(Collectors.joining(", "));
            sb.append("\n  ").append(m.name()).append("(").append(params).append("): ")
                    .append(tsType(m.returnType())).append(" {\n");
            String body = bodies.getOrDefault(
                    ProjectStager.methodKey(module.name(), s.name(), m.name()),
                    "throw new Error(\"unimplemented\");");
            body.lines().forEach(line -> sb.append("    ").append(line).append('\n'));
            sb.append("  }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String effectParams(Ast.Service s) {
        return s.uses().stream().map(effect -> switch (effect) {
            case "db" -> "private readonly db: Db";
            case "clock" -> "private readonly clock: SkyClock";
            default -> throw new CheckException("the ts-node profile does not bind the '"
                    + effect + "' effect yet");
        }).collect(Collectors.joining(", "));
    }

    private static void appendEffectImports(StringBuilder sb, Ast.Service s) {
        if (s.uses().contains("db")) {
            sb.append("import { Db } from \"./Db.js\";\n");
        }
        if (s.uses().contains("clock")) {
            sb.append("import { SkyClock } from \"./SkyClock.js\";\n");
        }
    }

    private void appendImports(StringBuilder sb, List<Ast.Type> types, String self) {
        Set<String> entities = new LinkedHashSet<>();
        for (Ast.Type t : types) {
            if (t instanceof Ast.TypeRef ref && !isPrimitive(ref.name()) && !ref.name().equals(self)) {
                entities.add(ref.name());
            }
        }
        entities.forEach(e -> sb.append("import { ").append(e).append(" } from \"./").append(e)
                .append(".js\";\n"));
    }

    // ---- tests ----------------------------------------------------------------------------

    private String testFile(Ast.Module module, Ast.Service s, Set<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("import { test } from \"node:test\";\n");
        sb.append("import assert from \"node:assert/strict\";\n");
        sb.append("import { ").append(s.name()).append(" } from \"./").append(s.name()).append(".js\";\n");
        Set<String> imported = new LinkedHashSet<>();
        for (Ast.Method m : s.methods()) {
            for (Ast.Param p : m.params()) {
                if (p.type() instanceof Ast.TypeRef ref && !isPrimitive(ref.name())
                        && !errors.contains(ref.name())) {
                    imported.add(ref.name());
                }
            }
        }
        imported.forEach(e -> sb.append("import { ").append(e).append(" } from \"./").append(e)
                .append(".js\";\n"));
        Set<String> raisedInExamples = new LinkedHashSet<>();
        for (Ast.Method m : s.methods()) {
            for (Ast.Example ex : m.examples()) {
                if (ex.result() instanceof Ast.RaisesResult rr) {
                    raisedInExamples.add(rr.error());
                }
            }
        }
        if (!raisedInExamples.isEmpty()) {
            sb.append("import { ").append(String.join(", ", raisedInExamples))
                    .append(" } from \"./errors.js\";\n");
        }
        appendEffectImports(sb, s);
        for (Ast.Method m : s.methods()) {
            int n = 0;
            for (Ast.Example ex : m.examples()) {
                sb.append(exampleTest(module, s, m, ex, ++n));
            }
            int g = 0;
            for (Ast.Expr req : m.requires()) {
                Optional<String> witness = violatingWitness(req, m);
                if (witness.isPresent()) {
                    sb.append(requiresTest(s, m, req, witness.get(), ++g));
                }
            }
        }
        return sb.toString();
    }

    private static String construction(Ast.Service s) {
        String args = s.uses().stream().map(effect -> switch (effect) {
            case "db" -> "new Db()";
            case "clock" -> "new SkyClock(0n)";
            default -> throw new CheckException("the ts-node profile does not bind the '"
                    + effect + "' effect yet");
        }).collect(Collectors.joining(", "));
        return "new " + s.name() + "(" + args + ")";
    }

    private String exampleTest(Ast.Module module, Ast.Service s, Ast.Method m, Ast.Example ex, int n) {
        if (ex.seed().isPresent()) {
            throw new CheckException(s.name() + "." + m.name()
                    + ": example seeding (on a ...) is not yet supported by the ts-node profile");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\ntest(\"").append(m.name()).append(" example ").append(n).append("\", () => {\n");
        sb.append("  const svc = ").append(construction(s)).append(";\n");
        Map<String, String> env = new LinkedHashMap<>();
        for (int i = 0; i < m.params().size(); i++) {
            String name = m.params().get(i).name();
            sb.append("  const ").append(name).append(" = ")
                    .append(tsExpr(ex.call().args().get(i), Map.of())).append(";\n");
            env.put(name, name);
        }
        String callArgs = m.params().stream().map(Ast.Param::name).collect(Collectors.joining(", "));
        if (ex.result() instanceof Ast.RaisesResult rr) {
            sb.append("  assert.throws(() => svc.").append(m.name()).append("(").append(callArgs)
                    .append("), ").append(rr.error())
                    .append(", \"example: raises ").append(rr.error()).append("\");\n");
            sb.append("});\n");
            return sb.toString();
        }
        env.put("result", "result");
        sb.append("  const result = svc.").append(m.name()).append("(").append(callArgs).append(");\n");
        for (Ast.Expr ens : m.ensures()) {
            sb.append("  assert.ok(").append(tsExpr(ens, env)).append(", ")
                    .append(quote("ensures: " + Lowering.skyText(ens))).append(");\n");
        }
        switch (ex.result()) {
            case Ast.ExprResult er -> sb.append("  assert.equal(result, ")
                    .append(tsExpr(er.value(), env)).append(", \"example result\");\n");
            case Ast.EntityResult ent -> appendFieldAsserts(sb, ent.fields());
            case Ast.FieldsResult fr -> appendFieldAsserts(sb, fr.fields());
            case Ast.RaisesResult rr -> throw new IllegalStateException("raises returned above");
            case Ast.NothingResult ignored -> throw new CheckException(
                    "Maybe results are not yet supported by the ts-node profile");
            case Ast.WhoseResult ignored -> throw new CheckException(
                    "whose-results are not yet supported by the ts-node profile");
        }
        sb.append("});\n");
        return sb.toString();
    }

    private void appendFieldAsserts(StringBuilder sb, List<Ast.FieldExpect> fields) {
        for (Ast.FieldExpect fe : fields) {
            sb.append("  assert.equal(result.").append(fe.field()).append(", ")
                    .append(tsExpr(fe.expected(), Map.of()))
                    .append(", \"example: ").append(fe.field()).append("\");\n");
        }
    }

    private String requiresTest(Ast.Service s, Ast.Method m, Ast.Expr req, String witness, int g) {
        StringBuilder sb = new StringBuilder();
        sb.append("\ntest(\"").append(m.name()).append(" requires ").append(g).append("\", () => {\n");
        sb.append("  const svc = ").append(construction(s)).append(";\n");
        sb.append("  assert.throws(() => svc.").append(m.name()).append("(").append(witness)
                .append("), RangeError, ").append(quote("requires: " + Lowering.skyText(req)))
                .append(");\n");
        sb.append("});\n");
        return sb.toString();
    }

    /**
     * Arguments that violate a {@code requires} of the shape {@code param <cmp> <int>}: the
     * offending parameter takes the boundary value, every other parameter a type-derived
     * sample. Contracts outside that shape simply get no generated precondition test.
     */
    private Optional<String> violatingWitness(Ast.Expr req, Ast.Method m) {
        if (!(req instanceof Ast.BinExpr be && be.left() instanceof Ast.NameExpr name
                && be.right() instanceof Ast.IntLit lit)) {
            return Optional.empty();
        }
        long bad = switch (be.op()) {
            case ">" -> lit.value();
            case ">=" -> lit.value() - 1;
            case "<" -> lit.value();
            case "<=" -> lit.value() + 1;
            case "==" -> lit.value() + 1;
            case "!=" -> lit.value();
            default -> Long.MIN_VALUE;
        };
        if (bad == Long.MIN_VALUE
                || m.params().stream().noneMatch(p -> p.name().equals(name.name()))) {
            return Optional.empty();
        }
        return Optional.of(m.params().stream()
                .map(p -> p.name().equals(name.name()) ? bad + "n" : sampleValue(p.type()))
                .collect(Collectors.joining(", ")));
    }

    private String sampleValue(Ast.Type type) {
        if (type instanceof Ast.TypeRef ref) {
            if (ref.list()) {
                return "[]";
            }
            return switch (ref.name()) {
                case "Int" -> "1n";
                case "Text" -> "\"x\"";
                case "Bool" -> "true";
                default -> "new " + ref.name() + "(" + sampleFields(ref.name()) + ")";
            };
        }
        throw unsupportedType(type);
    }

    private String sampleFields(String entityName) {
        Ast.Entity entity = stagedEntities.get(entityName);
        if (entity == null) {
            throw new CheckException("cannot derive a sample " + entityName
                    + " for a generated test under the ts-node profile");
        }
        return entity.fields().stream()
                .map(f -> f.min().isPresent() ? Math.max(f.min().getAsLong(), 1) + "n"
                        : sampleValue(f.type()))
                .collect(Collectors.joining(", "));
    }

    // ---- lowering -------------------------------------------------------------------------

    static boolean isPrimitive(String name) {
        return name.equals("Int") || name.equals("Text") || name.equals("Bool");
    }

    public static String tsType(Ast.Type type) {
        if (type instanceof Ast.TypeRef ref) {
            String base = switch (ref.name()) {
                case "Int" -> "bigint";
                case "Text" -> "string";
                case "Bool" -> "boolean";
                default -> ref.name();
            };
            return ref.list() ? base + "[]" : base;
        }
        throw unsupportedType(type);
    }

    static CheckException unsupportedType(Ast.Type type) {
        return new CheckException("type " + type.sky()
                + " is not yet supported by the ts-node profile");
    }

    /** The expression subset the ts-node profile lowers into generated tests. */
    static String tsExpr(Ast.Expr e, Map<String, String> env) {
        return switch (e) {
            case Ast.IntLit i -> i.value() + "n";
            case Ast.StrLit s -> quote(s.value());
            case Ast.BoolLit b -> Boolean.toString(b.value());
            case Ast.NameExpr n -> env.getOrDefault(n.name(), n.name());
            case Ast.MemberExpr me -> tsExpr(me.target(), env) + "." + me.field();
            case Ast.CallExpr ce -> "new " + ce.callee() + "(" + ce.args().stream()
                    .map(a -> tsExpr(a, env)).collect(Collectors.joining(", ")) + ")";
            case Ast.NotExpr ne -> "!(" + tsExpr(ne.value(), env) + ")";
            case Ast.BinExpr be -> "(" + tsExpr(be.left(), env) + " " + tsOp(be.op()) + " "
                    + tsExpr(be.right(), env) + ")";
            default -> throw new CheckException("the expression '" + Lowering.skyText(e)
                    + "' is not yet supported by the ts-node profile");
        };
    }

    private static String tsOp(String op) {
        return switch (op) {
            case "==" -> "===";
            case "!=" -> "!==";
            case "and" -> "&&";
            case "or" -> "||";
            default -> op;
        };
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    // ---- fixed project files --------------------------------------------------------------

    private String packageJson(Ast.Module module, java.util.List<com.adeptum.skylang.deps.Resolved> deps) {
        String dependencies = deps.isEmpty() ? "" : deps.stream()
                .flatMap(r -> r.coordinates().stream())
                .map(c -> {
                    int cut = c.lastIndexOf(':');
                    return "    \"" + c.substring(0, cut) + "\": \"" + c.substring(cut + 1) + "\"";
                })
                .collect(Collectors.joining(",\n", ",\n  \"dependencies\": {\n", "\n  }"));
        return """
                {
                  "name": "%s",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "test": "tsc && node --test dist/"
                  }%s
                }
                """.formatted(module.name(), dependencies);
    }

    private String tsconfig() {
        return """
                {
                  "compilerOptions": {
                    "strict": true,
                    "target": "es2022",
                    "module": "nodenext",
                    "moduleResolution": "nodenext",
                    "outDir": "dist",
                    "rootDir": "src",
                    "skipLibCheck": true
                  },
                  "include": ["src"]
                }
                """;
    }

    /** Just enough ambient typing for node:test and node:assert — no npm install needed. */
    private static final String NODE_SHIMS = """
            declare module "node:test" {
              export function test(name: string, fn: () => unknown): void;
            }
            declare module "node:assert/strict" {
              interface Assert {
                ok(value: unknown, message?: string): void;
                equal(actual: unknown, expected: unknown, message?: string): void;
                throws(fn: () => unknown, expected?: unknown, message?: string): void;
              }
              const assert: Assert;
              export default assert;
            }
            """;

    private static final String DB = """
            type EntityCtor<T> = (new (...args: never[]) => T) & { idField: string };

            /** The db effect's binding: an in-memory store keyed by each entity's id field. */
            export class Db {
              private readonly tables = new Map<string, Map<string, unknown>>();

              save<T extends object>(entity: T): T {
                const ctor = entity.constructor as EntityCtor<T>;
                this.table(ctor.name).set(this.key(entity, ctor), entity);
                return entity;
              }

              find<T>(ctor: EntityCtor<T>, id: unknown): T | undefined {
                return this.table(ctor.name).get(String(id)) as T | undefined;
              }

              all<T>(ctor: EntityCtor<T>): T[] {
                return [...this.table(ctor.name).values()] as T[];
              }

              private key<T extends object>(entity: T, ctor: EntityCtor<T>): string {
                return String((entity as Record<string, unknown>)[ctor.idField]);
              }

              private table(name: string): Map<string, unknown> {
                let t = this.tables.get(name);
                if (t === undefined) {
                  t = new Map<string, unknown>();
                  this.tables.set(name, t);
                }
                return t;
              }
            }
            """;

    private static final String SKY_CLOCK = """
            /** The clock effect's binding: epoch milliseconds, pinnable for tests. */
            export class SkyClock {
              constructor(private readonly fixed?: bigint) {}

              instant(): bigint {
                return this.fixed ?? BigInt(Date.now());
              }
            }
            """;
}
