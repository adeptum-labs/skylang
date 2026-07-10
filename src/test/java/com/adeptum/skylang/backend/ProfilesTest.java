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

import com.adeptum.skylang.config.ConfigException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A build activates exactly one profile; asking for the wrong one must say why, precisely. */
class ProfilesTest {

    @Test
    void theReferenceProfileResolves() {
        Profile p = Profiles.byId("jvm-jakarta");
        assertEquals("jvm-jakarta", p.id());
        assertEquals("java", p.nativeKeyword());
        assertTrue(p.supportsViews());
    }

    @Test
    void aDesignedButUnshippedProfileSaysSo() {
        ConfigException e = assertThrows(ConfigException.class, () -> Profiles.byId("python"));
        assertTrue(e.getMessage().contains("not shipped"), e.getMessage());
    }

    @Test
    void anUnknownProfileListsTheAvailableOnes() {
        ConfigException e = assertThrows(ConfigException.class, () -> Profiles.byId("go-lang"));
        assertTrue(e.getMessage().contains("unknown profile 'go-lang'"), e.getMessage());
        assertTrue(e.getMessage().contains("jvm-jakarta"), e.getMessage());
    }
}
