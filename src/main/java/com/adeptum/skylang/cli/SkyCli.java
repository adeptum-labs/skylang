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

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The {@code sky} command-line entry point. The help header recreates the book cover's
 * look: the tracked eyebrow, the two-tone wordmark (near-white Sky, petrol Lang), the
 * tagline and the motto. Picocli strips the colors when output is not a terminal.
 */
@Command(
        name = "sky",
        mixinStandardHelpOptions = true,
        version = "sky 0.1.0",
        header = {
                "",
                "@|fg(109) S P E C I F I C A T I O N   L A N G U A G E|@ @|fg(66) ·|@ @|fg(109) J V M - F I R S T|@",
                "",
                "@|fg(255) ┏━┓╻┏ ╻ ╻|@@|fg(109) ╻  ┏━┓┏┓╻┏━╸|@",
                "@|fg(255) ┗━┓┣┻┓┗┳┛|@@|fg(109) ┃  ┣━┫┃┗┫┃╺┓|@",
                "@|fg(255) ┗━┛╹ ╹ ╹ |@@|fg(109) ┗━╸╹ ╹╹ ╹┗━┛|@",
                "",
                "@|fg(255) You write the contract. The compiler writes the code.|@",
                "@|fg(66) ────────────────────────|@",
                "@|fg(66) THE MODEL PROPOSES. THE COMPILER DISPOSES.|@",
                ""},
        description = "SkyLang compiler: declare the contract, the model writes the body.",
        subcommands = {OnboardCommand.class, CheckCommand.class, BuildCommand.class, PreviewCommand.class,
                TddCommand.class, FreezeCommand.class, WhyCommand.class, TestCommand.class,
                CleanCommand.class})
public final class SkyCli implements Runnable {

    @Override
    public void run() {
        // No subcommand given: print usage.
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SkyCli()).execute(args);
        System.exit(exitCode);
    }
}
