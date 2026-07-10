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
import com.adeptum.skylang.verify.Verifier;

import java.nio.file.Path;
import java.util.Map;

/**
 * The adapter between the platform-independent core and one concrete target. A profile
 * supplies the representations — lowerings, native keyword, staged project layout, synthesis
 * prompts — while the core keeps the semantics: what every type and contract means, and that
 * nothing unverified reaches the artifact.
 */
public interface Profile {

    String id();

    String version();

    /** The one native-block keyword this profile registers ({@code java}, {@code ts}, ...). */
    String nativeKeyword();

    /** Transcript tag for non-reference targets ({@code "ts"} prints as {@code synthesized (ts)}). */
    String tag();

    /**
     * The portability boundary and the profile's feature envelope, checked before any body
     * exists: a native block in another profile's language, or a construct this profile does
     * not yet lower, is a hard frontend error naming the offending method.
     */
    void validate(Ast.Module module);

    /** Whether the profile carries the optional interface library ({@code view} et al). */
    boolean supportsViews();

    /** Materialise the complete staged project — sources, spliced bodies, contracts as tests. */
    void stage(Ast.Module module, Map<String, String> bodies, Path dir);

    /** The platform's own toolchain, run as the verification harness over the staged project. */
    Verifier verifier();

    String systemPrompt();

    String userPrompt(Ast.Module module, Ast.Service service, Ast.Method method);

    /** The proposed body, extracted from the model's fenced reply. */
    String extractBody(String reply);
}
