/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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


#ifndef JBR_JNIDOWNCALL_BEGIN_END_H
#define JBR_JNIDOWNCALL_BEGIN_END_H

#include "alloc.h" // entry_point, throw_if_shutdown, handle_bad_alloc, awt_toolkit_shutdown

// Defined in awt_Toolkit.cpp
void jbr_awt_throw_shutdown_to_java_or_hang(void);


// Replacements for the TRY / CATCH_BAD_ALLOC[_RET] macro pairs (from alloc.h)
//   for JNI downcalls ONLY, i.e. entry points invoked from Java code, where a
//   pending Java exception is guaranteed to be delivered to the caller.
//
// Unlike TRY, which parks the calling thread forever when the Toolkit has
//   been disposed (expecting Runtime.halt() to terminate the process soon),
//   these macros raise sun.awt.windows.WToolkitShutdownException in the Java
//   caller and return errorReturnValue immediately. This keeps the VM shutdown
//   sequence (shutdown hooks -> Runtime.halt) from deadlocking on threads that
//   enter AWT after the Toolkit has been disposed (JBR-10607).
//
// Do NOT use these in entry points invoked by the OS or by other native code
//   (window procedures, COM methods, dialog hooks, EnumWindows callbacks, JAWT
//   exports): there is no Java caller there to consume the pending exception.
//   Keep TRY for those.

#define JBR_AWT_JNIDOWNCALL_BEGIN                       \
    try {                                               \
        entry_point();                                  \
        throw_if_shutdown();


#define JBR_AWT_JNIDOWNCALL_END                         \
    } catch(const std::bad_alloc&) {                    \
        handle_bad_alloc();                             \
        return;                                         \
    }                                                   \
    catch(const awt_toolkit_shutdown&) {                \
        jbr_awt_throw_shutdown_to_java_or_hang();       \
        return;                                         \
    }

#define JBR_AWT_JNIDOWNCALL_END_RET(errorReturnValue)   \
    } catch(const std::bad_alloc&) {                    \
        handle_bad_alloc();                             \
        return (errorReturnValue);                      \
    }                                                   \
    catch(const awt_toolkit_shutdown&) {                \
        jbr_awt_throw_shutdown_to_java_or_hang();       \
        return (errorReturnValue);                      \
    }

#endif //JBR_JNIDOWNCALL_BEGIN_END_H
