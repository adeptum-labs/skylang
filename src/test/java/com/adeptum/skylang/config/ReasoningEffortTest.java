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

package com.adeptum.skylang.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reasoning effort is a free value, not a closed set — the provider decides what it accepts — so
 * parsing only normalises and rejects the empty case.
 */
class ReasoningEffortTest {

    @Test
    void acceptsAnyValueNormalisedToLowercase() {
        assertEquals("high", ReasoningEffort.parse("  HIGH ").id());
        assertEquals("minimal", ReasoningEffort.parse("minimal").id());
        assertEquals("xhigh", ReasoningEffort.parse("xhigh").id(),
                "a provider-specific level is accepted, not rejected");
        assertEquals("2000", ReasoningEffort.parse("2000").id(),
                "a bare token budget is accepted");
    }

    @Test
    void rejectsOnlyTheBlankCase() {
        assertThrows(ConfigException.class, () -> ReasoningEffort.parse("   "));
        assertThrows(ConfigException.class, () -> ReasoningEffort.parse(null));
    }

    @Test
    void defaultsToHigh() {
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.DEFAULT);
        assertEquals("high", ReasoningEffort.DEFAULT.id());
    }
}
