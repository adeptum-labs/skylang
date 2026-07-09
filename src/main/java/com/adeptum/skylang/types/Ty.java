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

package com.adeptum.skylang.types;

/**
 * A resolved SkyLang type in the thin slice: the primitives {@code Int}/{@code Text},
 * the contract-only {@code Bool}, an entity type carrying its name, or a {@code list} of one
 * of those (e.g. {@code [Product]}). Identity is by ({@code name}, {@code list}).
 */
public record Ty(String name, boolean list) {

    public static final Ty INT = new Ty("Int", false);
    public static final Ty TEXT = new Ty("Text", false);
    public static final Ty BOOL = new Ty("Bool", false);

    public Ty(String name) {
        this(name, false);
    }

    public static Ty entity(String name) {
        return new Ty(name, false);
    }

    /** A list whose element type has the given name, e.g. {@code list("Product")} is {@code [Product]}. */
    public static Ty list(String element) {
        return new Ty(element, true);
    }

    public boolean isInt() {
        return this.equals(INT);
    }

    public boolean isText() {
        return this.equals(TEXT);
    }

    public boolean isBool() {
        return this.equals(BOOL);
    }

    @Override
    public String toString() {
        return list ? "[" + name + "]" : name;
    }
}
