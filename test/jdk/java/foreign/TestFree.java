/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.
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
 * @enablePreview
 * @bug 8248421
 * @summary SystemCLinker should have a way to free memory allocated outside Java
 * @run testng/othervm --enable-native-access=ALL-UNNAMED TestFree
 */

import java.lang.foreign.MemorySegment;

import static org.testng.Assert.assertEquals;

public class TestFree extends NativeTestHelper {
    public void test() throws Throwable {
        String str = "hello world";
        MemorySegment addr = allocateMemory(str.length() + 1);
        addr.copyFrom(MemorySegment.ofArray(str.getBytes()));
        addr.set(C_CHAR, str.length(), (byte)0);
        assertEquals(str, addr.getUtf8String(0));
        freeMemory(addr);
    }
}
