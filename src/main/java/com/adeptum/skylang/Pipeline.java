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

import com.adeptum.skylang.backend.JvmProfile;
import com.adeptum.skylang.backend.ProjectStager;
import com.adeptum.skylang.freeze.Hashing;
import com.adeptum.skylang.freeze.Lock;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.Llm;
import com.adeptum.skylang.synth.PromptBuilder;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the build pipeline for one already-checked module (guide §12): resolve each method's
 * body from the freeze store or the model, stage the target project, verify it, then freeze the
 * newly-synthesized bodies. The model is called only for methods whose spec hash changed.
 */
public final class Pipeline {

    private final Llm client;
    private final Verifier verifier;
    private final PromptBuilder prompts = new PromptBuilder();
    private final ProjectStager stager = new ProjectStager();
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

    /** @return 0 on success, non-zero on a verification/synthesis failure. */
    public int build(Ast.Module module, Path lockPath, Path buildDir, PrintStream out, PrintStream err) {
        Lock lock = Lock.load(lockPath);
        lock.setProfile(JvmProfile.ID, JvmProfile.VERSION);

        List<Unit> units = new ArrayList<>();
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                String key = ProjectStager.methodKey(module.name(), s.name(), m.name());
                units.add(new Unit(s, m, key, Hashing.sha256(specString(module, m))));
            }
        }

        // Resolve bodies: reuse a frozen body when its hash matches, else synthesize.
        boolean anyFresh = false;
        for (Unit u : units) {
            var frozen = lock.get(u.key);
            if (frozen.isPresent() && frozen.get().specHash().equals(u.specHash)) {
                u.body = frozen.get().body();
            } else {
                u.body = synthesize(module, u);
                u.fresh = true;
                anyFresh = true;
            }
        }

        // Stage the project. If nothing changed, everything is already verified — skip the test run.
        stager.stage(module, bodyMap(units), buildDir);
        if (anyFresh) {
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
            // Verified: freeze the fresh bodies.
            for (Unit u : units) {
                if (u.fresh) {
                    lock.put(u.key, new Lock.Entry(u.specHash, u.body));
                }
            }
            lock.save(lockPath);
        }

        report(units, out);
        out.println("staged: " + buildDir);
        return 0;
    }

    private String synthesize(Ast.Module module, Unit u) {
        String reply = client.complete(prompts.system(), prompts.user(module, u.service, u.method));
        return prompts.extractBody(reply);
    }

    private static Map<String, String> bodyMap(List<Unit> units) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Unit u : units) {
            map.put(u.key, u.body);
        }
        return map;
    }

    private void report(List<Unit> units, PrintStream out) {
        for (Unit u : units) {
            String label = u.service.name() + "." + u.method.name();
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
    private static String specString(Ast.Module module, Ast.Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append("profile=").append(JvmProfile.ID).append('@').append(JvmProfile.VERSION).append('\n');
        for (Ast.Entity e : module.entities()) {
            sb.append("entity ").append(e).append('\n');
        }
        sb.append("method ").append(method).append('\n');
        return sb.toString();
    }
}
