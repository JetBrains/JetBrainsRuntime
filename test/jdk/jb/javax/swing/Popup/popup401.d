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

int c;
int a[long long];

BEGIN {
    c=0;
}

objc$target:AWTWindow_Panel:-initWithDelegate*:entry {
    c++;
    a[arg0] = 1;
}

objc$target:NSObject:-dealloc:entry {
    c -= a[arg0];
    a[arg0] = 0;
}

END /c > 0/ {
    printf("AWTWindow_Panel leaks: %d", c);
    exit(9);
}

END /c == 0/ {
    printf("AWTWindow_Panel leaks: %d", c);
    exit(0);
}