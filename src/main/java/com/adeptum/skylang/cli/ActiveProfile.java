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

package com.adeptum.skylang.cli;

import com.adeptum.skylang.backend.Profile;
import com.adeptum.skylang.backend.Profiles;
import com.adeptum.skylang.config.Manifest;
import com.adeptum.skylang.deps.Budget;
import com.adeptum.skylang.deps.Registry;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.types.CheckException;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A build activates exactly one profile: an explicit {@code --profile} wins, else the
 * {@code sky.project} manifest next to the sources, else the reference profile.
 */
final class ActiveProfile {

    /** The target a build runs against: the profile and the resolved dependency budget. */
    record Activation(Profile profile, Budget deps) {
    }

    private ActiveProfile() {
    }

    static String resolve(String flag, Path sourceFile) {
        if (flag != null) {
            return flag;
        }
        return Manifest.load(sourceFile.toAbsolutePath().getParent())
                .map(Manifest::profile)
                .orElse("jvm-jakarta");
    }

    /**
     * Resolve and hold the module against its target: the portability boundary (native blocks
     * in another profile's language), the profile's feature envelope, and the manifest's
     * dependency resolution are all frontend errors, raised before any body is synthesized.
     */
    static Activation activate(String flag, Path sourceFile, Ast.Module module) {
        Profile profile = Profiles.byId(resolve(flag, sourceFile));
        profile.validate(module);
        if (!module.views().isEmpty() && !profile.supportsViews()) {
            throw new CheckException("views are not supported by profile '" + profile.id()
                    + "' — the interface library is optional per profile");
        }
        Path dir = sourceFile.toAbsolutePath().getParent();
        Registry registry = Registry.forProfile(profile.id(), Optional.ofNullable(dir));
        List<Manifest.Require> requires = Manifest.load(dir)
                .map(Manifest::requires).orElse(List.of());
        return new Activation(profile, new Budget(registry.resolve(requires), registry.prefixIndex()));
    }
}
