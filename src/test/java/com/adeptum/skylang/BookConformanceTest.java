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

import com.adeptum.skylang.front.Parsing;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guide's complete shop listing, lifted straight out of the manuscript and pushed
 * through the front end. The listing is the specification the guide publishes checkpoint
 * output for, so anything it contains that the compiler cannot read is a divergence
 * between what the guide promises and what the language does.
 *
 * <p>The unreadable clauses are enumerated in {@link #KNOWN_GAPS}. The assertion is a
 * ratchet in both directions: a clause that stops parsing fails the build, and a gap that
 * starts parsing fails it too, so closing one is not complete until its entry is removed.
 *
 * <p>The manuscript lives beside this repository. When it is absent the test is skipped;
 * point {@code -Dsky.guide=/path} at another checkout to run it elsewhere.
 */
class BookConformanceTest {

    private static final Path GUIDE = Path.of(
            System.getProperty("sky.guide", "../a-book-about-skylang"));

    private static final Path LISTING = GUIDE.resolve("appendix/g-full-listing.tex");

    private static final Pattern BLOCK = Pattern.compile(
            "\\\\begin\\{skycode}\\R(.*?)\\\\end\\{skycode}", Pattern.DOTALL);

    /**
     * Clauses the guide writes that the front end cannot yet read. Each is the article-form
     * example vocabulary: a witness named by its shape rather than constructed by literal
     * arguments, a starting state described in the same words, or a result that refers back
     * to what the line already named.
     */
    private static final Set<String> KNOWN_GAPS = Set.of(
            "example find(1) on a catalog containing product 1 -> that product",
            "example find(999) on a catalog without it          -> nothing",
            "example totalStockValue() on an empty catalog -> 0eur",
            "example lowStock(10) on a catalog with products at stock 3, 10, 25",
            "example place(a Draft order with 2 items)",
            "example place(an order that is already Placed)",
            "example place(a Draft order with no items)",
            "example  cancel(id of a Placed order)  -> an order whose status is Cancelled",
            "example  cancel(id of a Shipped order) -> raises AlreadyShipped",
            "example  cancel(id of a Draft order)   -> raises NotCancellable",
            "example find(999) on a shop without it -> nothing",
            "example place(a cart with Notebook x2, a customer) -> a Placed order for that customer",
            "given a cart with Notebook x2, Notebook with stock 7,",
            "example averageRating(a product with ratings 4 and 2) -> 3",
            "example averageRating(a product with no reviews)      -> nothing",
            "example latest(5) on a shop with 3 reviews -> 3 reviews",
            "ensures every product in result has stock <= threshold",
            "ensures every order in result has placedAt present and placedAt is today",
            "ensures result is ordered by postedAt descending");

    @Test
    void theGuidesListingReadsAsWrittenApartFromTheKnownGaps() throws IOException {
        assumeTrue(Files.isReadable(LISTING), "guide not checked out beside this repository");

        List<String> lines = new ArrayList<>(List.of(listing().split("\\R")));
        Set<String> matched = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (KNOWN_GAPS.contains(lines.get(i).strip())) {
                matched.add(lines.get(i).strip());
                blankClauseAt(lines, i + 1);
            }
        }

        assertTrue(matched.containsAll(KNOWN_GAPS),
                () -> "these gaps no longer appear in the guide — remove them from KNOWN_GAPS:\n  "
                        + String.join("\n  ", minus(KNOWN_GAPS, matched)));

        String error = parseError(String.join("\n", lines));
        assertTrue(error == null,
                () -> "the guide writes a clause the front end cannot read, and it is not"
                        + " recorded as a known gap:\n  " + error);
    }

    @Test
    void everyKnownGapStillFailsToRead() throws IOException {
        assumeTrue(Files.isReadable(LISTING), "guide not checked out beside this repository");

        assertTrue(parseError(listing()) != null,
                "the whole listing reads cleanly — the known gaps are closed, so drop"
                        + " KNOWN_GAPS and assert the listing parses outright");
    }

    private static String listing() throws IOException {
        Matcher m = BLOCK.matcher(Files.readString(LISTING));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1));
        }
        return sb.toString();
    }

    /** The parse error for this source, or null when it reads cleanly. */
    private static String parseError(String source) {
        try {
            Parsing.parse(source, "shop.sky");
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * The keywords that open a clause — where one ends and the next begins. A spec's
     * given/when/then are absent deliberately: they are interior to the block, and a spec
     * missing one no longer parses, so the block is blanked whole.
     */
    private static final Set<String> CLAUSE_WORDS = Set.of(
            "intent", "requires", "ensures", "example", "raises", "spec",
            "shows", "action", "expect", "appears", "step", "policy", "type", "entity", "service",
            "page", "view", "component", "flow", "annotation");

    private static int clauseStart(List<String> lines, int line) {
        int i = Math.min(line, lines.size()) - 1;
        while (i > 0 && !CLAUSE_WORDS.contains(firstWord(lines.get(i)))) {
            i--;
        }
        return i;
    }

    private static int clauseEnd(List<String> lines, int start) {
        if (firstWord(lines.get(start)).equals("spec")) {
            return blockEnd(lines, start);
        }
        int i = start;
        while (i + 1 < lines.size()) {
            String next = lines.get(i + 1).strip();
            if (next.isEmpty() || next.startsWith("}") || CLAUSE_WORDS.contains(firstWord(lines.get(i + 1)))) {
                break;
            }
            i++;
        }
        return i;
    }

    /** The line closing the brace opened on {@code start}. */
    private static int blockEnd(List<String> lines, int start) {
        int depth = 0;
        for (int i = start; i < lines.size(); i++) {
            for (char c : lines.get(i).toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (depth <= 0 && i > start) {
                return i;
            }
        }
        return start;
    }

    private static void blankClauseAt(List<String> lines, int line) {
        int start = clauseStart(lines, line);
        int end = clauseEnd(lines, start);   // fixed first: blanking changes what the end reads as
        for (int i = start; i <= end; i++) {
            lines.set(i, "");
        }
    }

    private static String firstWord(String line) {
        String s = line.strip();
        int space = s.indexOf(' ');
        return space < 0 ? s : s.substring(0, space);
    }

    private static List<String> minus(Set<String> from, Set<String> remove) {
        return from.stream().filter(s -> !remove.contains(s)).sorted().toList();
    }
}
