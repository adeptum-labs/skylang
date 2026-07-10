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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Materializes the db effect's binding: a typed {@code Db} store interface, one JPA
 * mapping class per identified entity (plus an embeddable per component entity), the
 * {@code JpaDb} implementation, and the persistence unit. Records stay the domain
 * surface; each mapping class converts to and from its record.
 *
 * <p>Relations carry no cascade: saving an entity that references another requires the
 * referee to be saved first, exactly as ownership-by-lifecycle suggests.
 */
public final class JpaStager {

    public void stage(Ast.Module module, Path buildDir) {
        String pkg = module.name();
        Path main = buildDir.resolve("src/main/java").resolve(pkg);
        Path metaInf = buildDir.resolve("src/main/resources/META-INF");
        Map<String, Ast.TypeDecl> types = Lowering.typesOf(module);
        List<Ast.Entity> roots = new ArrayList<>();
        List<Ast.Entity> components = new ArrayList<>();
        java.util.Set<String> errors = ProjectStager.errorEntities(module);
        for (Ast.Entity e : module.entities()) {
            if (!e.values().isEmpty() || errors.contains(e.name())) {
                continue;   // value sets persist as text; errors are failures, not data
            }
            (e.fields().stream().anyMatch(Ast.Field::id) ? roots : components).add(e);
        }
        try {
            Files.createDirectories(main);
            Files.createDirectories(metaInf);
            Files.writeString(main.resolve("Db.java"), dbInterface(pkg, roots, types));
            Files.writeString(main.resolve("JpaDb.java"), jpaDb(pkg, roots, types));
            for (Ast.Entity e : roots) {
                Files.writeString(main.resolve(e.name() + "Jpa.java"), mappingClass(pkg, e, module, types, true));
            }
            for (Ast.Entity e : components) {
                Files.writeString(main.resolve(e.name() + "Jpa.java"), mappingClass(pkg, e, module, types, false));
            }
            Files.writeString(metaInf.resolve("persistence.xml"), persistenceXml(pkg, roots, components));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage the persistence layer under " + buildDir, e);
        }
    }

    // ----- the store interface and its JPA implementation -----------------------

    private static String dbInterface(String pkg, List<Ast.Entity> roots, Map<String, Ast.TypeDecl> types) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/**\n");
        sb.append(" * The db effect: a typed store over the module's identified entities. Saving an\n");
        sb.append(" * entity that references another requires the referee to be saved first.\n");
        sb.append(" */\n");
        sb.append("public interface Db {\n");
        for (Ast.Entity e : roots) {
            String n = e.name();
            String var = lower(n);
            String idType = idJavaType(e, types);
            sb.append("\n    ").append(n).append(" save(").append(n).append(' ').append(var).append(");\n");
            sb.append("\n    java.util.Optional<").append(n).append("> find").append(n)
                    .append("(").append(idType).append(" id);\n");
            sb.append("\n    java.util.List<").append(n).append("> all").append(n).append("s();\n");
            sb.append("\n    void delete").append(n).append("(").append(idType).append(" id);\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String jpaDb(String pkg, List<Ast.Entity> roots, Map<String, Ast.TypeDecl> types) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/** The JPA binding of the db effect; each call runs in its own transaction. */\n");
        sb.append("public final class JpaDb implements Db {\n\n");
        sb.append("    private final jakarta.persistence.EntityManagerFactory factory;\n\n");
        sb.append("    public JpaDb(jakarta.persistence.EntityManagerFactory factory) {\n");
        sb.append("        this.factory = factory;\n    }\n");
        for (Ast.Entity e : roots) {
            String n = e.name();
            String jpa = n + "Jpa";
            String var = lower(n);
            String idType = idJavaType(e, types);
            sb.append("\n    @Override\n    public ").append(n).append(" save(").append(n).append(' ')
                    .append(var).append(") {\n");
            sb.append("        return tx(em -> em.merge(").append(jpa).append(".of(").append(var)
                    .append(")).toRecord());\n    }\n");
            sb.append("\n    @Override\n    public java.util.Optional<").append(n).append("> find").append(n)
                    .append("(").append(idType).append(" id) {\n");
            sb.append("        return tx(em -> java.util.Optional.ofNullable(em.find(").append(jpa)
                    .append(".class, id)).map(").append(jpa).append("::toRecord));\n    }\n");
            sb.append("\n    @Override\n    public java.util.List<").append(n).append("> all").append(n)
                    .append("s() {\n");
            sb.append("        return tx(em -> em.createQuery(\"select x from ").append(jpa)
                    .append(" x\", ").append(jpa).append(".class)\n")
                    .append("                .getResultList().stream().map(").append(jpa)
                    .append("::toRecord).toList());\n    }\n");
            sb.append("\n    @Override\n    public void delete").append(n).append("(").append(idType)
                    .append(" id) {\n");
            sb.append("        tx(em -> {\n");
            sb.append("            ").append(jpa).append(" row = em.find(").append(jpa).append(".class, id);\n");
            sb.append("            if (row != null) {\n                em.remove(row);\n            }\n");
            sb.append("            return null;\n        });\n    }\n");
        }
        sb.append("""

                    private <R> R tx(java.util.function.Function<jakarta.persistence.EntityManager, R> work) {
                        jakarta.persistence.EntityManager em = factory.createEntityManager();
                        try {
                            em.getTransaction().begin();
                            R result = work.apply(em);
                            em.getTransaction().commit();
                            return result;
                        } catch (RuntimeException e) {
                            if (em.getTransaction().isActive()) {
                                em.getTransaction().rollback();
                            }
                            throw e;
                        } finally {
                            em.close();
                        }
                    }
                }
                """);
        return sb.toString();
    }

    // ----- one mapping class per entity ----------------------------------------

    private String mappingClass(String pkg, Ast.Entity entity, Ast.Module module,
                                Map<String, Ast.TypeDecl> types, boolean root) {
        List<String> decls = new ArrayList<>();
        List<String> of = new ArrayList<>();
        List<String> to = new ArrayList<>();
        for (Ast.Field f : entity.fields()) {
            mapField(f, module, types, decls, of, to);
        }
        String jpa = entity.name() + "Jpa";
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/** The storage form of ").append(entity.name())
                .append("; converts to and from the domain record. */\n");
        if (root) {
            sb.append("@jakarta.persistence.Entity\n");
            sb.append("@jakarta.persistence.Table(name = \"sky_").append(lower(entity.name())).append("\")\n");
        } else {
            sb.append("@jakarta.persistence.Embeddable\n");
        }
        sb.append("public class ").append(jpa).append(" {\n\n");
        for (String decl : decls) {
            sb.append(decl);
        }
        sb.append("\n    public static ").append(jpa).append(" of(").append(entity.name()).append(" value) {\n");
        sb.append("        ").append(jpa).append(" row = new ").append(jpa).append("();\n");
        for (String line : of) {
            sb.append("        ").append(line).append('\n');
        }
        sb.append("        return row;\n    }\n");
        sb.append("\n    public ").append(entity.name()).append(" toRecord() {\n");
        sb.append("        return new ").append(entity.name()).append("(\n                ")
                .append(String.join(",\n                ", to)).append(");\n    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Append the column(s), the record-to-row assignment, and the row-to-record argument. */
    private void mapField(Ast.Field f, Ast.Module module, Map<String, Ast.TypeDecl> types,
                          List<String> decls, List<String> of, List<String> to) {
        String n = f.name();
        String get = "value." + n + "()";
        String id = (f.id() ? "    @jakarta.persistence.Id\n" : "")
                + (f.unique() ? "    @jakarta.persistence.Column(unique = true)\n" : "");
        Ast.Type type = f.type();
        if (type instanceof Ast.TypeRef ref && ref.list()) {
            type = new Ast.GenericType("List", List.of(new Ast.TypeRef(ref.name())));
        }

        if (type instanceof Ast.GenericType g) {
            switch (g.name()) {
                case "Secret" -> {
                    boolean text = baseName(g.args().get(0), types).equals("Text");
                    decls.add(id + "    public " + (text ? "String" : "byte[]") + " " + n + ";\n");
                    of.add("row." + n + " = " + get + ".reveal()" + (text ? "" : ".toByteArray()") + ";");
                    to.add(text ? "Secret.of(" + n + ")" : "Secret.of(Bytes.of(" + n + "))");
                    if (!text) {
                        // Secret<Bytes> stores the raw bytes; rebuild wraps them again.
                        of.set(of.size() - 1, "row." + n + " = " + get + ".reveal().toByteArray();");
                    }
                }
                case "Maybe" -> mapMaybe(n, get, g.args().get(0), module, types, decls, of, to);
                default -> mapCollection(n, get, g, module, types, decls, of, to);
            }
            return;
        }

        String base = baseName(type, types);
        switch (kindOf(base, module)) {
            case "Int" -> {
                decls.add(id + "    public long " + n + ";\n");
                of.add("row." + n + " = " + get + ";");
                to.add(n);
            }
            case "Text" -> {
                decls.add(id + "    public String " + n + ";\n");
                of.add("row." + n + " = " + get + ";");
                to.add(n);
            }
            case "Bool" -> {
                decls.add("    public boolean " + n + ";\n");
                of.add("row." + n + " = " + get + ";");
                to.add(n);
            }
            case "Money" -> {
                // Explicit scale: schema generation would otherwise default to scale 0 and round.
                decls.add("    @jakarta.persistence.Column(precision = 38, scale = 4)\n"
                        + "    public java.math.BigDecimal " + n + "Amount;\n");
                decls.add("    public String " + n + "Currency;\n");
                of.add("row." + n + "Amount = " + get + ".amount();");
                of.add("row." + n + "Currency = " + get + ".currency().getCurrencyCode();");
                to.add("Money.of(" + n + "Amount.toPlainString(), " + n + "Currency)");
            }
            case "Instant" -> {
                // Stored as epoch millis: portable across providers, no timezone to get wrong.
                decls.add("    public long " + n + "Millis;\n");
                of.add("row." + n + "Millis = " + get + ".toEpochMilli();");
                to.add("java.time.Instant.ofEpochMilli(" + n + "Millis)");
            }
            case "Bytes" -> {
                decls.add("    public byte[] " + n + ";\n");
                of.add("row." + n + " = " + get + ".toByteArray();");
                to.add("Bytes.of(" + n + ")");
            }
            case "values" -> {
                decls.add("    public String " + n + ";\n");
                of.add("row." + n + " = " + get + "." + carrier(base, module) + "();");
                to.add("new " + base + "(" + n + ")");
            }
            case "relation" -> {
                decls.add("    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.EAGER)\n"
                        + "    public " + base + "Jpa " + n + ";\n");
                of.add("row." + n + " = " + base + "Jpa.of(" + get + ");");
                to.add(n + ".toRecord()");
            }
            default -> {   // an embedded component
                decls.add("    @jakarta.persistence.Embedded\n    public " + base + "Jpa " + n + ";\n");
                of.add("row." + n + " = " + base + "Jpa.of(" + get + ");");
                to.add(n + ".toRecord()");
            }
        }
    }

    private void mapMaybe(String n, String get, Ast.Type inner, Ast.Module module,
                          Map<String, Ast.TypeDecl> types, List<String> decls, List<String> of, List<String> to) {
        String base = baseName(inner, types);
        switch (kindOf(base, module)) {
            case "Int" -> nullableColumn(n, get, "Long", "", decls, of, to);
            case "Text" -> nullableColumn(n, get, "String", "", decls, of, to);
            case "Bool" -> nullableColumn(n, get, "Boolean", "", decls, of, to);
            case "Instant" -> {
                decls.add("    public Long " + n + "Millis;\n");
                of.add("row." + n + "Millis = " + get + ".isPresent() ? " + get + ".get().toEpochMilli() : null;");
                to.add("java.util.Optional.ofNullable(" + n + "Millis).map(java.time.Instant::ofEpochMilli)");
            }
            case "values" -> {
                decls.add("    public String " + n + ";\n");
                of.add("row." + n + " = " + get + ".isPresent() ? " + get + ".get()."
                        + carrier(base, module) + "() : null;");
                to.add("java.util.Optional.ofNullable(" + n + ").map(" + base + "::new)");
            }
            default -> {   // a nullable relation
                decls.add("    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.EAGER)\n"
                        + "    public " + base + "Jpa " + n + ";\n");
                of.add("row." + n + " = " + get + ".isPresent() ? " + base + "Jpa.of(" + get + ".get()) : null;");
                to.add("java.util.Optional.ofNullable(" + n + ").map(" + base + "Jpa::toRecord)");
            }
        }
    }

    private static void nullableColumn(String n, String get, String boxed, String suffix,
                                       List<String> decls, List<String> of, List<String> to) {
        decls.add("    public " + boxed + " " + n + suffix + ";\n");
        of.add("row." + n + " = " + get + ".isPresent() ? " + get + ".get() : null;");
        to.add("java.util.Optional.ofNullable(" + n + ")");
    }

    private void mapCollection(String n, String get, Ast.GenericType g, Ast.Module module,
                               Map<String, Ast.TypeDecl> types, List<String> decls, List<String> of, List<String> to) {
        boolean list = g.name().equals("List");
        String container = list ? "java.util.List" : "java.util.Set";
        String copy = list ? "java.util.ArrayList" : "java.util.LinkedHashSet";
        String order = list ? "    @jakarta.persistence.OrderColumn\n" : "";
        String element = baseName(g.args().get(0), types);
        String annotations = "    @jakarta.persistence.ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)\n"
                + order;
        switch (kindOf(element, module)) {
            case "Int" -> basicCollection(n, get, container, copy, "Long", annotations, decls, of, to);
            case "Text" -> basicCollection(n, get, container, copy, "String", annotations, decls, of, to);
            case "Bool" -> basicCollection(n, get, container, copy, "Boolean", annotations, decls, of, to);
            case "values" -> {
                decls.add(annotations + "    public " + container + "<String> " + n + ";\n");
                of.add("row." + n + " = " + get + ".stream().map(x -> x." + carrier(element, module)
                        + "()).collect(java.util.stream.Collectors.toCollection(" + copy + "::new));");
                to.add(collect(n + ".stream().map(" + element + "::new)", list));
            }
            default -> {   // a collection of components
                decls.add(annotations + "    public " + container + "<" + element + "Jpa> " + n + ";\n");
                of.add("row." + n + " = " + get + ".stream().map(" + element
                        + "Jpa::of).collect(java.util.stream.Collectors.toCollection(" + copy + "::new));");
                to.add(collect(n + ".stream().map(" + element + "Jpa::toRecord)", list));
            }
        }
    }

    private static void basicCollection(String n, String get, String container, String copy, String boxed,
                                        String annotations, List<String> decls, List<String> of, List<String> to) {
        decls.add(annotations + "    public " + container + "<" + boxed + "> " + n + ";\n");
        of.add("row." + n + " = new " + copy + "<>(" + get + ");");
        to.add(container.endsWith("List") ? "java.util.List.copyOf(" + n + ")" : "java.util.Set.copyOf(" + n + ")");
    }

    private static String collect(String stream, boolean list) {
        return list ? stream + ".toList()"
                : stream + ".collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))";
    }

    // ----- shared shape helpers --------------------------------------------------

    /** The base type name a field type erases to, resolving declared refined types. */
    private static String baseName(Ast.Type type, Map<String, Ast.TypeDecl> types) {
        return switch (type) {
            case Ast.TypeRef ref -> {
                Ast.TypeDecl d = types.get(ref.name());
                yield d != null ? d.base() : ref.name();
            }
            case Ast.RangedType r -> r.base();
            case Ast.GenericType g -> g.name();
        };
    }

    /** Classify a base name: a primitive kind, "values", "relation", or component (default). */
    private static String kindOf(String base, Ast.Module module) {
        switch (base) {
            case "Int", "Bool", "Money", "Instant", "Bytes" -> {
                return base;
            }
            case "Text", "Email" -> {
                return "Text";
            }
            default -> {
                for (Ast.Entity e : module.entities()) {
                    if (e.name().equals(base)) {
                        if (!e.values().isEmpty()) {
                            return "values";
                        }
                        return e.fields().stream().anyMatch(Ast.Field::id) ? "relation" : "component";
                    }
                }
                return "Text";   // a refined Text alias already erased by baseName
            }
        }
    }

    /** The carrier field of a values entity, e.g. Role.name. */
    private static String carrier(String entityName, Ast.Module module) {
        return module.entities().stream()
                .filter(e -> e.name().equals(entityName))
                .findFirst().orElseThrow()
                .fields().get(0).name();
    }

    private static String idJavaType(Ast.Entity e, Map<String, Ast.TypeDecl> types) {
        Ast.Field id = e.fields().stream().filter(Ast.Field::id).findFirst().orElseThrow();
        return Lowering.javaType(id.type(), types);
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ----- the persistence unit --------------------------------------------------

    private static String persistenceXml(String pkg, List<Ast.Entity> roots, List<Ast.Entity> components) {
        String classes = java.util.stream.Stream.concat(roots.stream(), components.stream())
                .map(e -> "    <class>" + pkg + "." + e.name() + "Jpa</class>")
                .collect(Collectors.joining("\n"));
        // The default unit is a runnable in-memory database, so the staged application
        // deploys as-is; tests override the url for isolation, deployments override all
        // of it with their real database.
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <persistence xmlns="https://jakarta.ee/xml/ns/persistence"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence https://jakarta.ee/xml/ns/persistence/persistence_3_0.xsd"
                             version="3.0">
                  <persistence-unit name="sky" transaction-type="RESOURCE_LOCAL">
                    <provider>org.eclipse.persistence.jpa.PersistenceProvider</provider>
                %s
                    <exclude-unlisted-classes>true</exclude-unlisted-classes>
                    <properties>
                      <property name="jakarta.persistence.jdbc.driver" value="org.h2.Driver"/>
                      <property name="jakarta.persistence.jdbc.url" value="jdbc:h2:mem:sky;DB_CLOSE_DELAY=-1"/>
                      <property name="jakarta.persistence.schema-generation.database.action" value="drop-and-create"/>
                    </properties>
                  </persistence-unit>
                </persistence>
                """.formatted(classes);
    }
}
