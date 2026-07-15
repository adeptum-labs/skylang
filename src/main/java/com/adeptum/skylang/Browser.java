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

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/** Hands a URL to the desktop's browser; where there is none, the URL is the fallback. */
public final class Browser {

    private Browser() {
    }

    public static void open(String url, PrintStream out) {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> command = os.contains("mac") ? List.of("open", url)
                : os.contains("win") ? List.of("cmd", "/c", "start", "", url)
                : List.of("xdg-open", url);
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            out.println("(open " + url + " in your browser)");
        }
    }
}
