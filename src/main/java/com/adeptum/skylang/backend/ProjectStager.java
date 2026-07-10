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
            Files.writeString(buildDir.resolve("pom.xml"), module.views().isEmpty() ? pom() : webPom());

            for (String support : SupportClasses.used(module)) {
                Files.writeString(main.resolve(support + ".java"),
                        SupportClasses.source(support, pkg).orElseThrow());
            }
            boolean web = !module.views().isEmpty();
            for (Ast.Entity e : module.entities()) {
                Files.writeString(main.resolve(e.name() + ".java"), entitySource(pkg, e, web, types));
            }
            for (Ast.Service s : module.services()) {
                Files.writeString(main.resolve(s.name() + ".java"), serviceSource(pkg, module, s, bodies, types));
                Files.writeString(test.resolve(s.name() + "Test.java"), testSource(pkg, module, s));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage project under " + buildDir, e);
        }
    }

    // ----- entity ------------------------------------------------------------

    private String entitySource(String pkg, Ast.Entity entity, boolean web, Map<String, Ast.TypeDecl> types) {
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
                + defaultsConstructor(entity, types)
                + getters
                + "}\n";
    }

    private static boolean isSecret(Ast.Type type) {
        return type instanceof Ast.GenericType g && g.name().equals("Secret");
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
    private String defaultsConstructor(Ast.Entity entity, Map<String, Ast.TypeDecl> types) {
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
                    : Lowering.javaValue(fields.get(i).defaultValue().orElseThrow()));
        }
        return "\n    public " + entity.name() + "(" + params + ") {\n"
                + "        this(" + args + ");\n    }\n";
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
        for (Ast.Method m : service.methods()) {
            String key = methodKey(module.name(), service.name(), m.name());
            String body = bodies.get(key);
            if (body == null) {
                throw new IllegalStateException("no resolved body for " + key);
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
            }
            sb.append(indent(body.strip(), "        ")).append('\n');
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String signature(Ast.Method m, Map<String, Ast.TypeDecl> types) {
        String params = m.params().stream()
                .map(p -> Lowering.javaType(p.type(), types) + " " + p.name())
                .collect(Collectors.joining(", "));
        return Lowering.javaType(m.returnType(), types) + " " + m.name() + "(" + params + ")";
    }

    // ----- tests -------------------------------------------------------------

    private String testSource(String pkg, Ast.Module module, Ast.Service service) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n\n");
        sb.append("public class ").append(service.name()).append("Test {\n");

        for (Ast.Method m : service.methods()) {
            int n = 0;
            for (Ast.Example ex : m.examples()) {
                sb.append(testMethod(service, m, ex, ++n));
            }
        }
        sb.append(opHelpers(SupportClasses.used(module).contains("Money")));
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * The helper methods contract expressions lower their operators to; javac's overload
     * resolution gives each lowered type its correct semantics (primitive comparison for
     * long, value equality for objects, currency-safe arithmetic for Money).
     */
    private static String opHelpers(boolean money) {
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
                """;
        String moneyOps = """
                    private static Money plus(Money a, Money b) { return a.plus(b); }
                    private static Money minus(Money a, Money b) { return a.minus(b); }
                    private static Money times(Money a, long b) { return a.times(b); }
                    private static Money times(long a, Money b) { return b.times(a); }
                """;
        return money ? base + moneyOps : base;
    }

    private String testMethod(Ast.Service service, Ast.Method m, Ast.Example ex, int n) {
        Map<String, String> env = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("\n    @Test\n    void ").append(m.name()).append("_example_").append(n).append("() {\n");
        sb.append("        ").append(service.name()).append(" svc = new ").append(service.name()).append("();\n");

        List<Ast.Expr> args = ex.call().args();
        for (int i = 0; i < m.params().size(); i++) {
            String name = m.params().get(i).name();
            sb.append("        var ").append(name).append(" = ")
                    .append(Lowering.javaValue(args.get(i))).append(";\n");
            env.put(name, name);
        }
        env.put("result", "result");

        String argNames = m.params().stream().map(Ast.Param::name).collect(Collectors.joining(", "));
        sb.append("        var result = svc.").append(m.name()).append("(").append(argNames).append(");\n");

        for (Ast.Expr ens : m.ensures()) {
            String cond = Lowering.exprToJava(ens, env);
            sb.append("        assertTrue(").append(cond).append(", ")
                    .append(Lowering.javaString("ensures: " + cond)).append(");\n");
        }

        switch (ex.result()) {
            case Ast.ExprResult er -> sb.append("        assertEquals(")
                    .append(Lowering.javaValue(er.value())).append(", result, \"example result\");\n");
            case Ast.EntityResult ent -> {
                for (Ast.FieldExpect fe : ent.fields()) {
                    sb.append("        assertEquals(").append(Lowering.javaValue(fe.expected()))
                            .append(", result.").append(fe.field()).append("(), ")
                            .append(Lowering.javaString("example: " + fe.field())).append(");\n");
                }
            }
        }

        sb.append("    }\n");
        return sb.toString();
    }

    // ----- helpers -----------------------------------------------------------

    private static String indent(String block, String prefix) {
        return block.lines().map(l -> l.isBlank() ? l : prefix + l).collect(Collectors.joining("\n"));
    }

    private static String pom() {
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
                    <dependency>
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
                """;
    }

    /**
     * The staged web project's POM: Jakarta EE (Web Profile) on an embedded TomEE with Eclipse
     * Mojarra as the sole Faces implementation, so a generated view renders in-container for
     * verification. TomEE provides the APIs, so the aggregate api jar stays off the runtime classpath.
     */
    private static String webPom() {
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
                    <dependency>
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
                """;
    }
}
