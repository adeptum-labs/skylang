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
import com.adeptum.skylang.backend.ProjectStager;
import com.adeptum.skylang.freeze.Hashing;
import com.adeptum.skylang.freeze.Lock;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.synth.PromptBuilder;
import com.adeptum.skylang.synth.UiPromptBuilder;
import com.adeptum.skylang.verify.EffectLinter;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;
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

    private final Llm client;
    private final Verifier verifier;
    private final PromptBuilder prompts = new PromptBuilder();
    private final UiPromptBuilder uiPrompts = new UiPromptBuilder();
    private final ProjectStager stager = new ProjectStager();
    private final FacesViewStager facesViewStager = new FacesViewStager();
    private final ViewVerifier viewVerifier = new ViewVerifier();
    private final int maxRegenerations;

    public Pipeline(Llm client, Verifier verifier) {
        this(client, verifier, 1);
    }

    public Pipeline(Llm client, Verifier verifier, int maxRegenerations) {
        this.client = client;
        this.verifier = verifier;
        this.maxRegenerations = maxRegenerations;
    }

    /** One unit of work: a method plus everything needed to freeze it. */
    private static final class Unit {
        final Ast.Service service;
        final Ast.Method method;
        final String key;
        final String specHash;
        String body;
        boolean fresh;   // true = synthesized this build (needs freezing on success)

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
        lock.setProfile(JvmProfile.ID, JvmProfile.VERSION);

        // Resolve method bodies: reuse a frozen body when its hash matches, else synthesize.
        List<Unit> units = new ArrayList<>();
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                String key = ProjectStager.methodKey(module.name(), s.name(), m.name());
                units.add(new Unit(s, m, key, Hashing.sha256(specString(module, m))));
            }
        }
        boolean anyFresh = false;
        for (Unit u : units) {
            var frozen = lock.get(u.key);
            if (frozen.isPresent() && frozen.get().specHash().equals(u.specHash)) {
                u.body = frozen.get().body();
            } else {
                if (!resolveBody(module, u, out, err)) {
                    return 1;
                }
                u.fresh = true;
                anyFresh = true;
            }
        }

        // Resolve views: reuse frozen markup, else synthesize and dispose against its expectations.
        List<ViewUnit> viewUnits = new ArrayList<>();
        for (Ast.View v : module.views()) {
            String key = ProjectStager.viewKey(module.name(), v.name());
            viewUnits.add(new ViewUnit(v, key, Hashing.sha256(viewSpecString(module, v))));
        }
        boolean anyViewFresh = false;
        for (ViewUnit u : viewUnits) {
            var frozen = lock.getView(u.key);
            if (frozen.isPresent() && frozen.get().specHash().equals(u.specHash)) {
                u.artifact = new UiPromptBuilder.UiArtifact(frozen.get().markup(), frozen.get().bean());
            } else if (resolveView(module, u, out)) {
                u.fresh = true;
                anyViewFresh = true;
            } else {
                err.println("build failed: view " + u.view.name()
                        + " did not satisfy its expectations after " + (maxRegenerations + 1) + " attempt(s).");
                return 1;
            }
        }

        // Stage the project. If nothing changed, everything is already verified — skip the test run.
        stager.stage(module, bodyMap(units), buildDir);
        if (!module.views().isEmpty()) {
            facesViewStager.stage(module, viewArtifacts(viewUnits), baselines(viewUnits, lock), buildDir);
            clearCaptures(buildDir);   // a stale rasterization must never be adopted as a baseline
        }
        if (anyFresh || anyViewFresh || recheck) {
            int attempts = 0;
            VerificationResult result = verifier.verify(buildDir);
            while (!result.passed() && attempts < maxRegenerations) {
                attempts++;
                out.println("  verification failed — regenerating synthesized bodies (attempt " + attempts + ")");
                for (Unit u : units) {
                    if (u.fresh) {
                        u.body = synthesize(module, u);
                    }
                }
                stager.stage(module, bodyMap(units), buildDir);
                result = verifier.verify(buildDir);
            }
            if (!result.passed()) {
                err.println("build failed: synthesized code did not satisfy its contracts/examples.");
                err.println(result.output());
                return 1;
            }
            for (Unit u : units) {
                if (u.fresh) {
                    lock.put(u.key, new Lock.Entry(u.specHash, u.body));
                }
            }
        }

        // Freeze the fresh views — they were already disposed against their expectations above.
        for (ViewUnit u : viewUnits) {
            if (u.fresh) {
                lock.putView(u.key, new Lock.ViewEntry(u.specHash, u.artifact.markup(), u.artifact.bean()));
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
        String reply = client.complete(prompts.system(), prompts.user(module, u.service, u.method));
        return prompts.extractBody(reply);
    }

    /**
     * Synthesize a body and hold it to the service's effects budget, regenerating until
     * it stays inside or the attempts run out. Nothing outside the budget may reach the
     * staged project, let alone the freeze store.
     */
    private boolean resolveBody(Ast.Module module, Unit u, PrintStream out, PrintStream err) {
        u.body = synthesize(module, u);
        List<String> violations = EffectLinter.violations(u.body, u.service.uses(), module);
        int attempts = 0;
        while (!violations.isEmpty() && attempts < maxRegenerations) {
            attempts++;
            out.println("  " + u.key + " broke its effects budget " + violations
                    + " — regenerating (attempt " + attempts + ")");
            u.body = synthesize(module, u);
            violations = EffectLinter.violations(u.body, u.service.uses(), module);
        }
        if (!violations.isEmpty()) {
            err.println("build failed: " + u.key + " reaches outside its effects budget:");
            violations.forEach(v -> err.println("  " + v));
            return false;
        }
        return true;
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

    private void report(List<Unit> units, List<ViewUnit> viewUnits, PrintStream out) {
        for (Unit u : units) {
            String label = u.service.name() + "." + u.method.name();
            if (u.fresh) {
                out.printf("  %-28s regenerated   (verified)%n", label);
            } else {
                out.printf("  %-28s frozen @ %s%n", label, Hashing.shortHash(u.specHash));
            }
        }
        for (ViewUnit u : viewUnits) {
            String label = "view " + u.view.name();
            if (u.fresh) {
                out.printf("  %-28s regenerated   (verified)%n", label);
            } else {
                out.printf("  %-28s frozen @ %s%n", label, Hashing.shortHash(u.specHash));
            }
        }
    }

    /**
     * Canonical text whose hash freezes a method. Includes the profile and every entity, so any
     * change that could affect the staged/verified code re-triggers synthesis (conservative).
     */
    static String specString(Ast.Module module, Ast.Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(JvmProfile.ID).append('@').append(JvmProfile.VERSION).append('\n');
        appendTypes(sb, module);
        for (Ast.Entity e : module.entities()) {
            sb.append("entity ").append(e).append('\n');
        }
        sb.append("method ").append(method).append('\n');
        return sb.toString();
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
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(JvmProfile.ID).append('@').append(JvmProfile.VERSION).append('\n');
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
