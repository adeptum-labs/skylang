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

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sources for the small runtime classes some SkyLang types lower to — {@code Money},
 * {@code Secret}, {@code Bytes} — staged into the generated project's package only when
 * the module actually uses them.
 */
public final class SupportClasses {

    public static final Set<String> NAMES = Set.of("Money", "Secret", "Bytes");

    private SupportClasses() {
    }

    /** Which support classes the module needs, scanning every type position and literal. */
    public static Set<String> used(Ast.Module module) {
        Set<String> used = new LinkedHashSet<>();
        Map<String, Ast.TypeDecl> types = Lowering.typesOf(module);
        for (Ast.TypeDecl d : module.types()) {
            addTypeName(d.base(), used);
        }
        for (Ast.Entity e : module.entities()) {
            for (Ast.Field f : e.fields()) {
                addType(f.type(), types, used);
                f.defaultValue().ifPresent(v -> addExpr(v, used));
            }
        }
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                for (Ast.Param p : m.params()) {
                    addType(p.type(), types, used);
                }
                addType(m.returnType(), types, used);
                m.requires().forEach(x -> addExpr(x, used));
                m.ensures().forEach(x -> addExpr(x, used));
                for (Ast.Example ex : m.examples()) {
                    ex.call().args().forEach(a -> addExpr(a, used));
                    ex.seed().ifPresent(sd -> sd.fields().forEach(fe -> addExpr(fe.expected(), used)));
                    switch (ex.result()) {
                        case Ast.ExprResult er -> addExpr(er.value(), used);
                        case Ast.EntityResult er -> er.fields().forEach(fe -> addExpr(fe.expected(), used));
                        case Ast.FieldsResult fr -> fr.fields().forEach(fe -> addExpr(fe.expected(), used));
                        case Ast.WhoseResult wr -> wr.expects().forEach(
                                we -> we.value().ifPresent(v -> addExpr(v, used)));
                        case Ast.RaisesResult ignored -> {
                        }
                        case Ast.NothingResult ignored -> {
                        }
                    }
                }
                for (Ast.Spec spec : m.specs()) {
                    spec.given().ifPresent(g -> addExpr(g, used));
                    spec.when().args().forEach(a -> addExpr(a, used));
                    for (Ast.ThenAssert t : spec.then()) {
                        if (t instanceof Ast.ThenExpr te) {
                            addExpr(te.expr(), used);
                        }
                    }
                }
            }
        }
        for (Ast.View v : module.views()) {
            for (Ast.Action a : v.actions()) {
                for (Ast.ActionArg arg : a.args()) {
                    switch (arg) {
                        case Ast.AskArg ask -> addType(ask.type(), types, used);
                        case Ast.ExprArg ea -> addExpr(ea.value(), used);
                    }
                }
            }
        }
        return used;
    }

    private static void addType(Ast.Type type, Map<String, Ast.TypeDecl> types, Set<String> used) {
        switch (type) {
            case Ast.TypeRef ref -> {
                Ast.TypeDecl declared = types.get(ref.name());
                addTypeName(declared != null ? declared.base() : ref.name(), used);
            }
            case Ast.GenericType g -> {
                addTypeName(g.name(), used);
                g.args().forEach(a -> addType(a, types, used));
            }
            case Ast.RangedType ignored -> {
            }
        }
    }

    private static void addTypeName(String name, Set<String> used) {
        if (NAMES.contains(name)) {
            used.add(name);
        }
    }

    private static void addExpr(Ast.Expr expr, Set<String> used) {
        switch (expr) {
            case Ast.MoneyLit ignored -> used.add("Money");
            case Ast.BinExpr b -> {
                addExpr(b.left(), used);
                addExpr(b.right(), used);
            }
            case Ast.CallExpr c -> c.args().forEach(a -> addExpr(a, used));
            case Ast.MemberExpr m -> addExpr(m.target(), used);
            case Ast.NotExpr n -> addExpr(n.value(), used);
            case Ast.OldExpr o -> addExpr(o.value(), used);
            case Ast.EmptyCheck e -> addExpr(e.value(), used);
            case Ast.AggExpr a -> {
                addExpr(a.value(), used);
                a.where().ifPresent(w -> addExpr(w, used));
                if (a.source() instanceof Ast.SourceExpr s) {
                    addExpr(s.expr(), used);
                }
            }
            default -> {
            }
        }
    }

    /** The source of one support class in the given package, or empty for other names. */
    public static Optional<String> source(String name, String pkg) {
        return switch (name) {
            case "Money" -> Optional.of(money(pkg));
            case "Secret" -> Optional.of(secret(pkg));
            case "Bytes" -> Optional.of(bytes(pkg));
            default -> Optional.empty();
        };
    }

    /** The effects every service in the module declares, in first-seen order. */
    public static Set<String> effectsOf(Ast.Module module) {
        Set<String> effects = new LinkedHashSet<>();
        for (Ast.Service s : module.services()) {
            effects.addAll(s.uses());
        }
        return effects;
    }

    /** True when any entity field defaults to {@code now} — the pinnable clock is needed. */
    public static boolean usesNow(Ast.Module module) {
        return module.entities().stream()
                .flatMap(e -> e.fields().stream())
                .anyMatch(f -> f.defaultValue().orElse(null) instanceof Ast.NameExpr n
                        && n.name().equals("now"));
    }

    public static String mail(String pkg) {
        return """
                package %s;

                /** The mail effect: sending is the only capability the budget grants. */
                @FunctionalInterface
                public interface Mail {

                    void send(String to, String subject, String body);
                }
                """.formatted(pkg);
    }

    public static String http(String pkg) {
        return """
                package %s;

                /** The http effect: outbound requests through the budgeted handle only. */
                @FunctionalInterface
                public interface Http {

                    String get(String url);
                }
                """.formatted(pkg);
    }

    public static String jdkHttp(String pkg) {
        return """
                package %s;

                /** The production http binding, on the JDK's own client. */
                public final class JdkHttp implements Http {

                    @Override
                    public String get(String url) {
                        try {
                            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                            java.net.http.HttpRequest request =
                                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build();
                            return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException("http get failed: " + url, e);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("http get interrupted: " + url, e);
                        }
                    }
                }
                """.formatted(pkg);
    }

    public static String skyClock(String pkg) {
        return """
                package %s;

                /**
                 * The clock behind '= now' field defaults. Production reads UTC; a test harness
                 * pins it so entity construction stays deterministic.
                 */
                public final class SkyClock {

                    private static volatile java.time.Clock clock = java.time.Clock.systemUTC();

                    private SkyClock() {
                    }

                    public static java.time.Instant now() {
                        return clock.instant();
                    }

                    public static java.time.Clock current() {
                        return clock;
                    }

                    public static void pin(java.time.Clock pinned) {
                        clock = pinned;
                    }
                }
                """.formatted(pkg);
    }

    /** CDI producers binding the declared effects for the web profile. */
    public static String effectProducers(String pkg, Set<String> effects) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/** Binds each declared effect for the deployed application. */\n");
        sb.append("@jakarta.enterprise.context.ApplicationScoped\n");
        sb.append("public class Effects {\n");
        if (effects.contains("db")) {
            // The provider is named directly: the container's own resolver only offers its
            // bundled provider, and the unit is ours, not container-managed. The transaction
            // type is forced too — inside a container the provider would otherwise detect
            // JTA and refuse the store's own transactions.
            sb.append("""

                        @jakarta.enterprise.inject.Produces
                        @jakarta.enterprise.context.ApplicationScoped
                        public Db db() {
                            java.util.Map<String, String> props = java.util.Map.of(
                                    "jakarta.persistence.transactionType", "RESOURCE_LOCAL",
                                    "eclipselink.target-server", "None");
                            return new JpaDb(new org.eclipse.persistence.jpa.PersistenceProvider()
                                    .createEntityManagerFactory("sky", props));
                        }
                    """);
        }
        if (effects.contains("clock")) {
            sb.append("""

                        @jakarta.enterprise.inject.Produces
                        @jakarta.enterprise.context.ApplicationScoped
                        public java.time.Clock clock() {
                            return java.time.Clock.systemUTC();
                        }
                    """);
        }
        if (effects.contains("mail")) {
            sb.append("""

                        @jakarta.enterprise.inject.Produces
                        @jakarta.enterprise.context.ApplicationScoped
                        public Mail sender() {
                            return (to, subject, body) -> {
                                throw new IllegalStateException("bind a real Mail sender before sending");
                            };
                        }
                    """);
        }
        if (effects.contains("http")) {
            sb.append("""

                        @jakarta.enterprise.inject.Produces
                        @jakarta.enterprise.context.ApplicationScoped
                        public Http client() {
                            return new JdkHttp();
                        }
                    """);
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** The substituted effects the generated tests wire in: deterministic and offline. */
    public static String testEffects(String pkg, Set<String> effects, boolean pinsClock) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/** Substituted effects for the generated tests: deterministic, offline. */\n");
        sb.append("final class TestEffects {\n\n");
        sb.append("    static final java.time.Instant NOW = java.time.Instant.parse(\"2026-01-01T00:00:00Z\");\n");
        if (pinsClock) {
            sb.append("\n    static {\n        SkyClock.pin(clock());\n    }\n");
        }
        sb.append("\n    private TestEffects() {\n    }\n");
        sb.append("\n    static java.time.Clock clock() {\n");
        sb.append("        return java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);\n    }\n");
        if (effects.contains("db")) {
            sb.append("""

                        private static final java.util.concurrent.atomic.AtomicInteger DB_SEQ =
                                new java.util.concurrent.atomic.AtomicInteger();

                        static Db db() {
                            java.util.Map<String, String> props = java.util.Map.of(
                                    "jakarta.persistence.jdbc.driver", "org.h2.Driver",
                                    "jakarta.persistence.jdbc.url",
                                            "jdbc:h2:mem:sky" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
                                    "jakarta.persistence.schema-generation.database.action", "drop-and-create");
                            return new JpaDb(jakarta.persistence.Persistence.createEntityManagerFactory("sky", props));
                        }
                    """);
        }
        if (effects.contains("mail")) {
            sb.append("\n    static Mail mail() {\n        return (to, subject, body) -> { };\n    }\n");
        }
        if (effects.contains("http")) {
            sb.append("\n    static Http http() {\n");
            sb.append("        return url -> { throw new UnsupportedOperationException(\"no outbound http under test\"); };\n");
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String money(String pkg) {
        return """
                package %s;

                import java.math.BigDecimal;
                import java.util.Currency;

                /**
                 * Fixed-point money with a currency. Arithmetic requires matching currencies and
                 * multiplying money by money is deliberately absent; equality ignores scale, so
                 * 10.0 EUR equals 10.00 EUR.
                 */
                public final class Money implements Comparable<Money> {

                    private final BigDecimal amount;
                    private final Currency currency;

                    private Money(BigDecimal amount, Currency currency) {
                        this.amount = amount;
                        this.currency = currency;
                    }

                    public static Money of(String amount, String currency) {
                        return new Money(new BigDecimal(amount), Currency.getInstance(currency));
                    }

                    public BigDecimal amount() {
                        return amount;
                    }

                    public Currency currency() {
                        return currency;
                    }

                    public Money plus(Money other) {
                        return new Money(amount.add(same(other).amount), currency);
                    }

                    public Money minus(Money other) {
                        return new Money(amount.subtract(same(other).amount), currency);
                    }

                    public Money times(long factor) {
                        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
                    }

                    @Override
                    public int compareTo(Money other) {
                        return amount.compareTo(same(other).amount);
                    }

                    private Money same(Money other) {
                        if (!currency.equals(other.currency)) {
                            throw new IllegalArgumentException(
                                    "currency mismatch: " + currency + " vs " + other.currency);
                        }
                        return other;
                    }

                    @Override
                    public boolean equals(Object o) {
                        return o instanceof Money m && currency.equals(m.currency)
                                && amount.compareTo(m.amount) == 0;
                    }

                    @Override
                    public int hashCode() {
                        return amount.stripTrailingZeros().hashCode() * 31 + currency.hashCode();
                    }

                    @Override
                    public String toString() {
                        return amount.toPlainString() + " " + currency.getCurrencyCode();
                    }
                }
                """.formatted(pkg);
    }

    private static String secret(String pkg) {
        return """
                package %s;

                /**
                 * A value that must not leak: its string form is masked, so logging or rendering
                 * a carrier object can never expose the payload. Read it only via reveal().
                 */
                public final class Secret<T> {

                    private final T value;

                    private Secret(T value) {
                        this.value = value;
                    }

                    public static <T> Secret<T> of(T value) {
                        return new Secret<>(value);
                    }

                    public T reveal() {
                        return value;
                    }

                    @Override
                    public boolean equals(Object o) {
                        return o instanceof Secret<?> s && java.util.Objects.equals(value, s.value);
                    }

                    @Override
                    public int hashCode() {
                        return java.util.Objects.hashCode(value);
                    }

                    @Override
                    public String toString() {
                        return "\\u2022\\u2022\\u2022";
                    }
                }
                """.formatted(pkg);
    }

    private static String bytes(String pkg) {
        return """
                package %s;

                import java.util.Arrays;

                /**
                 * An immutable byte sequence with content equality; a bare byte[] inside a record
                 * would compare by reference and break example tests.
                 */
                public final class Bytes {

                    private final byte[] data;

                    private Bytes(byte[] data) {
                        this.data = data.clone();
                    }

                    public static Bytes of(byte[] data) {
                        return new Bytes(data);
                    }

                    public static Bytes ofUtf8(String text) {
                        return new Bytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    public byte[] toByteArray() {
                        return data.clone();
                    }

                    public int size() {
                        return data.length;
                    }

                    public long length() {
                        return data.length;
                    }

                    @Override
                    public boolean equals(Object o) {
                        return o instanceof Bytes b && Arrays.equals(data, b.data);
                    }

                    @Override
                    public int hashCode() {
                        return Arrays.hashCode(data);
                    }

                    @Override
                    public String toString() {
                        return "Bytes(" + data.length + ")";
                    }
                }
                """.formatted(pkg);
    }
}
