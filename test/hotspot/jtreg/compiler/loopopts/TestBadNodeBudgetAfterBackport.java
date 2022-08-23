/*
 * Copyright (c) 2022, Red Hat, Inc. All rights reserved.
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

/*
 * @test
 * @bug 8291713
 * @summary assert(!phase->exceeding_node_budget()) failed: sanity after JDK-8223389
 * @run main/othervm -Xbatch -XX:-TieredCompilation TestBadNodeBudgetAfterBackport
 */

public class TestBadNodeBudgetAfterBackport {

    public static long instanceCount=7400137408519306837L;
    public static int iFld=-5840;

    public static int iMeth(long l) {

        float f1=42.67F;
        double d2=2.45690;
        int i14=-47, i16=5;

        for (i14 = 8; 168 > i14; ++i14) {
            TestBadNodeBudgetAfterBackport.iFld = (int)f1;
            i16 = 1;
            while (++i16 < 10) {
                int i17=7;
                f1 *= 4350;
                try {
                    TestBadNodeBudgetAfterBackport.iFld = (TestBadNodeBudgetAfterBackport.iFld / i17);
                } catch (ArithmeticException a_e) {}
                d2 = TestBadNodeBudgetAfterBackport.iFld;
            }
        }
        return (int)Double.doubleToLongBits(d2);
    }

    public static void main(String[] strArr) {
        TestBadNodeBudgetAfterBackport _instance = new TestBadNodeBudgetAfterBackport();
        for (int i = 0; i < 10; i++ ) {
            _instance.iMeth(TestBadNodeBudgetAfterBackport.instanceCount);
        }
    }
}
