/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

package compiler.vectorapi;

import jdk.incubator.vector.*;
import jdk.test.lib.format.ArrayDiff;

/*
 * @test
 * @bug 8286283
 * @summary Test correctness of macrologic optimization
 * @modules jdk.incubator.vector
 * @requires vm.cpu.features ~= ".*avx512f.*"
 * @library /test/lib
 *
 * @run main compiler.vectorapi.Test8286283
 */
public class Test8286283 {
   public static final int LEN = 1024;
   public static VectorSpecies<Integer> SPECIES = IntVector.SPECIES_512;

    public static void test_vec(int [] r, int [] a, int [] b, int [] c) {
        VectorMask<Integer> mask = VectorMask.fromLong(SPECIES, -1);
        for(int i = 0 ; i < r.length; i+=SPECIES.length()) {
            IntVector avec = IntVector.fromArray(SPECIES, a, i);
            IntVector bvec = IntVector.fromArray(SPECIES, b, i);
            IntVector cvec = IntVector.fromArray(SPECIES, c, i);
            avec.lanewise(VectorOperators.AND, bvec)
                .lanewise(VectorOperators.OR, cvec)
                .lanewise(VectorOperators.NOT)
                .lanewise(VectorOperators.NOT, mask)
                .reinterpretAsInts()
                .intoArray(r, i);
        }
    }

    public static void test_scalar(int [] r, int [] a, int [] b, int [] c) {
        for(int i = 0; i < r.length; i++) {
            r[i] = ~(~(a[i] & b[i] | c[i]));
        }
    }

    public static void main(String [] args) {
        int res = 0;
        int [] a = new int[LEN];
        int [] b = new int[LEN];
        int [] c = new int[LEN];
        int [] rv = new int[LEN];
        int [] rs = new int[LEN];

        for(int i = 0 ; i < LEN; i++) {
            a[i] = i;
            b[i] = i+1;
            c[i] = i+2;
        }

        for (int i = 0 ; i < 10000; i++) {
            test_vec(rv, a, b ,c);
            test_scalar(rs, a, b ,c);
        }

        var diff = ArrayDiff.of(rv, rs);
        if (!diff.areEqual()) {
            throw new AssertionError("scalar vs vector result mismatch: " + diff.format());
        }
    }
}
