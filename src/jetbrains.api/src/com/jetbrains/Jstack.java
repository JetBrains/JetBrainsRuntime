/*
 * Copyright 2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.jetbrains;

import java.util.function.Supplier;

public interface Jstack {
    /**
     * Specifies a supplier of additional information to be included into
     * the output of {@code jstack}. The String supplied will be included
     * as-is with no header surrounded only with line breaks.
     *
     * {@code infoSupplier} will be invoked on an unspecified thread that
     * must not be left blocked for a long time.
     *
     * Only one supplier is allowed, so subsequent calls to
     * {@code includeInfoFrom} will overwrite the previously specified supplier.
     *
     * @param infoSupplier a supplier of {@code String} values to be
     *                     included into jstack's output. If {@code null},
     *                     then the previously registered supplier is removed
     *                     (if any) and no extra info will be included.
     */
    void includeInfoFrom(Supplier<String> infoSupplier);
}
