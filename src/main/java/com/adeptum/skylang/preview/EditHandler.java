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

package com.adeptum.skylang.preview;

/** Handles the studio's edit actions: reshape a view in natural language, then accept or reject. */
public interface EditHandler {

    EditResult edit(String view, String instruction);

    EditResult accept();

    EditResult reject();

    /** A handler that rejects everything — used when the studio is served without an editing session. */
    EditHandler NONE = new EditHandler() {
        @Override
        public EditResult edit(String view, String instruction) {
            return EditResult.error("editing is not available");
        }

        @Override
        public EditResult accept() {
            return EditResult.error("editing is not available");
        }

        @Override
        public EditResult reject() {
            return EditResult.error("editing is not available");
        }
    };
}
