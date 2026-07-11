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
    private final FacesViewStager facesViewStager = new FacesViewStager();
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

    /** @return 0 on success, non-zero on a verification/synthesis failure. */
    public int build(Ast.Module module, Path lockPath, Path buildDir, PrintStream out, PrintStream err) {
        return build(module, lockPath, buildDir, out, err, false);
    }

    /**
     * @param recheck run the staged verification even when every unit is frozen — an offline
     *                re-render of the whole project (tests, render checks, the visual gate) that
     *                catches environment or toolchain drift without a single model call
     * @return 0 on success, non-zero on a verification/synthesis failure.
     */
    public int build(Ast.Module module, Path lockPath, Path buildDir, PrintStream out, PrintStream err,
                     boolean recheck) {
        Lock lock = Lock.load(lockPath);

        // A body frozen as one profile's language has no meaning under another: switching the
        // profile invalidates the whole lock, and every method regenerates for the new target.
        boolean retarget = !lock.profileId().isEmpty() && !lock.profileId().equals(profile.id());
        if (retarget) {
            out.printf("  profile %s   (changed from %s; regenerating all bodies)%n%n",
                    profile.id(), lock.profileId());
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

        // Stage the project. If nothing changed, everything is already verified — skip the test run.
        profile.stage(module, bodyMap(units), deps.declared(), buildDir);
        if (!module.views().isEmpty()) {
            facesViewStager.stage(module, viewArtifacts(viewUnits), baselines(viewUnits, lock), buildDir);
            clearCaptures(buildDir);   // a stale rasterization must never be adopted as a baseline
        }
        if (anyFresh || anyViewFresh || recheck) {
            int attempts = 0;
            VerificationResult result = verifier.verify(buildDir);
            boolean regenerable = units.stream()
                    .anyMatch(u -> u.fresh && u.method.nativeBody().isEmpty());
            while (!result.passed() && regenerable && attempts < maxRegenerations) {
                attempts++;
                regenerateFailing(module, units, result.output(), attempts, out);
                profile.stage(module, bodyMap(units), deps.declared(), buildDir);
                result = verifier.verify(buildDir);
            }
            if (!result.passed()) {
                return reportVerifyFailure(module, units, result.output(), err);
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

        boolean anyVisualFrozen = adoptVisualCaptures(viewUnits, lock, buildDir, out);
        if (anyFresh || anyViewFresh || anyVisualFrozen) {
            lock.save(lockPath);
        }

        report(units, viewUnits, out);
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
        if (fresh.size() == 1) {
            return resolveBody(module, fresh.get(0), out, err);
        }
        record Attempt(java.io.ByteArrayOutputStream transcript,
                       java.util.concurrent.Future<Boolean> passed) {
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, fresh.size()));
        try {
            List<Attempt> attempts = new ArrayList<>();
            for (Unit u : fresh) {
                var transcript = new java.io.ByteArrayOutputStream();
                var stream = new PrintStream(transcript, true, java.nio.charset.StandardCharsets.UTF_8);
                attempts.add(new Attempt(transcript,
                        pool.submit(() -> resolveBody(module, u, stream, stream))));
            }
            boolean all = true;
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
                String text = attempt.transcript().toString(java.nio.charset.StandardCharsets.UTF_8);
                if (!text.isEmpty()) {
                    (passed ? out : err).print(text);
                }
                all &= passed;
            }
            return all;
        } finally {
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
        u.artifact = synthesizeView(module, u.view);
        List<String> unmet = viewVerifier.unmetExpectations(u.view, u.artifact.markup());
        int attempts = 0;
        while (!unmet.isEmpty() && attempts < maxRegenerations) {
            attempts++;
            out.println("  view " + u.view.name() + " unmet " + unmet + " — regenerating (attempt " + attempts + ")");
            u.artifact = synthesizeView(module, u.view);
            unmet = viewVerifier.unmetExpectations(u.view, u.artifact.markup());
        }
        return unmet.isEmpty();
    }

    private UiPromptBuilder.UiArtifact synthesizeView(Ast.Module module, Ast.View view) {
        String reply = client.complete(uiPrompts.system(UiPromptBuilder.STANDARD), uiPrompts.user(module, view));
        return uiPrompts.extractArtifacts(reply);
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
    private void report(List<Unit> units, List<ViewUnit> viewUnits, PrintStream out) {
        int width = 24;
        for (Unit u : units) {
            width = Math.max(width, (u.service.name() + "." + u.method.name()).length());
        }
        for (ViewUnit u : viewUnits) {
            width = Math.max(width, ("view " + u.view.name()).length());
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
            String label = "view " + u.view.name();
            if (!u.fresh) {
                out.printf("  %-" + width + "s \u25b8 frozen @ %s   (unchanged)%n",
                        label, Hashing.shortHash(u.specHash));
            } else {
                out.printf("  %-" + width + "s \u25b8 synthesized \u25b8 verified \u25b8 frozen @ %s%s%n",
                        label, Hashing.shortHash(u.specHash),
                        counted(u.view.expects().size(), "expectation"));
            }
        }
    }

    private static String counted(int n, String noun) {
        return n == 0 ? "" : "   \u2713 " + n + " " + noun + (n == 1 ? "" : "s");
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
            String pad = " ".repeat(2 + Math.max(28, label(u).length()) + 1);
            failures.stream().filter(f -> (f.service() + "." + f.method()).equals(label(u)))
                    .forEach(f -> out.printf("  %-28s \u25b8 candidate %d: %s  \u2717 FAILED%n",
                            label(u), u.candidate, f.clause()));
            out.println(pad + "\u25b8 regenerating ...");
            u.body = synthesize(module, u);
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
