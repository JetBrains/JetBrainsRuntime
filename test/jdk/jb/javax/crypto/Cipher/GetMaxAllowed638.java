/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

import javax.crypto.Cipher;

/*
 * @test
 * @summary JRE-638 It checks unlimited cryptographic policy was enabled for JBRE by default
 * @run main/othervm GetMaxAllowed638
 */

public class GetMaxAllowed638 {

    public static void main(String[] args) throws Exception {

        int maxKey = Cipher.getMaxAllowedKeyLength("RC5");
        if ( maxKey < Integer.MAX_VALUE) {
            System.out.println("Cipher.getMaxAllowedKeyLength(\"RC5\"): " + maxKey);
            throw new RuntimeException("unlimited policy is set by default, "
                    + Integer.MAX_VALUE + "(Integer.MAX_VALUE) is expected");
        }
    }
}
