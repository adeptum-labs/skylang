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

import java.io.PrintStream;

/**
 * A one-line activity indicator for a long, otherwise silent step — a dot that bounces back and
 * forth in a small track so a running build never looks frozen. On an interactive terminal it
 * animates in place and leaves a plain summary line when it stops; when the output is piped or
 * captured it only prints that summary line, so carriage-return frames never leak into a log or a
 * test transcript. The label may be updated while running (via {@link #label}) to reflect
 * progress, such as a running count.
 */
public final class Ticker implements AutoCloseable {

    private static final int WIDTH = 3;
    private static final long FRAME_MILLIS = 120;

    private final PrintStream out;
    private final boolean animated;
    private volatile String message;
    private volatile boolean running = true;
    private boolean closed;
    private Thread thread;

    private Ticker(PrintStream out, String message) {
        this.out = out;
        this.message = message;
        this.animated = System.console() != null;
    }

    /** Begin indicating activity for {@code message}; close the returned ticker when the step ends. */
    public static Ticker start(PrintStream out, String message) {
        Ticker ticker = new Ticker(out, message);
        if (ticker.animated) {
            ticker.thread = new Thread(ticker::animate, "sky-ticker");
            ticker.thread.setDaemon(true);
            ticker.thread.start();
        }
        return ticker;
    }

    /** Update the running label — the animated frame and the summary line pick it up. */
    public void label(String message) {
        this.message = message;
    }

    private void animate() {
        int pos = 0;
        int dir = 1;
        try {
            while (running) {
                StringBuilder track = new StringBuilder(WIDTH);
                for (int i = 0; i < WIDTH; i++) {
                    track.append(i == pos ? '•' : ' ');
                }
                out.print("\r  " + message + " [" + track + "]");
                out.flush();
                Thread.sleep(FRAME_MILLIS);
                pos += dir;
                if (pos <= 0 || pos >= WIDTH - 1) {
                    dir = -dir;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        if (animated) {
            try {
                thread.join(FRAME_MILLIS * 4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int span = ("  " + message + " [" + " ".repeat(WIDTH) + "]").length();
            out.print("\r" + " ".repeat(span) + "\r");
        }
        out.println("  " + message + " ...");
    }
}
