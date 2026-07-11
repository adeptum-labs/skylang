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
import com.adeptum.skylang.types.CheckException;

import java.nio.file.Path;
import java.util.Map;

/**
 * The reference profile: enterprise web on the JVM. Types lower onto Java, frozen bodies are
 * Java source, the backend stages a Maven project, and the interface library maps views onto
 * Faces/Facelets. Every example in the book assumes this profile.
 */
public final class JvmProfile implements Profile {

    public static final String ID = "jvm-jakarta";
    public static final String VERSION = "0.1.0";
    /** The native-block keyword this profile registers (guide §9). */
    public static final String NATIVE_KEYWORD = "java";

    public static final JvmProfile INSTANCE = new JvmProfile();

    private final ProjectStager stager = new ProjectStager();
    private final PromptBuilder prompts = new PromptBuilder();

    private JvmProfile() {
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
        return "";   // the reference profile prints untagged transcript lines
    }

    @Override
    public void validate(Ast.Module module) {
        for (Ast.Service s : module.services()) {
            for (Ast.Method m : s.methods()) {
                if (m.nativeBody().isPresent() && !NATIVE_KEYWORD.equals(m.nativeKeyword())) {
                    throw new CheckException(s.name() + "." + m.name() + ": a " + m.nativeKeyword()
                            + " block has no meaning under profile '" + ID
                            + "' — rewrite it as a " + NATIVE_KEYWORD + " block or retarget");
                }
            }
        }
    }

    @Override
    public boolean supportsViews() {
        return true;
    }

    @Override
    public void stage(Ast.Module module, Map<String, String> bodies,
                      java.util.List<com.adeptum.skylang.deps.Resolved> deps, Path dir) {
        stager.stage(module, bodies, deps, dir);
    }

    @Override
    public com.adeptum.skylang.verify.Verifier verifier() {
        return new com.adeptum.skylang.verify.MavenVerifier();
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
        return prompts.extractBody(reply);
    }
}
