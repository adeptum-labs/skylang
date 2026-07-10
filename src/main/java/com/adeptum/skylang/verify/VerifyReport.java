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

package com.adeptum.skylang.verify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the native toolchain's output back into specification terms. Generated tests are named
 * {@code <Service>Test.<method>_<kind>_<n>} and their assertion messages carry the violated
 * clause verbatim, so a failed run can be attributed to (service, method, clause) — which is
 * what the per-candidate transcript and the synthesis error report print.
 */
public final class VerifyReport {

    /** One violated clause: which service method failed, and the clause text it broke. */
    public record ClauseFailure(String service, String method, String clause) {
    }

    private static final Pattern FAILURE = Pattern.compile(
            "([A-Z]\\w*)Test\\.(\\w+?)_(?:example|spec|requires|raises)_\\w+(?::\\d+)?\\s+(\\S.*)");
    private static final Pattern COMPILE_ERROR = Pattern.compile(
            "\\[ERROR]\\s+(\\S+\\.java:\\[\\d+,\\d+].*)");
    private static final Pattern COUNTEREXAMPLE = Pattern.compile(" \\[\\w+=");

    private VerifyReport() {
    }

    public static List<ClauseFailure> clauseFailures(String output) {
        LinkedHashSet<ClauseFailure> failures = new LinkedHashSet<>();
        for (String line : output.lines().toList()) {
            Matcher m = FAILURE.matcher(line);
            if (m.find()) {
                failures.add(new ClauseFailure(m.group(1), m.group(2), clause(m.group(3))));
            }
        }
        return new ArrayList<>(failures);
    }

    /** The staged project did not even compile — a backend failure, not a violated contract. */
    public static boolean compilationFailed(String output) {
        return output.contains("COMPILATION ERROR");
    }

    /** The compiler diagnostics, one per offending file and line. */
    public static List<String> compileErrors(String output) {
        return output.lines()
                .map(COMPILE_ERROR::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .distinct()
                .toList();
    }

    /** The assertion message minus the counterexample suffix and the expected/actual tail. */
    private static String clause(String message) {
        int arrow = message.indexOf(" ==> ");
        String clause = arrow >= 0 ? message.substring(0, arrow) : message;
        Matcher ce = COUNTEREXAMPLE.matcher(clause);
        return (ce.find() ? clause.substring(0, ce.start()) : clause).strip();
    }
}
