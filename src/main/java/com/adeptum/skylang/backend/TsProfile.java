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
import com.adeptum.skylang.synth.PromptBuilder;
import com.adeptum.skylang.synth.TsPromptBuilder;
import com.adeptum.skylang.types.CheckException;

import java.nio.file.Path;
import java.util.Map;

/**
 * The TypeScript/Node profile: the same core, lowered onto modern TypeScript. Int is a
 * bigint, entities are immutable classes, frozen bodies are TypeScript source, and the
 * backend stages a tsconfig project verified by tsc plus node:test. This first cut carries
 * the core subset — Int/Text/Bool, entities, lists, the db and clock effects, contracts and
 * examples; everything else is an honest frontend error naming what is not yet lowered.
 */
public final class TsProfile implements Profile {

    public static final String ID = "ts-node";
    public static final String VERSION = "0.1.0";
    public static final String NATIVE_KEYWORD = "ts";

    public static final TsProfile INSTANCE = new TsProfile();

    private final TsStager stager = new TsStager();
    private final TsPromptBuilder prompts = new TsPromptBuilder();
    private final PromptBuilder fences = new PromptBuilder();   // fence extraction is shared

    private TsProfile() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String nativeKeyword() {
        return NATIVE_KEYWORD;
    }

    @Override
    public String tag() {
        return "ts";
    }

    @Override
    public boolean supportsViews() {
        return false;
    }

    @Override
    public void validate(Ast.Module module) {
        if (!module.types().isEmpty()) {
            throw notYet("declared refined types");
        }
        if (!module.policies().isEmpty()) {
            throw notYet("policies");
        }
        for (Ast.Entity e : module.entities()) {
            if (!e.values().isEmpty()) {
                throw notYet("values entities (closed instance sets)");
            }
            e.fields().forEach(f -> requireType(f.type(), "entity " + e.name() + "." + f.name()));
        }
        for (Ast.Service s : module.services()) {
            for (String effect : s.uses()) {
                if (!effect.equals("db") && !effect.equals("clock")) {
                    throw notYet("the '" + effect + "' effect");
                }
            }
            for (Ast.Method m : s.methods()) {
                String where = s.name() + "." + m.name();
                if (m.nativeBody().isPresent() && !NATIVE_KEYWORD.equals(m.nativeKeyword())) {
                    throw new CheckException(where + ": a " + m.nativeKeyword()
                            + " block has no meaning under profile '" + ID
                            + "' — rewrite it as a " + NATIVE_KEYWORD + " block or retarget");
                }
                if (!m.specs().isEmpty()) {
                    throw notYet("spec blocks (" + where + ")");
                }
                m.params().forEach(p -> requireType(p.type(), where + " parameter " + p.name()));
                requireType(m.returnType(), where + " return type");
                m.requires().forEach(e -> requireExpr(e, where + " requires"));
                m.ensures().forEach(e -> requireExpr(e, where + " ensures"));
                for (Ast.Example ex : m.examples()) {
                    if (ex.seed().isPresent()) {
                        throw notYet("example seeding, on a ... (" + where + ")");
                    }
                    ex.call().args().forEach(a -> requireExpr(a, where + " example"));
                }
            }
        }
    }

    private static final java.util.Set<String> UNLOWERED =
            java.util.Set.of("Money", "Instant", "Bytes", "Email", "Currency", "Percentage");

    private static void requireType(Ast.Type type, String where) {
        if (!(type instanceof Ast.TypeRef ref) || UNLOWERED.contains(ref.name())) {
            throw notYet("type " + type.sky() + " (" + where + ")");
        }
    }

    private static void requireExpr(Ast.Expr expr, String where) {
        switch (expr) {
            case Ast.IntLit i -> { }
            case Ast.StrLit s -> { }
            case Ast.BoolLit b -> { }
            case Ast.NameExpr n -> { }
            case Ast.MemberExpr me -> requireExpr(me.target(), where);
            case Ast.NotExpr ne -> requireExpr(ne.value(), where);
            case Ast.BinExpr be -> {
                requireExpr(be.left(), where);
                requireExpr(be.right(), where);
            }
            case Ast.CallExpr ce -> {
                if (ce.callee().endsWith("_with")) {
                    throw notYet("fixture arguments, " + ce.callee() + "(...) (" + where + ")");
                }
                ce.args().forEach(a -> requireExpr(a, where));
            }
            default -> throw notYet("the expression '" + Lowering.skyText(expr) + "' (" + where + ")");
        }
    }

    private static CheckException notYet(String what) {
        return new CheckException(what + " — not yet supported by the ts-node profile");
    }

    @Override
    public void stage(Ast.Module module, Map<String, String> bodies,
                      java.util.List<com.adeptum.skylang.deps.Resolved> deps, Path dir) {
        stager.stage(module, bodies, deps, dir);
    }

    @Override
    public com.adeptum.skylang.verify.Verifier verifier() {
        return new com.adeptum.skylang.verify.NodeVerifier();
    }

    @Override
    public boolean emit(String projectName, Path buildDir, java.io.PrintStream out) {
        String tsc = System.getenv().getOrDefault("SKY_TSC", "tsc");
        ProcessBuilder pb = new ProcessBuilder(tsc, "-p", ".")
                .directory(buildDir.toFile())
                .redirectErrorStream(true);
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                out.println("error [backend]: tsc failed");
                out.print(output);
                return false;
            }
        } catch (java.io.IOException e) {
            out.println("error [backend]: could not run '" + tsc + "' (set SKY_TSC): " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        out.println("  build/" + ID + " ▸ tsc ▸ dist/" + projectName);
        return true;
    }

    @Override
    public String systemPrompt() {
        return prompts.system();
    }

    @Override
    public String userPrompt(Ast.Module module, Ast.Service service, Ast.Method method,
                             java.util.List<com.adeptum.skylang.deps.Resolved> deps) {
        return prompts.user(module, service, method, deps);
    }

    @Override
    public String extractBody(String reply) {
        return fences.extractBody(reply);
    }
}
