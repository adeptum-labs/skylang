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

import java.nio.file.Path;
import java.util.List;

/**
 * How to serve a profile's emitted artifact: the command to run, where to run it, and the line the
 * server prints once it is listening. A plan is a value — the profile decides what running means for
 * its platform, and the runner executes it without knowing anything about Maven, wars, or npm.
 *
 * @param command     the process and its arguments
 * @param directory   the working directory the command expects
 * @param artifact    the emitted artifact being served; its absence is what {@code --skip-build}
 *                    has to report
 * @param readyMarker line prefix the server prints when serving; the bound port follows it
 * @param landingPath path to open in the browser, relative to the server root
 */
public record RunPlan(List<String> command, Path directory, Path artifact, String readyMarker,
                      String landingPath) {

    public RunPlan {
        command = List.copyOf(command);
    }
}
