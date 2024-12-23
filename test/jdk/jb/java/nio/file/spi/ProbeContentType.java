/*
 * Copyright (c) 2024,  JetBrains s.r.o.
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

/**
 * @test
 * @summary Verifies that Files.probeContentType() does not throw
 *          when used with a custom FS provider
 * @library /test/lib
 * @build TestProvider
 * @run main/othervm -Djava.nio.file.spi.DefaultFileSystemProvider=TestProvider ProbeContentType
 */

import java.nio.file.Files;
import java.nio.file.Path;

public class ProbeContentType {
    public static void main(String[] args) throws Exception {
        System.out.println(Files.probeContentType(Path.of(".")));
    }
}
