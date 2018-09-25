/*
 * Copyright 2018 JetBrains s.r.o.
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

#ifndef MTLFuncs_md_h_Included
#define MTLFuncs_md_h_Included

#include <dlfcn.h>
#include "MTLFuncMacros.h"


#define MTL_LIB_HANDLE (void*)
#define MTL_DECLARE_LIB_HANDLE()

#define MTL_LIB_IS_UNINITIALIZED() \
    (MTL_LIB_HANDLE == NULL)
#define MTL_OPEN_LIB() \
    MTL_LIB_HANDLE = dlopen("/System/Library/Frameworks/OpenGL.framework/Versions/Current/Libraries/libGL.dylib", RTLD_LAZY | RTLD_GLOBAL)
#define MTL_CLOSE_LIB() \
    dlclose(MTL_LIB_HANDLE)
#define MTL_GET_PROC_ADDRESS(f) \
    dlsym(MTL_LIB_HANDLE, #f)
#define MTL_GET_EXT_PROC_ADDRESS(f) \
    _GET_PROC_ADDRESS(f)


#define MTL_EXPRESS_PLATFORM_FUNCS(action)
#define MTL_EXPRESS_PLATFORM_EXT_FUNCS(action)

#endif /* OGLFuncs_md_h_Included */
