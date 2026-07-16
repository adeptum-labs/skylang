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

package com.adeptum.skylang;

import com.adeptum.skylang.backend.FacesViewStager;
import com.adeptum.skylang.backend.JvmProfile;
import com.adeptum.skylang.backend.Lowering;
import com.adeptum.skylang.backend.Profile;
import com.adeptum.skylang.backend.ProjectStager;
import com.adeptum.skylang.deps.Budget;
import com.adeptum.skylang.deps.Resolved;
import com.adeptum.skylang.freeze.Hashing;
import com.adeptum.skylang.freeze.Lock;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.synth.PromptBuilder;
import com.adeptum.skylang.synth.UiPromptBuilder;
import com.adeptum.skylang.verify.EffectLinter;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;
import com.adeptum.skylang.verify.VerifyReport;
import com.adeptum.skylang.verify.ViewVerifier;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Drives the build pipeline for one already-checked module (guide §12): resolve each method body and
 * view from the freeze store or the model, stage the target project, verify it, then freeze the
 * newly-synthesized units. The model is called only for units whose spec hash changed. Method bodies
 * are verified by the target toolchain; views are verified against their structural expectations.
 */
@lombok.extern.slf4j.Slf4j
public final class Pipeline {

    /**
     * The exit-code convention: 0 success; 1 a hard-layer error (raised before the pipeline
     * runs); 2 a verification failure — a body could not satisfy its contracts, examples, or
     * policies; 3 a configuration or provider error (raised by the CLI around the pipeline).
     */
    public static final int VERIFICATION_FAILED = 2;

    private final Llm client;
    private final Verifier verifier;
    private final Profile profile;
    private final Budget deps;
    private final UiPromptBuilder uiPrompts = new UiPromptBuilder();
    private FacesViewStager facesViewStager = new FacesViewStager();
    private final ViewVerifier viewVerifier = new ViewVerifier();
    private final int maxRegenerations;

    /** Five candidates per method by default: the first proposal plus four regenerations. */
    public Pipeline(Llm client, Verifier verifier) {
        this(client, verifier, 4);
    }

    public Pipeline(Llm client, Verifier verifier, int maxRegenerations) {
        this(client, verifier, maxRegenerations, JvmProfile.INSTANCE);
    }

    public Pipeline(Llm client, Verifier verifier, int maxRegenerations, Profile profile) {
        this(client, verifier, maxRegenerations, profile, Budget.NONE);
    }

    public Pipeline(Llm client, Verifier verifier, int maxRegenerations, Profile profile, Budget deps) {
        this.client = client;
        this.verifier = verifier;
        this.maxRegenerations = maxRegenerations;
        this.profile = profile;
        this.deps = deps;
    }

    /** One unit of work: a method plus everything needed to freeze it. */
    private static final class Unit {
        final Ast.Service service;
        final Ast.Method method;
        final String key;
        final String specHash;
        String body;
        boolean fresh;   // true = synthesized this build (needs freezing on success)
        boolean stale;   // true = an older frozen entry existed (fresh replaces it)
        int candidate = 1;   // how many bodies have been proposed for this method this build

        Unit(Ast.Service service, Ast.Method method, String key, String specHash) {
            this.service = service;
            this.method = method;
            this.key = key;
            this.specHash = specHash;
        }
    }

    /** One unit of work: a view plus its synthesized markup and backing bean. */
    private static final class ViewUnit {
        final Ast.View view;
        final String key;
        final String specHash;
        UiPromptBuilder.UiArtifact artifact;
        boolean fresh;

        ViewUnit(Ast.View view, String key, String specHash) {
            this.view = view;
            this.key = key;
            this.specHash = specHash;
        }
    }

    /** One unit of work: a flow plus its synthesized navigation graph. */
    private static final class FlowUnit {
        final Ast.Flow flow;
        final String key;
        final String specHash;
        String graphJson;
        boolean fresh;

        FlowUnit(Ast.Flow flow, String key, String specHash) {
            this.flow = flow;
            this.key = key;
            this.specHash = specHash;
        }
    }

    /** One unit of work: a component plus its synthesized composite markup. */
    private static final class ComponentUnit {
        final Ast.Component component;
        final String key;
        final String specHash;
        String markup;
        boolean fresh;

        ComponentUnit(Ast.Component component, String key, String specHash) {
            this.component = component;
            this.key = key;
            this.specHash = specHash;
        }
    }

    /** @return 0 on success, non-zero on a verification/synthesis failure. */
    public int build(Ast.Module module, Path lockPath, Path buildDir, PrintStream out, PrintStream err) {
        return build(module, lockPath, buildDir, out, err, false);
    }

    /** Stage views in preview mode, so the served pages carry the studio's in-page selection script. */
    public Pipeline preview() {
        this.facesViewStager = new FacesViewStager(true);
        return this;
    }

    /**
     * @param recheck run the staged verification even when every unit is frozen — an offline
     *                re-render of the whole project (tests, render checks, the visual gate) that
     *                catches environment or toolchain drift without a single model call
     * @return 0 on success, non-zero on a verification/synthesis failure.
     */
    public int build(Ast.Module module, Path lockPath, Path buildDir, PrintStream out, PrintStream err,
                     boolean recheck) {
        log.debug("build {} -> {} (profile {}, recheck={})", module.name(), buildDir, profile.id(), recheck);
        Lock lock = Lock.load(lockPath);

        // A body frozen as one profile's language has no meaning under another: switching the
        // profile invalidates the whole lock, and every method regenerates for the new target.
        boolean retarget = !lock.profileId().isEmpty() && !lock.profileId().equals(profile.id());
        if (retarget) {
            out.printf("  profile %s   (changed from %s; regenerating all bodies)%n%n",
                    profile.id(), lock.profileId());
        }
        if (!retarget) {
            out.println("  profile " + profile.id());
        }
        for (Resolved r : deps.declared()) {
            out.println("  " + r.name() + " " + r.constraint() + " \u25b8 resolved to "
                    + r.coordinates().get(0) + " (pinned)");
        }
        if (!deps.declared().isEmpty()) {
            out.println();
        }
        lock.setProfile(profile.id(), profile.version());
        Map<String, Lock.Dep> pinned = new LinkedHashMap<>();
        for (Resolved r : deps.declared()) {
            pinned.put(r.name(), new Lock.Dep(r.constraint(), r.version(), r.coordinates()));
        }
        lock.setDeps(pinned);

        // Resolve method bodies: reuse a frozen body when its hash matches, else synthesize.
        List<Unit> units = new ArrayList<>();
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                String key = ProjectStager.methodKey(module.name(), s.name(), m.name());
                units.add(new Unit(s, m, key,
                        Hashing.sha256(specString(module, m, profile, deps.declared()))));
            }
        }
        boolean anyFresh = false;
        List<Unit> toSynthesize = new ArrayList<>();
        for (Unit u : units) {
            var frozen = retarget ? java.util.Optional.<Lock.Entry>empty() : lock.get(u.key);
            if (frozen.isPresent() && frozen.get().specHash().equals(u.specHash)) {
                u.body = frozen.get().body();
            } else if (u.method.nativeBody().isPresent()) {
                u.stale = frozen.isPresent();
                // The escape hatch: the body is the author's, never the model's — but the
                // effects budget and the contracts hold for it all the same.
                u.body = Lock.canonical(u.method.nativeBody().get());
                List<String> violations = new ArrayList<>(
                        EffectLinter.violations(u.body, u.service.uses(), module));
                violations.addAll(EffectLinter.dependencyViolations(u.body, deps));
                if (!violations.isEmpty()) {
                    err.println("build failed: " + u.key + " (native) reaches outside its budget:");
                    violations.forEach(v -> err.println("  " + v));
                    return VERIFICATION_FAILED;
                }
                u.fresh = true;
                anyFresh = true;
            } else {
                u.stale = frozen.isPresent();
                toSynthesize.add(u);
                u.fresh = true;
                anyFresh = true;
            }
        }
        if (!synthesizeAll(module, toSynthesize, out, err)) {
            return VERIFICATION_FAILED;
        }

        // Resolve views: reuse frozen markup, else synthesize and dispose against its expectations.
        List<ViewUnit> viewUnits = new ArrayList<>();
        for (Ast.View v : module.views()) {
            String key = ProjectStager.viewKey(module.name(), v.name());
            viewUnits.add(new ViewUnit(v, key,
                    Hashing.sha256(viewSpecString(module, v, profile, deps.declared()))));
        }
        boolean anyViewFresh = false;
        for (ViewUnit u : viewUnits) {
            var frozen = retarget ? java.util.Optional.<Lock.ViewEntry>empty() : lock.getView(u.key);
            if (frozen.isPresent() && frozen.get().specHash().equals(u.specHash)) {
                u.artifact = new UiPromptBuilder.UiArtifact(frozen.get().markup(), frozen.get().bean());
            } else if (resolveView(module, u, out)) {
                u.fresh = true;
                anyViewFresh = true;
            } else {
                err.println("build failed: view " + u.view.name()
                        + " did not satisfy its expectations after " + (maxRegenerations + 1) + " attempt(s).");
                return VERIFICATION_FAILED;
            }
        }

        // Resolve components and flows: frozen artifacts are reused; fresh ones synthesize
        // against their structural contracts, exactly like a view.
        List<ComponentUnit> componentUnits = new ArrayList<>();
        boolean anyComponentFresh = false;
        for (Ast.Component c : module.components()) {
            ComponentUnit u = new ComponentUnit(c, module.name() + "." + c.name(),
                    Hashing.sha256(componentSpecString(module, c, profile, deps.declared())));
            var frozen = retarget ? java.util.Optional.<Lock.Entry>empty() : lock.getComponent(u.key);
            if (frozen.isPresent() && frozen.get().specHash().equals(u.specHash)) {
                u.markup = frozen.get().body();
            } else if (resolveComponent(module, u, out)) {
                u.fresh = true;
                anyComponentFresh = true;
            } else {
                err.println("build failed: component " + c.name()
                        + " did not satisfy its declaration after " + (maxRegenerations + 1)
                        + " attempt(s).");
                return VERIFICATION_FAILED;
            }
            componentUnits.add(u);
        }
        List<FlowUnit> flowUnits = new ArrayList<>();
        boolean anyFlowFresh = false;
        for (Ast.Flow f : module.flows()) {
            FlowUnit u = new FlowUnit(f, module.name() + "." + f.name(),
                    Hashing.sha256(flowSpecString(module, f, profile, deps.declared())));
            var frozen = retarget ? java.util.Optional.<Lock.Entry>empty() : lock.getFlow(u.key);
            if (frozen.isPresent() && frozen.get().specHash().equals(u.specHash)) {
                u.graphJson = frozen.get().body();
            } else if (resolveFlow(u, out)) {
                u.fresh = true;
                anyFlowFresh = true;
            } else {
                err.println("build failed: flow " + f.name()
                        + " did not satisfy its expectations after " + (maxRegenerations + 1)
                        + " attempt(s).");
                return VERIFICATION_FAILED;
            }
            flowUnits.add(u);
        }

        // Stage the project. If nothing changed, everything is already verified — skip the test run.
        profile.stage(module, bodyMap(units), deps.declared(), buildDir);
        stageInterfaceUnits(module, componentUnits, flowUnits, buildDir);
        if (!module.views().isEmpty()) {
            facesViewStager.stage(module, viewArtifacts(viewUnits), baselines(viewUnits, lock), buildDir);
            clearCaptures(buildDir);   // a stale rasterization must never be adopted as a baseline
        }
        boolean pureRecheck = recheck && !anyFresh && !anyViewFresh;
        if (pureRecheck) {
            StringBuilder reading = new StringBuilder("  reading " + units.size() + " frozen bodies");
            if (!viewUnits.isEmpty()) {
                reading.append(", ").append(viewUnits.size())
                        .append(viewUnits.size() == 1 ? " page" : " pages");
            }
            if (!module.flows().isEmpty()) {
                reading.append(", ").append(module.flows().size())
                        .append(module.flows().size() == 1 ? " flow" : " flows");
            }
            if (!module.components().isEmpty()) {
                reading.append(", ").append(module.components().size())
                        .append(module.components().size() == 1 ? " component" : " components");
            }
            out.println(reading.append(" from sky.lock ... no model calls"));
        }
        if (anyFresh || anyViewFresh || recheck) {
            int attempts = 0;
            VerificationResult result;
            try (Ticker ticker = Ticker.start(out, "verifying staged project")) {
                result = verifier.verify(buildDir);
            }
            boolean regenerable = units.stream()
                    .anyMatch(u -> u.fresh && u.method.nativeBody().isEmpty());
            while (!result.passed() && regenerable && attempts < maxRegenerations) {
                attempts++;
                regenerateFailing(module, units, result.output(), attempts, out);
                profile.stage(module, bodyMap(units), deps.declared(), buildDir);
                try (Ticker ticker = Ticker.start(out, "re-verifying staged project")) {
                    result = verifier.verify(buildDir);
                }
            }
            if (!result.passed()) {
                return reportVerifyFailure(module, units, result.output(), err);
            }
            if (pureRecheck) {
                out.println("  re-verifying contracts, examples, policies, expectations ... all ✓");
            }
            for (Unit u : units) {
                if (u.candidate > 1) {
                    out.printf("  %-28s ▸ candidate %d: all contracts ✓%n",
                            label(u), u.candidate);
                }
                if (u.fresh) {
                    lock.put(u.key, new Lock.Entry(u.specHash, Lock.canonical(u.body)));
                }
            }
        }

        // Freeze the fresh views — they were already disposed against their expectations above.
        for (ViewUnit u : viewUnits) {
            if (u.fresh) {
                lock.putView(u.key, new Lock.ViewEntry(u.specHash,
                        Lock.canonical(u.artifact.markup()), Lock.canonical(u.artifact.bean())));
            }
        }
        for (ComponentUnit u : componentUnits) {
            if (u.fresh) {
                lock.putComponent(u.key, new Lock.Entry(u.specHash, Lock.canonical(u.markup)));
            }
        }
        for (FlowUnit u : flowUnits) {
            if (u.fresh) {
                lock.putFlow(u.key, new Lock.Entry(u.specHash, Lock.canonical(u.graphJson)));
            }
        }

        boolean anyVisualFrozen = adoptVisualCaptures(viewUnits, lock, buildDir, out);
        if (anyFresh || anyViewFresh || anyComponentFresh || anyFlowFresh || anyVisualFrozen) {
            log.debug("froze sky.lock at {}", lockPath);
            lock.save(lockPath);
        }

        report(module, units, viewUnits, out);
        reportInterfaceUnits(flowUnits, componentUnits, out);
        out.println("staged: " + buildDir);
        return 0;
    }

    private String synthesize(Ast.Module module, Unit u) {
        String reply = client.complete(profile.systemPrompt(),
                profile.userPrompt(module, u.service, u.method, deps.declared()));
        return profile.extractBody(reply);
    }

    /**
     * Synthesize a body and hold it to the service's effects budget, regenerating until
     * it stays inside or the attempts run out. Nothing outside the budget may reach the
     * staged project, let alone the freeze store.
     */
    /**
     * Each method's generation is independent, so a first build synthesizes many bodies
     * concurrently — the wall-clock cost of a large module grows far more slowly than its
     * method count. Per-unit transcripts are buffered and replayed in declaration order.
     */
    private boolean synthesizeAll(Ast.Module module, List<Unit> fresh, PrintStream out, PrintStream err) {
        if (fresh.isEmpty()) {
            return true;
        }
        int total = fresh.size();
        if (total == 1) {
            Unit only = fresh.get(0);
            try (Ticker ticker = Ticker.start(out, "synthesizing body " + label(only))) {
                return resolveBody(module, only, out, err);
            }
        }
        Ticker ticker = Ticker.start(out, "synthesizing " + total + " bodies (0/" + total + ")");
        record Attempt(java.io.ByteArrayOutputStream transcript,
                       java.util.concurrent.Future<Boolean> passed) {
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, total));
        var done = new java.util.concurrent.atomic.AtomicInteger();
        try {
            List<Attempt> attempts = new ArrayList<>();
            for (Unit u : fresh) {
                var transcript = new java.io.ByteArrayOutputStream();
                var stream = new PrintStream(transcript, true, java.nio.charset.StandardCharsets.UTF_8);
                attempts.add(new Attempt(transcript, pool.submit(() -> {
                    boolean ok = resolveBody(module, u, stream, stream);
                    ticker.label("synthesizing " + total + " bodies ("
                            + done.incrementAndGet() + "/" + total + ", " + label(u) + ")");
                    return ok;
                })));
            }
            boolean all = true;
            List<String> texts = new ArrayList<>();
            List<Boolean> flags = new ArrayList<>();
            for (Attempt attempt : attempts) {
                boolean passed;
                try {
                    passed = attempt.passed().get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException cause) {
                        throw cause;
                    }
                    throw new IllegalStateException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                flags.add(passed);
                texts.add(attempt.transcript().toString(java.nio.charset.StandardCharsets.UTF_8));
                all &= passed;
            }
            // Stop the indicator before replaying buffered per-unit transcripts, so no animation
            // frame interleaves with them.
            ticker.close();
            for (int i = 0; i < texts.size(); i++) {
                if (!texts.get(i).isEmpty()) {
                    (flags.get(i) ? out : err).print(texts.get(i));
                }
            }
            return all;
        } finally {
            ticker.close();
            pool.shutdownNow();
        }
    }

    private boolean resolveBody(Ast.Module module, Unit u, PrintStream out, PrintStream err) {
        u.body = synthesize(module, u);
        List<String> violations = lint(module, u);
        int attempts = 0;
        while (!violations.isEmpty() && attempts < maxRegenerations) {
            attempts++;
            out.println("  " + u.key + " broke its budget " + violations
                    + " — regenerating (attempt " + attempts + ")");
            u.body = synthesize(module, u);
            violations = lint(module, u);
        }
        if (!violations.isEmpty()) {
            err.println("build failed: " + u.key + " reaches outside its budget:");
            violations.forEach(v -> err.println("  " + v));
            return false;
        }
        return true;
    }

    /** Both budgets at once: the effects a service uses and the dependencies the manifest requires. */
    private List<String> lint(Ast.Module module, Unit u) {
        List<String> violations = new ArrayList<>(
                EffectLinter.violations(u.body, u.service.uses(), module));
        violations.addAll(EffectLinter.dependencyViolations(u.body, deps));
        return violations;
    }

    /** Synthesize a view and re-synthesize until its expectations hold or the attempts run out. */
    private boolean resolveView(Ast.Module module, ViewUnit u, PrintStream out) {
        try (Ticker ticker = Ticker.start(out, "synthesizing page " + u.view.name())) {
            u.artifact = synthesizeView(module, u.view);
        }
        java.util.Set<String> imageColumns =
                com.adeptum.skylang.verify.ViewVerifier.bytesColumns(module, u.view);
        List<String> unmet = viewVerifier.unmetExpectations(module, u.view, u.artifact.markup(), imageColumns);
        int attempts = 0;
        while (!unmet.isEmpty() && attempts < maxRegenerations) {
            attempts++;
            out.println("  page " + u.view.name() + " unmet " + unmet + " (attempt " + attempts + ")");
            try (Ticker ticker = Ticker.start(out, "regenerating page " + u.view.name())) {
                u.artifact = synthesizeView(module, u.view);
            }
            unmet = viewVerifier.unmetExpectations(module, u.view, u.artifact.markup(), imageColumns);
        }
        return unmet.isEmpty();
    }

    private UiPromptBuilder.UiArtifact synthesizeView(Ast.Module module, Ast.View view) {
        String reply = client.complete(uiPrompts.system(UiPromptBuilder.STANDARD), uiPrompts.user(module, view));
        return uiPrompts.extractArtifacts(reply);
    }

    private final com.adeptum.skylang.verify.FlowVerifier flowVerifier =
            new com.adeptum.skylang.verify.FlowVerifier();
    private final com.adeptum.skylang.verify.ComponentVerifier componentVerifier =
            new com.adeptum.skylang.verify.ComponentVerifier();

    /** Synthesize a composite component and dispose it against its declaration. */
    private boolean resolveComponent(Ast.Module module, ComponentUnit u, PrintStream out) {
        try (Ticker ticker = Ticker.start(out, "synthesizing component " + u.component.name())) {
            u.markup = uiPrompts.extractFenced(client.complete(
                    uiPrompts.componentSystem(), uiPrompts.componentUser(module, u.component)), "xhtml");
        }
        List<String> unmet = componentVerifier.unmetExpectations(u.component, u.markup);
        int attempts = 0;
        while (!unmet.isEmpty() && attempts < maxRegenerations) {
            attempts++;
            out.println("  component " + u.component.name() + " unmet " + unmet
                    + " (attempt " + attempts + ")");
            try (Ticker ticker = Ticker.start(out, "regenerating component " + u.component.name())) {
                u.markup = uiPrompts.extractFenced(client.complete(
                        uiPrompts.componentSystem(), uiPrompts.componentUser(module, u.component)), "xhtml");
            }
            unmet = componentVerifier.unmetExpectations(u.component, u.markup);
        }
        return unmet.isEmpty();
    }

    /** Synthesize a flow's navigation graph and walk it against the declared expectations. */
    private boolean resolveFlow(FlowUnit u, PrintStream out) {
        try (Ticker ticker = Ticker.start(out, "synthesizing flow " + u.flow.name())) {
            u.graphJson = uiPrompts.extractFenced(client.complete(
                    uiPrompts.flowSystem(), uiPrompts.flowUser(u.flow)), "json");
        }
        List<String> unmet = flowVerifier.unmetExpectations(u.flow, flowVerifier.parse(u.graphJson));
        int attempts = 0;
        while (!unmet.isEmpty() && attempts < maxRegenerations) {
            attempts++;
            out.println("  flow " + u.flow.name() + " unmet " + unmet
                    + " (attempt " + attempts + ")");
            try (Ticker ticker = Ticker.start(out, "regenerating flow " + u.flow.name())) {
                u.graphJson = uiPrompts.extractFenced(client.complete(
                        uiPrompts.flowSystem(), uiPrompts.flowUser(u.flow)), "json");
            }
            unmet = flowVerifier.unmetExpectations(u.flow, flowVerifier.parse(u.graphJson));
        }
        return unmet.isEmpty();
    }

    /** Components land as composite XHTML; flows as an ordinary navigation-table class. */
    private void stageInterfaceUnits(Ast.Module module, List<ComponentUnit> components,
                                     List<FlowUnit> flows, Path buildDir) {
        try {
            if (!components.isEmpty()) {
                Path dir = buildDir.resolve("src/main/resources/components");
                Files.createDirectories(dir);
                for (ComponentUnit u : components) {
                    String file = Character.toLowerCase(u.component.name().charAt(0))
                            + u.component.name().substring(1) + ".xhtml";
                    Files.writeString(dir.resolve(file), u.markup + "\n");
                }
            }
            for (FlowUnit u : flows) {
                var graph = flowVerifier.parse(u.graphJson);
                Path dir = buildDir.resolve("src/main/java").resolve(module.name());
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(u.flow.name() + "Flow.java"),
                        flowClass(module.name(), u.flow.name(), graph));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage interface units under " + buildDir, e);
        }
    }

    /** The staged flow: the verified navigation graph as plain, keepable Java. */
    private static String flowClass(String pkg, String name,
                                    com.adeptum.skylang.verify.FlowVerifier.Graph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/** The ").append(name).append(" flow: guarded navigation, generated and verified. */\n");
        sb.append("public final class ").append(name).append("Flow {\n\n");
        sb.append("    public static final java.util.List<String> STEPS = java.util.List.of(");
        sb.append(graph.steps().stream().map(st -> "\"" + st + "\"")
                .collect(java.util.stream.Collectors.joining(", "))).append(");\n\n");
        sb.append("    public static final java.util.Map<String, String> TRANSITIONS = java.util.Map.of(");
        sb.append(graph.transitions().entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\", \"" + e.getValue() + "\"")
                .collect(java.util.stream.Collectors.joining(", "))).append(");\n\n");
        sb.append("    private ").append(name).append("Flow() {\n    }\n}\n");
        return sb.toString();
    }

    /** The interface units' transcript: frozen lines, and the walk for a fresh flow. */
    private void reportInterfaceUnits(List<FlowUnit> flows, List<ComponentUnit> components,
                                      PrintStream out) {
        for (ComponentUnit u : components) {
            String label = "component " + u.component.name();
            if (!u.fresh) {
                out.printf("  %-24s \u25b8 frozen @ %s   (unchanged)%n",
                        label, Hashing.shortHash(u.specHash));
            } else {
                out.printf("  %-24s \u25b8 synthesized \u25b8 verified \u25b8 frozen @ %s%s%s%n",
                        label, Hashing.shortHash(u.specHash),
                        countedInvariant(u.component.expects().size(), "expect"),
                        countedInvariant(u.component.appears().size(), "appears"));
            }
        }
        for (FlowUnit u : flows) {
            String label = "flow " + u.flow.name();
            if (!u.fresh) {
                out.printf("  %-24s \u25b8 frozen @ %s   (unchanged)%n",
                        label, Hashing.shortHash(u.specHash));
                continue;
            }
            out.printf("  %-24s \u25b8 synthesized \u25b8 verified \u25b8 frozen @ %s%s%n",
                    label, Hashing.shortHash(u.specHash),
                    countedInvariant(u.flow.expects().size(), "expect"));
            var graph = flowVerifier.parse(u.graphJson);
            if (graph != null) {
                for (String line : flowVerifier.walkLines(u.flow, graph)) {
                    out.printf("  %-24s \u25b8 %s%n", "", line);
                }
            }
        }
    }

    /** Canonical text whose hash freezes a flow: the profile, deps, and the declaration. */
    static String flowSpecString(Ast.Module module, Ast.Flow flow, Profile profile,
                                 List<Resolved> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(profile.id()).append('@').append(profile.version()).append('\n');
        appendDeps(sb, deps);
        sb.append("flow ").append(flow).append('\n');
        return sb.toString();
    }

    /** Canonical text whose hash freezes a component: profile, deps, entities, declaration. */
    static String componentSpecString(Ast.Module module, Ast.Component component, Profile profile,
                                      List<Resolved> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(profile.id()).append('@').append(profile.version()).append('\n');
        appendDeps(sb, deps);
        for (Ast.Entity e : module.entities()) {
            sb.append("entity ").append(e).append('\n');
        }
        sb.append("component ").append(component).append('\n');
        return sb.toString();
    }

    private static Map<String, String> bodyMap(List<Unit> units) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Unit u : units) {
            map.put(u.key, u.body);
        }
        return map;
    }

    /** The frozen visual baseline of every view that was reused as-is; a fresh view has none yet. */
    private static Map<String, byte[]> baselines(List<ViewUnit> viewUnits, Lock lock) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (ViewUnit u : viewUnits) {
            if (!u.fresh) {
                lock.getView(u.key)
                        .map(Lock.ViewEntry::visual)
                        .filter(v -> !v.isEmpty())
                        .ifPresent(v -> map.put(u.view.name(), Base64.getDecoder().decode(v)));
            }
        }
        return map;
    }

    /**
     * Freeze the rasterizations the staged visual gate left behind: a view without a baseline gets
     * the capture as its baseline, so the next verified build diffs against it instead of capturing.
     */
    private boolean adoptVisualCaptures(List<ViewUnit> viewUnits, Lock lock, Path buildDir, PrintStream out) {
        boolean any = false;
        for (ViewUnit u : viewUnits) {
            var entry = lock.getView(u.key);
            Path capture = buildDir.resolve("target/sky-visual").resolve(u.view.name() + ".png");
            if (entry.isPresent() && entry.get().visual().isEmpty() && Files.exists(capture)) {
                try {
                    lock.putView(u.key, entry.get().withVisual(
                            Base64.getEncoder().encodeToString(Files.readAllBytes(capture))));
                    out.printf("  %-28s visual baseline frozen%n", "view " + u.view.name());
                    any = true;
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read visual capture " + capture, e);
                }
            }
        }
        return any;
    }

    private static void clearCaptures(Path buildDir) {
        Path captures = buildDir.resolve("target/sky-visual");
        try (var files = Files.exists(captures) ? Files.list(captures) : Stream.<Path>empty()) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clear stale visual captures under " + captures, e);
        }
    }

    private static Map<String, UiPromptBuilder.UiArtifact> viewArtifacts(List<ViewUnit> viewUnits) {
        Map<String, UiPromptBuilder.UiArtifact> map = new LinkedHashMap<>();
        for (ViewUnit u : viewUnits) {
            map.put(u.view.name(), u.artifact);
        }
        return map;
    }

    /** The build transcript, in the shape the freeze model promises: per unit, one line. */
    private void report(Ast.Module module, List<Unit> units, List<ViewUnit> viewUnits, PrintStream out) {
        int width = 24;
        for (Unit u : units) {
            width = Math.max(width, (u.service.name() + "." + u.method.name()).length());
        }
        for (ViewUnit u : viewUnits) {
            width = Math.max(width, ("page " + u.view.name()).length());
        }
        for (Unit u : units) {
            String label = u.service.name() + "." + u.method.name();
            if (!u.fresh) {
                out.printf("  %-" + width + "s \u25b8 frozen @ %s   (unchanged)%n",
                        label, Hashing.shortHash(u.specHash));
                continue;
            }
            String how = u.method.nativeBody().isPresent() ? "native"
                    : u.stale ? "regenerated" : "synthesized";
            if (!profile.tag().isEmpty()) {
                how += " (" + profile.tag() + ")";
            }
            int contracts = u.method.requires().size() + u.method.ensures().size()
                    + u.method.raises().size();
            int examples = u.method.examples().size() + u.method.specs().size();
            out.printf("  %-" + width + "s \u25b8 %s \u25b8 verified \u25b8 frozen @ %s%s%s%n",
                    label, how, Hashing.shortHash(u.specHash),
                    counted(contracts, "contract"), counted(examples, "example"));
        }
        for (ViewUnit u : viewUnits) {
            String label = "page " + u.view.name();
            if (!u.fresh) {
                out.printf("  %-" + width + "s \u25b8 frozen @ %s   (unchanged)%n",
                        label, Hashing.shortHash(u.specHash));
            } else {
                out.printf("  %-" + width + "s \u25b8 synthesized \u25b8 verified \u25b8 frozen @ %s%s%s%n",
                        label, Hashing.shortHash(u.specHash),
                        countedInvariant(u.view.expects().size(), "expect"),
                        countedInvariant(u.view.appears().size(), "appears"));
            }
        }
        for (Ast.Policy policy : module.policies()) {
            out.println("  policy " + policy.name() + "   \u2713 checked against "
                    + units.size() + (units.size() == 1 ? " body" : " bodies"));
        }
    }

    private static String counted(int n, String noun) {
        return n == 0 ? "" : "   \u2713 " + n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The interface nouns read the same in any count: "2 expect", "3 appears". */
    private static String countedInvariant(int n, String noun) {
        return n == 0 ? "" : "   \u2713 " + n + " " + noun;
    }

    private static String label(Unit u) {
        return u.service.name() + "." + u.method.name();
    }

    /**
     * One retry round: name each failing candidate's violated clauses, then regenerate only the
     * implicated methods. When the toolchain output cannot be attributed to methods (say, a
     * compile error), every fresh synthesized body regenerates \u2014 the conservative fallback.
     */
    private void regenerateFailing(Ast.Module module, List<Unit> units, String output,
                                   int attempt, PrintStream out) {
        List<VerifyReport.ClauseFailure> failures = VerifyReport.clauseFailures(output);
        var implicated = failures.stream()
                .map(f -> f.service() + "." + f.method())
                .collect(java.util.stream.Collectors.toSet());
        if (implicated.isEmpty()) {
            out.println("  verification failed \u2014 regenerating synthesized bodies (attempt " + attempt + ")");
        }
        for (Unit u : units) {
            // A native body is the author's to fix; only model-written bodies regenerate.
            if (!u.fresh || u.method.nativeBody().isPresent()
                    || (!implicated.isEmpty() && !implicated.contains(label(u)))) {
                continue;
            }
            failures.stream().filter(f -> (f.service() + "." + f.method()).equals(label(u)))
                    .forEach(f -> out.printf("  %-28s \u25b8 candidate %d: %s  \u2717 FAILED%n",
                            label(u), u.candidate, f.clause()));
            try (Ticker ticker = Ticker.start(out, "regenerating " + label(u))) {
                u.body = synthesize(module, u);
            }
            u.candidate++;
        }
    }

    /**
     * The end of the road for a red build: attribute the toolchain output to a stage and say,
     * in specification terms, what could not be satisfied \u2014 the book's error taxonomy.
     */
    private int reportVerifyFailure(Ast.Module module, List<Unit> units, String output, PrintStream err) {
        if (VerifyReport.compilationFailed(output)) {
            err.println("error [backend]: the staged project did not compile");
            List<String> diagnostics = VerifyReport.compileErrors(output);
            diagnostics.forEach(d -> err.println("  " + d));
            boolean nativeInvolved = diagnostics.stream().anyMatch(d -> units.stream()
                    .anyMatch(u -> u.method.nativeBody().isPresent()
                            && d.contains("/" + u.service.name() + ".java")));
            if (nativeInvolved) {
                err.println("  -> the failing file is in the staged project; the fix belongs in the");
                err.println("     java block of the .sky source.");
            }
            return VERIFICATION_FAILED;
        }
        List<VerifyReport.ClauseFailure> failures = VerifyReport.clauseFailures(output);
        if (failures.isEmpty()) {
            err.println("build failed: synthesized code did not satisfy its contracts/examples.");
            err.println(output);
            return VERIFICATION_FAILED;
        }
        for (Unit u : units) {
            List<String> clauses = failures.stream()
                    .filter(f -> (f.service() + "." + f.method()).equals(label(u)))
                    .map(VerifyReport.ClauseFailure::clause).distinct().toList();
            if (clauses.isEmpty()) {
                continue;
            }
            if (u.method.nativeBody().isPresent()) {
                err.println("error [verify]: " + module.name() + "." + label(u) + " (native)");
                err.println("  the hand-written body does not satisfy:");
                clauses.forEach(c -> err.println("    " + c));
                err.println("  -> fix the java block in the .sky source.");
                continue;
            }
            err.println("error [synthesis]: " + module.name() + "." + label(u));
            err.println("  could not satisfy all clauses after " + u.candidate
                    + (u.candidate == 1 ? " attempt." : " attempts."));
            var contradiction = contradictoryExamples(u.method);
            if (contradiction.isPresent()) {
                err.println("  unsatisfiable together:");
                err.println("    " + exampleText(contradiction.get()[0]));
                err.println("    " + exampleText(contradiction.get()[1]));
                err.println("  -> these two examples contradict each other.");
            } else {
                err.println("  violated:");
                clauses.forEach(c -> err.println("    " + c));
            }
        }
        return VERIFICATION_FAILED;
    }

    /** Two examples with the same arguments demanding different outcomes can never both pass. */
    private static java.util.Optional<Ast.Example[]> contradictoryExamples(Ast.Method m) {
        List<Ast.Example> examples = m.examples();
        for (int i = 0; i < examples.size(); i++) {
            for (int j = i + 1; j < examples.size(); j++) {
                Ast.Example a = examples.get(i);
                Ast.Example b = examples.get(j);
                if (Lowering.skyText(a.call()).equals(Lowering.skyText(b.call()))
                        && a.seed().equals(b.seed())
                        && !resultText(a.result()).equals(resultText(b.result()))) {
                    return java.util.Optional.of(new Ast.Example[]{a, b});
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static String exampleText(Ast.Example ex) {
        return "example " + Lowering.skyText(ex.call()) + " -> " + resultText(ex.result());
    }

    private static String resultText(Ast.Result result) {
        return switch (result) {
            case Ast.RaisesResult rr -> "raises " + rr.error();
            case Ast.ExprResult er -> Lowering.skyText(er.value());
            case Ast.EntityResult ent -> "a " + ent.typeName() + " with " + fieldsText(ent.fields());
            case Ast.FieldsResult fr -> fieldsText(fr.fields());
            case Ast.NothingResult ignored -> "nothing";
            case Ast.WhoseResult wr -> "a " + wr.typeName() + " whose " + wr.expects().stream()
                    .map(e -> e.field() + switch (e.kind()) {
                        case EQUALS -> " is " + Lowering.skyText(e.value().orElseThrow());
                        case NOT_EQUALS -> " is not " + Lowering.skyText(e.value().orElseThrow());
                        case IS_SET -> " is set";
                    })
                    .collect(java.util.stream.Collectors.joining(" and whose "));
            case Ast.ProseResult pr -> pr.text();
        };
    }

    private static String fieldsText(List<Ast.FieldExpect> fields) {
        return fields.stream()
                .map(f -> f.field() + " " + Lowering.skyText(f.expected()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** The spec hash a method freezes under \u2014 the key auditing tools look up in the lock. */
    public static String methodSpecHash(Ast.Module module, Ast.Method method) {
        return methodSpecHash(module, method, JvmProfile.INSTANCE, List.of());
    }

    public static String methodSpecHash(Ast.Module module, Ast.Method method, Profile profile,
                                        List<Resolved> deps) {
        return Hashing.sha256(specString(module, method, profile, deps));
    }

    /**
     * Canonical text whose hash freezes a method. Includes the profile, the resolved
     * dependencies, and every entity, so any change that could affect the staged/verified
     * code re-triggers synthesis (conservative).
     */
    static String specString(Ast.Module module, Ast.Method method) {
        return specString(module, method, JvmProfile.INSTANCE, List.of());
    }

    static String specString(Ast.Module module, Ast.Method method, Profile profile,
                             List<Resolved> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(profile.id()).append('@').append(profile.version()).append('\n');
        appendDeps(sb, deps);
        appendTypes(sb, module);
        for (Ast.Entity e : module.entities()) {
            sb.append("entity ").append(e).append('\n');
        }
        sb.append("method ").append(method).append('\n');
        return sb.toString();
    }

    /**
     * Declared dependencies bound what a body may draw on, so they are part of every spec.
     * A project without a requires block contributes nothing here, keeping older hashes exact.
     */
    private static void appendDeps(StringBuilder sb, List<Resolved> deps) {
        for (Resolved r : deps) {
            sb.append("require ").append(r.name()).append(' ').append(r.constraint())
                    .append(" -> ").append(r.version())
                    .append(' ').append(r.coordinates()).append('\n');
        }
    }

    /**
     * Declared refined types and policies are part of every spec (both shape lowering and
     * verification for all bodies). A module without them contributes nothing here, so specs
     * written before these constructs existed keep their exact hash.
     */
    private static void appendTypes(StringBuilder sb, Ast.Module module) {
        for (Ast.TypeDecl d : module.types()) {
            sb.append("type ").append(d).append('\n');
        }
        for (Ast.Policy p : module.policies()) {
            sb.append("policy ").append(p).append('\n');
        }
    }

    /**
     * Canonical text whose hash freezes a view. Includes the profile, every entity, and every service
     * signature the view may reference, so a change to the data it renders re-triggers synthesis.
     */
    static String viewSpecString(Ast.Module module, Ast.View view) {
        return viewSpecString(module, view, JvmProfile.INSTANCE, List.of());
    }

    static String viewSpecString(Ast.Module module, Ast.View view, Profile profile,
                                 List<Resolved> deps) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(profile.id()).append('@').append(profile.version()).append('\n');
        appendDeps(sb, deps);
        appendTypes(sb, module);
        for (Ast.Entity e : module.entities()) {
            sb.append("entity ").append(e).append('\n');
        }
        for (Ast.Service s : module.services()) {
            sb.append("service ").append(s).append('\n');
        }
        sb.append("view ").append(view).append('\n');
        return sb.toString();
    }
}
