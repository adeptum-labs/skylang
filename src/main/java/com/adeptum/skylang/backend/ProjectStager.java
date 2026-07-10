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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Materializes {@code build/&lt;profile&gt;/} as a conventional Maven project (guide §12):
 * entities lowered to records, services with the resolved method bodies spliced in, and each
 * {@code example}/{@code ensures} emitted as an ordinary JUnit test. The directory is a
 * disposable artifact, regenerated from the sources and the resolved bodies.
 */
public final class ProjectStager {

    /** The canonical key for a method across the lock and the body map. */
    public static String methodKey(String module, String service, String method) {
        return module + "." + service + "." + method;
    }

    /** The canonical key for a view across the lock. */
    public static String viewKey(String module, String view) {
        return module + "." + view;
    }

    /**
     * @param bodies map from {@link #methodKey} to the Java statements for that method body
     */
    public void stage(Ast.Module module, Map<String, String> bodies, Path buildDir) {
        String pkg = module.name();
        Path main = buildDir.resolve("src/main/java").resolve(pkg);
        Path test = buildDir.resolve("src/test/java").resolve(pkg);
        Map<String, Ast.TypeDecl> types = Lowering.typesOf(module);
        try {
            Files.createDirectories(main);
            Files.createDirectories(test);
            boolean db = SupportClasses.effectsOf(module).contains("db");
            Files.writeString(buildDir.resolve("pom.xml"), module.views().isEmpty() ? pom(db) : webPom(db));

            for (String support : SupportClasses.used(module)) {
                Files.writeString(main.resolve(support + ".java"),
                        SupportClasses.source(support, pkg).orElseThrow());
            }
            java.util.Set<String> effects = SupportClasses.effectsOf(module);
            boolean usesNow = SupportClasses.usesNow(module);
            if (effects.contains("mail")) {
                Files.writeString(main.resolve("Mail.java"), SupportClasses.mail(pkg));
            }
            if (effects.contains("http")) {
                Files.writeString(main.resolve("Http.java"), SupportClasses.http(pkg));
                Files.writeString(main.resolve("JdkHttp.java"), SupportClasses.jdkHttp(pkg));
            }
            if (usesNow) {
                Files.writeString(main.resolve("SkyClock.java"), SupportClasses.skyClock(pkg));
            }
            if (effects.contains("db")) {
                new JpaStager().stage(module, buildDir);
            }
            if (!module.views().isEmpty() && !effects.isEmpty()) {
                Files.writeString(main.resolve("Effects.java"), SupportClasses.effectProducers(pkg, effects));
            }
            if (!effects.isEmpty() || usesNow) {
                Files.writeString(test.resolve("TestEffects.java"),
                        SupportClasses.testEffects(pkg, effects, usesNow));
            }
            boolean web = !module.views().isEmpty();
            java.util.Set<String> values = Lowering.valueEntities(module);
            java.util.Set<String> errors = errorEntities(module);
            for (Ast.Entity e : module.entities()) {
                Files.writeString(main.resolve(e.name() + ".java"), errors.contains(e.name())
                        ? errorSource(pkg, e, types)
                        : entitySource(pkg, e, web, types, values, module));
            }
            for (Ast.Service s : module.services()) {
                Files.writeString(main.resolve(s.name() + ".java"), serviceSource(pkg, module, s, bodies, types));
                Files.writeString(test.resolve(s.name() + "Test.java"), testSource(pkg, module, s, values));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage project under " + buildDir, e);
        }
    }

    // ----- entity ------------------------------------------------------------

    private String entitySource(String pkg, Ast.Entity entity, boolean web, Map<String, Ast.TypeDecl> types,
                                java.util.Set<String> values, Ast.Module module) {
        String components = entity.fields().stream()
                .map(f -> Lowering.javaType(f.type(), types) + " " + f.name())
                .collect(Collectors.joining(", "));

        StringBuilder checks = new StringBuilder();
        for (Ast.Field f : entity.fields()) {
            if (f.min().isPresent()) {
                checks.append("        if (").append(f.name()).append(" < ").append(f.min().getAsLong())
                        .append("L) throw new IllegalArgumentException(\"")
                        .append(entity.name()).append('.').append(f.name())
                        .append(" violates @min(").append(f.min().getAsLong()).append(")\");\n");
            }
            String refined = Lowering.javaCheck(f.name(), f.type(), types,
                    entity.name() + "." + f.name());
            if (!refined.isEmpty()) {
                checks.append(indent(refined.strip(), "        ")).append('\n');
            }
            String policy = Lowering.policyChecks(f.name(), f.type(), module);
            if (!policy.isEmpty()) {
                checks.append(indent(policy.strip(), "        ")).append('\n');
            }
        }

        String compact = checks.isEmpty() ? "" :
                "\n    public " + entity.name() + " {\n" + checks + "    }\n";

        // Faces EL reads properties through getX() getters, which records lack; add them for the
        // web profile. Secret fields get none: nothing in a page may reach a secret.
        StringBuilder getters = new StringBuilder();
        if (web) {
            for (Ast.Field f : entity.fields()) {
                if (isSecret(f.type())) {
                    continue;
                }
                String cap = Character.toUpperCase(f.name().charAt(0)) + f.name().substring(1);
                getters.append("\n    public ").append(Lowering.javaType(f.type(), types)).append(" get").append(cap)
                        .append("() {\n        return ").append(f.name()).append(";\n    }\n");
            }
        }

        return "package " + pkg + ";\n\n"
                + entityJavadoc(entity)
                + "public record " + entity.name() + "(" + components + ") {\n"
                + compact
                + valueConstants(entity)
                + identityEquality(entity, types)
                + defaultsConstructor(entity, types, values)
                + getters
                + "}\n";
    }

    /** {@code values Member, Admin} lowers to one constant per value; the checker closes the set. */
    private static String valueConstants(Ast.Entity entity) {
        StringBuilder sb = new StringBuilder(entity.values().isEmpty() ? "" : "\n");
        for (String v : entity.values()) {
            sb.append("    public static final ").append(entity.name()).append(' ').append(v)
                    .append(" = new ").append(entity.name()).append("(\"").append(v).append("\");\n");
        }
        return sb.toString();
    }

    /** An @id entity compares by its identity; a value entity keeps record content equality. */
    private static String identityEquality(Ast.Entity entity, Map<String, Ast.TypeDecl> types) {
        var idField = entity.fields().stream().filter(Ast.Field::id).findFirst();
        if (idField.isEmpty() || entity.fields().size() == 1) {
            return "";
        }
        String name = idField.get().name();
        String javaType = Lowering.javaType(idField.get().type(), types);
        boolean primitive = javaType.equals("long") || javaType.equals("boolean");
        String compare = primitive ? name + " == other." + name
                : "java.util.Objects.equals(" + name + ", other." + name + ")";
        String hash = switch (javaType) {
            case "long" -> "Long.hashCode(" + name + ")";
            case "boolean" -> "Boolean.hashCode(" + name + ")";
            default -> "java.util.Objects.hashCode(" + name + ")";
        };
        return """

                    @Override
                    public boolean equals(Object o) {
                        return o instanceof %s other && %s;
                    }

                    @Override
                    public int hashCode() {
                        return %s;
                    }
                """.formatted(entity.name(), compare, hash);
    }

    private static boolean isSecret(Ast.Type type) {
        return type instanceof Ast.GenericType g && g.name().equals("Secret");
    }

    /** The entities named as failures — by raises clauses, spec thens, example results, or policies. */
    public static java.util.Set<String> errorEntities(Ast.Module module) {
        java.util.Set<String> errors = new java.util.LinkedHashSet<>();
        for (Ast.Policy p : module.policies()) {
            if (p.rule() instanceof Ast.RequireRule rr) {
                rr.raise().ifPresent(errors::add);
            }
        }
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                m.raises().forEach(r -> errors.add(r.error()));
                m.examples().forEach(ex -> {
                    if (ex.result() instanceof Ast.RaisesResult rr) {
                        errors.add(rr.error());
                    }
                });
                m.specs().forEach(spec -> spec.then().forEach(t -> {
                    if (t instanceof Ast.ThenRaises tr) {
                        errors.add(tr.error());
                    }
                }));
            }
        }
        return errors;
    }

    /** An error entity: an exception carrying the context fields the caller needs. */
    private String errorSource(String pkg, Ast.Entity entity, Map<String, Ast.TypeDecl> types) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/** Raised as declared by the raises contracts naming it. */\n");
        sb.append("public class ").append(entity.name()).append(" extends RuntimeException {\n");
        for (Ast.Field f : entity.fields()) {
            sb.append("\n    private final ").append(Lowering.javaType(f.type(), types))
                    .append(' ').append(f.name()).append(";\n");
        }
        String params = entity.fields().stream()
                .map(f -> Lowering.javaType(f.type(), types) + " " + f.name())
                .collect(Collectors.joining(", "));
        String message = entity.fields().isEmpty()
                ? "\"" + entity.name() + "\""
                : "\"" + entity.name() + ": \" + " + entity.fields().stream()
                        .map(f -> f.name()).collect(Collectors.joining(" + \", \" + "));
        sb.append("\n    public ").append(entity.name()).append("(").append(params).append(") {\n");
        sb.append("        super(").append(message).append(");\n");
        for (Ast.Field f : entity.fields()) {
            sb.append("        this.").append(f.name()).append(" = ").append(f.name()).append(";\n");
        }
        sb.append("    }\n");
        for (Ast.Field f : entity.fields()) {
            sb.append("\n    public ").append(Lowering.javaType(f.type(), types)).append(' ')
                    .append(f.name()).append("() {\n        return ").append(f.name()).append(";\n    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String entityJavadoc(Ast.Entity entity) {
        String unique = entity.fields().stream()
                .filter(Ast.Field::unique)
                .map(Ast.Field::name)
                .collect(Collectors.joining(", "));
        return unique.isEmpty() ? "" :
                "/** @unique (advisory until a persistence layer enforces it): " + unique + ". */\n";
    }

    /** A constructor omitting the trailing run of defaulted fields, so callers may skip them. */
    private String defaultsConstructor(Ast.Entity entity, Map<String, Ast.TypeDecl> types,
                                       java.util.Set<String> values) {
        List<Ast.Field> fields = entity.fields();
        int first = fields.size();
        while (first > 0 && fields.get(first - 1).defaultValue().isPresent()) {
            first--;
        }
        if (first == fields.size()) {
            return "";
        }
        String params = fields.subList(0, first).stream()
                .map(f -> Lowering.javaType(f.type(), types) + " " + f.name())
                .collect(Collectors.joining(", "));
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            args.append(i > 0 ? ", " : "").append(i < first
                    ? fields.get(i).name()
                    : defaultValue(fields.get(i).defaultValue().orElseThrow(), values));
        }
        return "\n    public " + entity.name() + "(" + params + ") {\n"
                + "        this(" + args + ");\n    }\n";
    }

    private static String defaultValue(Ast.Expr value, java.util.Set<String> values) {
        if (value instanceof Ast.NameExpr n && n.name().equals("now")) {
            return "SkyClock.now()";
        }
        return Lowering.javaValue(value, values);
    }

    // ----- service -----------------------------------------------------------

    private String serviceSource(String pkg, Ast.Module module, Ast.Service service, Map<String, String> bodies,
                                 Map<String, Ast.TypeDecl> types) {
        boolean web = !module.views().isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        if (web) {
            // A view's backing bean injects the service, so under the web profile it is a CDI bean.
            sb.append("@jakarta.enterprise.context.ApplicationScoped\n");
            sb.append("public class ").append(service.name()).append(" {\n");
        } else {
            sb.append("public final class ").append(service.name()).append(" {\n");
        }
        sb.append(effectHandles(service, web));
        for (Ast.Method m : service.methods()) {
            String key = methodKey(module.name(), service.name(), m.name());
            String body = bodies.get(key);
            if (body == null) {
                throw new IllegalStateException("no resolved body for " + key);
            }
            // A native body may use checked platform APIs without changing the signature
            // its callers see; anything checked resurfaces unchecked.
            if (m.nativeBody().isPresent()) {
                body = "try {\n" + indent(body.strip(), "    ")
                        + "\n} catch (RuntimeException e) {\n    throw e;\n"
                        + "} catch (Exception e) {\n    throw new IllegalStateException(e);\n}";
            }
            sb.append("\n    public ").append(signature(m, types)).append(" {\n");
            // Refined parameters are guarded before the (synthesized) body runs, so no body
            // can be reached with a value that breaks the parameter's declared predicate.
            for (Ast.Param p : m.params()) {
                String guard = Lowering.javaCheck(p.name(), p.type(), types,
                        service.name() + "." + m.name() + " parameter " + p.name());
                if (!guard.isEmpty()) {
                    sb.append(indent(guard.strip(), "        ")).append('\n');
                }
                String policy = Lowering.policyChecks(p.name(), p.type(), module);
                if (!policy.isEmpty()) {
                    sb.append(indent(policy.strip(), "        ")).append('\n');
                }
            }
            // A requires clause is the caller's obligation, enforced as a guard.
            java.util.Set<String> values = Lowering.valueEntities(module);
            for (Ast.Expr req : m.requires()) {
                sb.append("        if (!(").append(Lowering.exprToJava(req, Map.of(), values))
                        .append(")) throw new IllegalArgumentException(")
                        .append(Lowering.javaString("requires: " + Lowering.skyText(req))).append(");\n");
            }
            sb.append(indent(body.strip(), "        ")).append('\n');
            sb.append("    }\n");
        }
        boolean guarded = service.methods().stream().anyMatch(m -> !m.requires().isEmpty());
        if (guarded) {
            java.util.Set<String> guardSupport = SupportClasses.used(module);
            sb.append(opHelpers(guardSupport.contains("Money"), guardSupport.contains("Bytes")));
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** The Java type each declared effect binds to under the JVM profile. */
    private static final Map<String, String> EFFECT_TYPES = Map.of(
            "db", "Db", "clock", "java.time.Clock", "mail", "Mail", "http", "Http");

    /**
     * The service's effects budget as injected handles: one final field per declared effect,
     * arriving through the constructor. An undeclared effect has no handle, so no body can
     * reach it — the budget is enforced by what exists. Under the web profile the constructor
     * is the CDI injection point, plus the no-arg constructor a normal-scoped proxy needs.
     */
    private static String effectHandles(Ast.Service service, boolean web) {
        if (service.uses().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n");
        for (String effect : service.uses()) {
            sb.append("    private final ").append(EFFECT_TYPES.get(effect)).append(' ')
                    .append(effect).append(";\n");
        }
        String params = service.uses().stream()
                .map(e -> EFFECT_TYPES.get(e) + " " + e)
                .collect(Collectors.joining(", "));
        if (web) {
            sb.append("\n    @jakarta.inject.Inject");
        }
        sb.append("\n    public ").append(service.name()).append("(").append(params).append(") {\n");
        for (String effect : service.uses()) {
            sb.append("        this.").append(effect).append(" = ").append(effect).append(";\n");
        }
        sb.append("    }\n");
        if (web) {
            String nulls = service.uses().stream().map(e -> "null").collect(Collectors.joining(", "));
            sb.append("\n    protected ").append(service.name()).append("() {\n");
            sb.append("        this(").append(nulls).append(");\n    }\n");
        }
        return sb.toString();
    }

    private String signature(Ast.Method m, Map<String, Ast.TypeDecl> types) {
        String params = m.params().stream()
                .map(p -> Lowering.javaType(p.type(), types) + " " + p.name())
                .collect(Collectors.joining(", "));
        return Lowering.javaType(m.returnType(), types) + " " + m.name() + "(" + params + ")";
    }

    // ----- tests -------------------------------------------------------------

    private String testSource(String pkg, Ast.Module module, Ast.Service service, java.util.Set<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n\n");
        sb.append("public class ").append(service.name()).append("Test {\n");

        Map<String, Ast.TypeDecl> types = Lowering.typesOf(module);
        for (Ast.Method m : service.methods()) {
            int n = 0;
            for (Ast.Example ex : m.examples()) {
                sb.append(testMethod(service, m, ex, ++n, module));
            }
            int sp = 0;
            for (Ast.Spec spec : m.specs()) {
                sb.append(specTest(service, m, spec, ++sp, module, types));
            }
            int k = 0;
            for (Ast.Raise r : m.raises()) {
                sb.append(raiseTest(service, m, r, ++k, module, types));
            }
            int g = 0;
            for (Ast.Expr req : m.requires()) {
                sb.append(requiresTest(service, m, req, ++g, module, types));
            }
        }
        java.util.Set<String> support = SupportClasses.used(module);
        sb.append(opHelpers(support.contains("Money"), support.contains("Bytes")));
        sb.append("}\n");
        return sb.toString();
    }

    /** The effect wiring for one test: a fresh store kept in a local so the test can seed it. */
    private static String construction(Ast.Service service) {
        StringBuilder sb = new StringBuilder();
        if (service.uses().contains("db")) {
            sb.append("        Db db = TestEffects.db();\n");
        }
        String wiring = service.uses().stream()
                .map(e -> e.equals("db") ? "db" : "TestEffects." + e + "()")
                .collect(Collectors.joining(", "));
        sb.append("        ").append(service.name()).append(" svc = new ").append(service.name())
                .append("(").append(wiring).append(");\n");
        return sb.toString();
    }

    /** Default arguments for a witness test, or null when one of them is underivable. */
    private static Map<String, String> witnessArgs(Ast.Method m, Ast.Module module,
                                                   Map<String, Ast.TypeDecl> types) {
        Map<String, String> args = new LinkedHashMap<>();
        for (Ast.Param p : m.params()) {
            String value = Lowering.defaultJavaValue(p.type(), types, module);
            if (value == null) {
                return null;
            }
            args.put(p.name(), value);
        }
        return args;
    }

    /**
     * A raises contract becomes a test with a derivable witness: an empty store for the
     * existence phrase, a seeded duplicate for the uniqueness phrase, a boundary value for a
     * simple comparison. An underivable condition generates no test and stays prompt-only.
     */
    private String raiseTest(Ast.Service service, Ast.Method m, Ast.Raise r, int k,
                             Ast.Module module, Map<String, Ast.TypeDecl> types) {
        Map<String, String> args = witnessArgs(m, module, types);
        if (args == null) {
            return "";
        }
        String seed = "";
        String condition;
        switch (r.condition()) {
            case Ast.NoSuch ns -> condition = "no " + ns.entityWord() + " has that " + ns.fieldWord();
            case Ast.AlreadyRegistered ar -> {
                String param = ((Ast.NameExpr) ar.value()).name();
                String seeded = seedFor(param, module, types);
                if (seeded == null) {
                    return "";
                }
                seed = "        db.save(" + seeded + ");\n";
                condition = param + " already registered";
            }
            case Ast.CondExpr ce -> {
                if (!m.requires().isEmpty()) {
                    return "";   // a witness could break the requires guard first
                }
                String witness = comparisonWitness(ce.expr(), false);
                if (witness == null) {
                    return "";
                }
                args.put(((Ast.NameExpr) ((Ast.BinExpr) ce.expr()).left()).name(), witness);
                condition = Lowering.skyText(ce.expr());
            }
            default -> {
                return "";
            }
        }
        return "\n    @Test\n    void " + m.name() + "_raises_" + r.error() + "_" + k + "() throws Exception {\n"
                + construction(service) + seed
                + "        assertThrows(" + r.error() + ".class, () -> svc." + m.name() + "("
                + String.join(", ", args.values()) + "), "
                + Lowering.javaString("raises " + r.error() + " when " + condition) + ");\n    }\n";
    }

    /** A requires guard gets a boundary test: a violating input must be turned away. */
    private String requiresTest(Ast.Service service, Ast.Method m, Ast.Expr req, int g,
                                Ast.Module module, Map<String, Ast.TypeDecl> types) {
        Map<String, String> args = witnessArgs(m, module, types);
        String witness = args == null ? null : comparisonWitness(req, true);
        if (witness == null) {
            return "";
        }
        args.put(((Ast.NameExpr) ((Ast.BinExpr) req).left()).name(), witness);
        return "\n    @Test\n    void " + m.name() + "_requires_" + g + "() throws Exception {\n"
                + construction(service)
                + "        assertThrows(IllegalArgumentException.class, () -> svc." + m.name() + "("
                + String.join(", ", args.values()) + "), "
                + Lowering.javaString("requires: " + Lowering.skyText(req)) + ");\n    }\n";
    }

    /**
     * A boundary value for {@code param <op> literal}: satisfying the comparison for a raises
     * witness, violating it for a requires witness. Anything more complex returns null.
     */
    private static String comparisonWitness(Ast.Expr expr, boolean violate) {
        if (!(expr instanceof Ast.BinExpr be) || !(be.left() instanceof Ast.NameExpr)
                || !(be.right() instanceof Ast.IntLit lit)) {
            return null;
        }
        String op = violate ? negate(be.op()) : be.op();
        Long value = switch (op) {
            case "<" -> lit.value() - 1;
            case "<=", ">=", "==" -> lit.value();
            case ">", "!=" -> lit.value() + 1;
            default -> null;
        };
        return value == null ? null : value + "L";
    }

    private static String negate(String op) {
        return switch (op) {
            case "<" -> ">=";
            case "<=" -> ">";
            case ">" -> "<=";
            case ">=" -> "<";
            case "==" -> "!=";
            case "!=" -> "==";
            default -> op;
        };
    }

    /** A stored row whose @unique field equals the witness argument of the same name. */
    private static String seedFor(String param, Ast.Module module, Map<String, Ast.TypeDecl> types) {
        for (Ast.Entity e : module.entities()) {
            boolean unique = e.fields().stream().anyMatch(f -> f.unique() && f.name().equals(param));
            boolean root = e.fields().stream().anyMatch(Ast.Field::id);
            if (unique && root) {
                return Lowering.defaultJavaValue(new Ast.TypeRef(e.name()), types, module);
            }
        }
        return null;
    }

    /**
     * The helper methods contract expressions lower their operators to; javac's overload
     * resolution gives each lowered type its correct semantics (primitive comparison for
     * long, value equality for objects, currency-safe arithmetic for Money).
     */
    private static String opHelpers(boolean money, boolean bytes) {
        String base = """

                    private static boolean eq(long a, long b) { return a == b; }
                    private static boolean eq(boolean a, boolean b) { return a == b; }
                    private static boolean eq(Object a, Object b) { return java.util.Objects.equals(a, b); }
                    private static boolean lt(long a, long b) { return a < b; }
                    private static boolean le(long a, long b) { return a <= b; }
                    private static boolean gt(long a, long b) { return a > b; }
                    private static boolean ge(long a, long b) { return a >= b; }
                    private static <T extends Comparable<T>> boolean lt(T a, T b) { return a.compareTo(b) < 0; }
                    private static <T extends Comparable<T>> boolean le(T a, T b) { return a.compareTo(b) <= 0; }
                    private static <T extends Comparable<T>> boolean gt(T a, T b) { return a.compareTo(b) > 0; }
                    private static <T extends Comparable<T>> boolean ge(T a, T b) { return a.compareTo(b) >= 0; }
                    private static long plus(long a, long b) { return a + b; }
                    private static long minus(long a, long b) { return a - b; }
                    private static long times(long a, long b) { return a * b; }
                    private static long div(long a, long b) { return a / b; }
                    private static long max(long a, long b) { return Math.max(a, b); }
                    private static long min(long a, long b) { return Math.min(a, b); }
                    private static <T extends Comparable<T>> T max(T a, T b) { return a.compareTo(b) >= 0 ? a : b; }
                    private static <T extends Comparable<T>> T min(T a, T b) { return a.compareTo(b) <= 0 ? a : b; }
                """;
        String lenDispatch = """
                    private static long len(Object o) {
                        if (o instanceof java.util.Collection<?> c) { return c.size(); }
                        if (o instanceof java.util.Map<?, ?> m) { return m.size(); }
                        if (o instanceof String s) { return s.length(); }
                %s        throw new IllegalArgumentException("no length for " + o);
                    }
                """.formatted(bytes ? "        if (o instanceof Bytes b) { return b.length(); }\n" : "");
        String moneyOps = """
                    private static Money plus(Money a, Money b) { return a.plus(b); }
                    private static Money minus(Money a, Money b) { return a.minus(b); }
                    private static Money times(Money a, long b) { return a.times(b); }
                    private static Money times(long a, Money b) { return b.times(a); }
                """;
        // sumOf dispatches on the element at runtime; the Money variant needs the Money class.
        String longSum = """
                    private static long sumOf(java.util.List<Object> items) {
                        long total = 0;
                        for (Object item : items) { total += (Long) item; }
                        return total;
                    }
                """;
        String dispatchingSum = """
                    private static Object sumOf(java.util.List<Object> items) {
                        long total = 0;
                        Money moneyTotal = null;
                        for (Object item : items) {
                            if (item instanceof Money m) { moneyTotal = moneyTotal == null ? m : moneyTotal.plus(m); }
                            else { total += (Long) item; }
                        }
                        return moneyTotal != null ? moneyTotal : total;
                    }
                """;
        return (money ? base + moneyOps + dispatchingSum : base + longSum) + lenDispatch;
    }

    /** A message suffix printing the inputs — the counterexample when the clause fails. */
    private static String counterexample(List<String> inputs) {
        if (inputs.isEmpty()) {
            return "";
        }
        String parts = inputs.stream()
                .map(name -> Lowering.javaString(name + "=") + " + " + name)
                .collect(Collectors.joining(" + " + Lowering.javaString(", ") + " + "));
        return " + " + Lowering.javaString(" [") + " + " + parts + " + " + Lowering.javaString("]");
    }

    private String testMethod(Ast.Service service, Ast.Method m, Ast.Example ex, int n, Ast.Module module) {
        java.util.Set<String> values = Lowering.valueEntities(module);
        Map<String, String> env = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("\n    @Test\n    void ").append(m.name()).append("_example_").append(n).append("() throws Exception {\n");
        sb.append(construction(service));

        List<Ast.Expr> args = ex.call().args();
        for (int i = 0; i < m.params().size(); i++) {
            String name = m.params().get(i).name();
            sb.append("        var ").append(name).append(" = ")
                    .append(Lowering.exprToJava(args.get(i), Map.of(), module)).append(";\n");
            env.put(name, name);
        }

        // `on a Product with stock 5` — establish the stored row before anything reads it.
        ex.seed().ifPresent(seed -> sb.append("        db.save(")
                .append(seedValue(seed, m, module, Lowering.typesOf(module), values)).append(");\n"));

        String argNames = m.params().stream().map(Ast.Param::name).collect(Collectors.joining(", "));
        if (ex.result() instanceof Ast.RaisesResult rr) {
            sb.append("        assertThrows(").append(rr.error()).append(".class, () -> svc.")
                    .append(m.name()).append("(").append(argNames).append("), ")
                    .append(Lowering.javaString("example: raises " + rr.error())).append(");\n");
            sb.append("    }\n");
            return sb.toString();
        }

        // old(...) values are snapshotted before the call; old(result...) reads the stored
        // row the method will return, found by its id parameter.
        Map<String, String> oldNames = new LinkedHashMap<>();
        collectOld(m.ensures(), oldNames);
        if (oldNames.keySet().stream().anyMatch(k -> k.contains("result"))) {
            String entity = ((Ast.TypeRef) m.returnType()).name();
            String idParam = module.entities().stream().filter(e -> e.name().equals(entity))
                    .flatMap(e -> e.fields().stream()).filter(Ast.Field::id)
                    .map(Ast.Field::name).findFirst().orElseThrow();
            sb.append("        var __before = db.find").append(entity).append("(")
                    .append(idParam).append(").orElseThrow();\n");
            env.put("__before", "__before");
        }
        int snapshot = 0;
        for (Map.Entry<String, String> old : oldNames.entrySet()) {
            old.setValue("__old" + snapshot++);
        }
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (Ast.Expr ens : m.ensures()) {
            emitSnapshotWalk(ens, oldNames, env, module, sb, emitted);
        }
        env.put("result", "result");
        sb.append("        var result = svc.").append(m.name()).append("(").append(argNames).append(");\n");

        List<String> inputs = m.params().stream().map(Ast.Param::name).toList();
        for (Ast.Expr ens : m.ensures()) {
            String cond = Lowering.exprToJava(replaceOld(ens, oldNames), env, module);
            sb.append("        assertTrue(").append(cond).append(", () -> ")
                    .append(Lowering.javaString("ensures: " + Lowering.skyText(ens)))
                    .append(counterexample(inputs)).append(");\n");
        }

        switch (ex.result()) {
            case Ast.RaisesResult rr ->
                    throw new IllegalStateException("raises results return before the call");
            case Ast.ExprResult er -> sb.append("        assertEquals(")
                    .append(Lowering.javaValue(er.value(), values)).append(", result, \"example result\");\n");
            case Ast.EntityResult ent -> appendFieldAsserts(sb, ent.fields(), values);
            case Ast.FieldsResult fr -> appendFieldAsserts(sb, fr.fields(), values);
        }

        sb.append("    }\n");
        return sb.toString();
    }

    private static void appendFieldAsserts(StringBuilder sb, List<Ast.FieldExpect> fields,
                                           java.util.Set<String> values) {
        for (Ast.FieldExpect fe : fields) {
            sb.append("        assertEquals(").append(Lowering.javaValue(fe.expected(), values))
                    .append(", result.").append(fe.field()).append("(), ")
                    .append(Lowering.javaString("example: " + fe.field())).append(");\n");
        }
    }

    /** The seeded row: pinned fields from the seed, the id from the matching call argument. */
    private static String seedValue(Ast.Seed seed, Ast.Method m, Ast.Module module,
                                    Map<String, Ast.TypeDecl> types, java.util.Set<String> values) {
        Ast.Entity entity = module.entities().stream()
                .filter(e -> e.name().equals(seed.entityName())).findFirst().orElseThrow();
        List<String> args = new java.util.ArrayList<>();
        for (Ast.Field f : entity.fields()) {
            Ast.Expr pinned = seed.fields().stream()
                    .filter(fe -> fe.field().equals(f.name()))
                    .map(Ast.FieldExpect::expected).findFirst().orElse(null);
            if (pinned != null) {
                args.add(Lowering.javaValue(pinned, values));
            } else if (f.id() && m.params().stream().anyMatch(p -> p.name().equals(f.name()))) {
                args.add(f.name());   // the local bound to the call argument of the same name
            } else {
                String fallback = Lowering.defaultJavaValue(f.type(), types, module);
                if (fallback == null) {
                    throw new IllegalStateException("cannot derive a value for "
                            + entity.name() + "." + f.name() + " in a seeded example");
                }
                args.add(fallback);
            }
        }
        return "new " + entity.name() + "(" + String.join(", ", args) + ")";
    }

    /**
     * A spec block as a test: given pins construct and seed the witnesses, when performs
     * the call, then asserts raises and outcomes — stored state re-read after the call.
     */
    private String specTest(Ast.Service service, Ast.Method m, Ast.Spec spec, int n,
                            Ast.Module module, Map<String, Ast.TypeDecl> types) {
        java.util.Set<String> values = Lowering.valueEntities(module);
        Map<String, Map<String, Ast.Expr>> fieldPins = new LinkedHashMap<>();
        Map<String, Ast.Expr> directPins = new LinkedHashMap<>();
        spec.given().ifPresent(g -> collectPins(g, fieldPins, directPins));

        StringBuilder sb = new StringBuilder();
        sb.append("\n    @Test\n    void ").append(m.name()).append("_spec_").append(n).append("() throws Exception {\n");
        sb.append(construction(service));

        // Witness variables for the parameters the when-call names; sequential ids keep
        // distinct entity witnesses distinct rows.
        Map<String, String> env = new LinkedHashMap<>();
        List<String> entityParams = new java.util.ArrayList<>();
        long nextId = 1;
        for (Ast.Param p : m.params()) {
            boolean referenced = spec.when().args().stream()
                    .anyMatch(a -> a instanceof Ast.NameExpr ne && ne.name().equals(p.name()));
            if (!referenced) {
                continue;
            }
            String value;
            if (directPins.containsKey(p.name())) {
                value = Lowering.javaValue(directPins.get(p.name()), values);
            } else if (p.type() instanceof Ast.TypeRef ref && isModuleEntity(module, ref.name())) {
                value = Lowering.entityWitness(ref.name(), fieldPins.getOrDefault(p.name(), Map.of()),
                        nextId++, module, types, values);
                entityParams.add(p.name());
            } else {
                value = Lowering.defaultJavaValue(p.type(), types, module);
            }
            sb.append("        var ").append(p.name()).append(" = ").append(value).append(";\n");
            env.put(p.name(), p.name());
        }
        if (service.uses().contains("db")) {
            for (String p : entityParams) {
                sb.append("        db.save(").append(p).append(");\n");
            }
        }

        String call = "svc." + m.name() + "(" + spec.when().args().stream()
                .map(a -> Lowering.exprToJava(a, env, module)).collect(Collectors.joining(", ")) + ")";
        String raises = spec.then().stream()
                .filter(t -> t instanceof Ast.ThenRaises)
                .map(t -> ((Ast.ThenRaises) t).error()).findFirst().orElse(null);
        if (raises != null) {
            sb.append("        assertThrows(").append(raises).append(".class, () -> ").append(call)
                    .append(", ").append(Lowering.javaString("spec: " + spec.title())).append(");\n");
        } else {
            sb.append("        var result = ").append(call).append(";\n");
            env.put("result", "result");
        }

        // Stored state after the call: re-read each asserted entity witness by its id.
        java.util.Set<String> reread = new java.util.LinkedHashSet<>();
        for (Ast.ThenAssert t : spec.then()) {
            if (t instanceof Ast.ThenExpr te) {
                collectPostReads(te.expr(), entityParams, reread);
            }
        }
        for (String p : reread) {
            String entity = m.params().stream().filter(x -> x.name().equals(p))
                    .map(x -> ((Ast.TypeRef) x.type()).name()).findFirst().orElseThrow();
            String idField = idFieldOf(module, entity);
            sb.append("        var __post_").append(p).append(" = db.find").append(entity)
                    .append("((").append(p).append(").").append(idField).append("()).orElseThrow();\n");
            env.put("__post_" + p, "__post_" + p);
        }
        List<String> witnesses = m.params().stream().map(Ast.Param::name)
                .filter(env::containsKey).toList();
        for (Ast.ThenAssert t : spec.then()) {
            if (t instanceof Ast.ThenExpr te) {
                Ast.Expr rewritten = rewritePostState(te.expr(), reread);
                sb.append("        assertTrue(").append(Lowering.exprToJava(rewritten, env, module))
                        .append(", () -> ").append(Lowering.javaString("then: " + Lowering.skyText(te.expr())))
                        .append(counterexample(witnesses)).append(");\n");
            }
        }
        sb.append("    }\n");
        return sb.toString();
    }

    private static boolean isModuleEntity(Ast.Module module, String name) {
        return module.entities().stream().anyMatch(e -> e.name().equals(name) && e.values().isEmpty());
    }

    private static String idFieldOf(Ast.Module module, String entity) {
        return module.entities().stream().filter(e -> e.name().equals(entity))
                .flatMap(e -> e.fields().stream()).filter(Ast.Field::id)
                .map(Ast.Field::name).findFirst().orElseThrow();
    }

    /** Split an and-chain of given pins into per-parameter field pins and direct pins. */
    private static void collectPins(Ast.Expr g, Map<String, Map<String, Ast.Expr>> fieldPins,
                                    Map<String, Ast.Expr> directPins) {
        if (g instanceof Ast.BinExpr and && and.op().equals("and")) {
            collectPins(and.left(), fieldPins, directPins);
            collectPins(and.right(), fieldPins, directPins);
            return;
        }
        Ast.BinExpr pin = (Ast.BinExpr) g;
        if (pin.left() instanceof Ast.NameExpr n) {
            directPins.put(n.name(), pin.right());
        } else if (pin.left() instanceof Ast.MemberExpr me && me.target() instanceof Ast.NameExpr n) {
            fieldPins.computeIfAbsent(n.name(), k -> new LinkedHashMap<>()).put(me.field(), pin.right());
        }
    }

    private static void collectPostReads(Ast.Expr e, List<String> entityParams, java.util.Set<String> out) {
        switch (e) {
            case Ast.MemberExpr m -> {
                if (m.target() instanceof Ast.NameExpr n && entityParams.contains(n.name())) {
                    out.add(n.name());
                }
                collectPostReads(m.target(), entityParams, out);
            }
            case Ast.BinExpr b -> {
                collectPostReads(b.left(), entityParams, out);
                collectPostReads(b.right(), entityParams, out);
            }
            case Ast.NotExpr n -> collectPostReads(n.value(), entityParams, out);
            case Ast.CallExpr c -> c.args().forEach(a -> collectPostReads(a, entityParams, out));
            default -> {
            }
        }
    }

    /** In then-assertions, a witness's field means the stored row after the call. */
    private static Ast.Expr rewritePostState(Ast.Expr e, java.util.Set<String> reread) {
        return switch (e) {
            case Ast.MemberExpr m -> m.target() instanceof Ast.NameExpr n && reread.contains(n.name())
                    ? new Ast.MemberExpr(new Ast.NameExpr("__post_" + n.name()), m.field())
                    : new Ast.MemberExpr(rewritePostState(m.target(), reread), m.field());
            case Ast.BinExpr b -> new Ast.BinExpr(b.op(), rewritePostState(b.left(), reread),
                    rewritePostState(b.right(), reread));
            case Ast.NotExpr n -> new Ast.NotExpr(rewritePostState(n.value(), reread));
            case Ast.CallExpr c -> new Ast.CallExpr(c.callee(),
                    c.args().stream().map(a -> rewritePostState(a, reread)).toList());
            default -> e;
        };
    }

    /** Index each distinct old(...) by its written form; the value becomes its snapshot name. */
    private static void collectOld(List<Ast.Expr> ensures, Map<String, String> oldNames) {
        for (Ast.Expr e : ensures) {
            walkOld(e, oldNames);
        }
    }

    private static void walkOld(Ast.Expr e, Map<String, String> oldNames) {
        switch (e) {
            case Ast.OldExpr o -> oldNames.putIfAbsent(Lowering.skyText(o.value()), null);
            case Ast.BinExpr b -> {
                walkOld(b.left(), oldNames);
                walkOld(b.right(), oldNames);
            }
            case Ast.NotExpr n -> walkOld(n.value(), oldNames);
            case Ast.MemberExpr m -> walkOld(m.target(), oldNames);
            case Ast.CallExpr c -> c.args().forEach(a -> walkOld(a, oldNames));
            default -> {
            }
        }
    }

    private static void emitSnapshotWalk(Ast.Expr e, Map<String, String> oldNames, Map<String, String> env,
                                         Ast.Module module, StringBuilder sb, java.util.Set<String> emitted) {
        switch (e) {
            case Ast.OldExpr o -> {
                String key = Lowering.skyText(o.value());
                if (emitted.add(key)) {
                    Ast.Expr snapshot = renameResult(o.value());
                    sb.append("        var ").append(oldNames.get(key)).append(" = ")
                            .append(Lowering.exprToJava(snapshot, env, module)).append(";\n");
                }
            }
            case Ast.BinExpr b -> {
                emitSnapshotWalk(b.left(), oldNames, env, module, sb, emitted);
                emitSnapshotWalk(b.right(), oldNames, env, module, sb, emitted);
            }
            case Ast.NotExpr n -> emitSnapshotWalk(n.value(), oldNames, env, module, sb, emitted);
            case Ast.MemberExpr m -> emitSnapshotWalk(m.target(), oldNames, env, module, sb, emitted);
            case Ast.CallExpr c -> c.args().forEach(a -> emitSnapshotWalk(a, oldNames, env, module, sb, emitted));
            default -> {
            }
        }
    }

    /** Inside a snapshot, `result` means the row as it was before the call. */
    private static Ast.Expr renameResult(Ast.Expr e) {
        return switch (e) {
            case Ast.NameExpr n -> n.name().equals("result") ? new Ast.NameExpr("__before") : n;
            case Ast.MemberExpr m -> new Ast.MemberExpr(renameResult(m.target()), m.field());
            case Ast.BinExpr b -> new Ast.BinExpr(b.op(), renameResult(b.left()), renameResult(b.right()));
            case Ast.NotExpr n -> new Ast.NotExpr(renameResult(n.value()));
            case Ast.CallExpr c -> new Ast.CallExpr(c.callee(),
                    c.args().stream().map(ProjectStager::renameResult).toList());
            default -> e;
        };
    }

    /** Swap every old(...) for its snapshot variable before lowering the assertion. */
    private static Ast.Expr replaceOld(Ast.Expr e, Map<String, String> oldNames) {
        return switch (e) {
            case Ast.OldExpr o -> new Ast.NameExpr(oldNames.get(Lowering.skyText(o.value())));
            case Ast.BinExpr b -> new Ast.BinExpr(b.op(), replaceOld(b.left(), oldNames),
                    replaceOld(b.right(), oldNames));
            case Ast.NotExpr n -> new Ast.NotExpr(replaceOld(n.value(), oldNames));
            case Ast.MemberExpr m -> new Ast.MemberExpr(replaceOld(m.target(), oldNames), m.field());
            case Ast.CallExpr c -> new Ast.CallExpr(c.callee(),
                    c.args().stream().map(a -> replaceOld(a, oldNames)).toList());
            case Ast.EmptyCheck ec -> new Ast.EmptyCheck(replaceOld(ec.value(), oldNames));
            case Ast.AggExpr a -> new Ast.AggExpr(a.kind(), replaceOld(a.value(), oldNames), a.var(),
                    a.source() instanceof Ast.SourceExpr s
                            ? new Ast.SourceExpr(replaceOld(s.expr(), oldNames)) : a.source(),
                    a.where().map(w -> replaceOld(w, oldNames)));
            default -> e;
        };
    }

    // ----- helpers -----------------------------------------------------------

    private static String indent(String block, String prefix) {
        return block.lines().map(l -> l.isBlank() ? l : prefix + l).collect(Collectors.joining("\n"));
    }

    private static String pom(boolean db) {
        String persistence = !db ? "" : """
                    <dependency>
                      <groupId>jakarta.persistence</groupId>
                      <artifactId>jakarta.persistence-api</artifactId>
                      <version>3.1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.eclipse.persistence</groupId>
                      <artifactId>eclipselink</artifactId>
                      <version>4.0.8</version>
                      <scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.h2database</groupId>
                      <artifactId>h2</artifactId>
                      <version>2.2.224</version>
                      <scope>test</scope>
                    </dependency>
                """;
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.adeptum.skylang.staged</groupId>
                  <artifactId>staged</artifactId>
                  <version>0.0.0</version>
                  <properties>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                  <dependencies>
                %s    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.2</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.2.5</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(persistence);
    }

    /**
     * The staged web project's POM: Jakarta EE (Web Profile) on an embedded TomEE with Eclipse
     * Mojarra as the sole Faces implementation, so a generated view renders in-container for
     * verification. TomEE provides the APIs, so the aggregate api jar stays off the runtime classpath.
     */
    private static String webPom(boolean db) {
        String persistence = !db ? "" : """
                    <dependency>
                      <groupId>org.eclipse.persistence</groupId>
                      <artifactId>eclipselink</artifactId>
                      <version>4.0.8</version>
                    </dependency>
                    <dependency>
                      <groupId>com.h2database</groupId>
                      <artifactId>h2</artifactId>
                      <version>2.2.224</version>
                      <scope>runtime</scope>
                    </dependency>
                """;
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.adeptum.skylang.staged</groupId>
                  <artifactId>staged</artifactId>
                  <version>0.0.0</version>
                  <packaging>war</packaging>
                  <properties>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <maven.compiler.release>17</maven.compiler.release>
                    <failOnMissingWebXml>false</failOnMissingWebXml>
                  </properties>
                  <dependencies>
                %s    <dependency>
                      <groupId>jakarta.platform</groupId>
                      <artifactId>jakarta.jakartaee-api</artifactId>
                      <version>10.0.0</version>
                      <scope>provided</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.2</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.apache.tomee</groupId>
                      <artifactId>tomee-embedded</artifactId>
                      <version>10.0.0</version>
                      <scope>test</scope>
                      <exclusions>
                        <exclusion><groupId>org.apache.myfaces.core</groupId><artifactId>myfaces-api</artifactId></exclusion>
                        <exclusion><groupId>org.apache.myfaces.core</groupId><artifactId>myfaces-impl</artifactId></exclusion>
                        <exclusion><groupId>org.apache.tomee</groupId><artifactId>tomee-myfaces</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                    <dependency>
                      <groupId>org.glassfish</groupId>
                      <artifactId>jakarta.faces</artifactId>
                      <version>4.0.7</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.jsoup</groupId>
                      <artifactId>jsoup</artifactId>
                      <version>1.19.1</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.seleniumhq.selenium</groupId>
                      <artifactId>selenium-java</artifactId>
                      <version>4.40.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.seleniumhq.selenium</groupId>
                      <artifactId>htmlunit3-driver</artifactId>
                      <version>4.40.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.xhtmlrenderer</groupId>
                      <artifactId>flying-saucer-core</artifactId>
                      <version>9.11.4</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.13</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.2.5</version>
                        <configuration>
                          <classpathDependencyExcludes>
                            <classpathDependencyExclude>jakarta.platform:jakarta.jakartaee-api</classpathDependencyExclude>
                          </classpathDependencyExcludes>
                          <systemPropertyVariables>
                            <java.awt.headless>true</java.awt.headless>
                          </systemPropertyVariables>
                        </configuration>
                      </plugin>
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>exec-maven-plugin</artifactId>
                        <version>3.5.0</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(persistence);
    }
}
