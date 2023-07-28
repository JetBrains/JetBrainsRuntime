// Copyright 2023 JetBrains s.r.o.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 2 only, as
// published by the Free Software Foundation.  Oracle designates this
// particular file as subject to the "Classpath" exception as provided
// by Oracle in the LICENSE file that accompanied this code.
//
// This code is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// version 2 for more details (a copy is included in the LICENSE file that
// accompanied this code).
//
// You should have received a copy of the GNU General Public License version
// 2 along with this work; if not, write to the Free Software Foundation,
// Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// or visit www.oracle.com if you need additional information or have any
// questions.


#ifndef ACCESSBRIDGEUTILS_H
#define ACCESSBRIDGEUTILS_H

#include <cstddef>  // std::size_t
#include <jni.h>    // JNIEnv, jstring

class AccessBridgeUtils final { // it should be a namespace but the hotspot code style recommends to avoid namespaces
public: // java strings copying
    /**
     * Copies up to @p bufferCapacityInWChars characters of @p javaString to @p buffer.
     * In case of any error occurred (including a java exception), the buffer is filled with zeroes.
     * After any invocation of the method you always have to check if a java exception occurred
     * (@code env->ExceptionCheck() @endcode or @code env->ExceptionOccurred() @endcode).
     *
     * @param [in] replaceLastCharWith0IfNoSpace if length of the java string >= @p bufferCapacityInWChars,
     *                                           this parameter determines whether the function should replace
     *                                           the last copied character with 0, so that the string in @p buffer
     *                                           always gets null-terminated.
     *
     * @return the number of the characters copied from the java string, *excluding* appended 0 (if any) ;
     *         effectively @code min(len(javaString), bufferCapacityInWChars) @endcode
     *         (if no errors occurred during copying)
     */
    static std::size_t CopyJavaStringToWCharBuffer(
        JNIEnv& env,
        jstring javaString,
        wchar_t* buffer,
        std::size_t bufferCapacityInWChars,
        bool replaceLastCharWith0IfNoSpace = true
    );

    /**
     * @see CopyJavaStringToWCharBuffer(JNIEnv&, jstring, wchar_t*, std::size_t, bool)
     */
    template<std::size_t BufferCapacityInWChars>
    static std::size_t CopyJavaStringToWCharBuffer(
        JNIEnv& env,
        jstring javaString,
        wchar_t (&buffer)[BufferCapacityInWChars],
        bool replaceLastCharWith0IfNoSpace = true
    ) {
        return CopyJavaStringToWCharBuffer(
            env,
            javaString,
            &buffer[0],
            BufferCapacityInWChars,
            replaceLastCharWith0IfNoSpace
        );
    }

private:
    AccessBridgeUtils() = delete;
    ~AccessBridgeUtils() = delete;
};

#endif // ndef ACCESSBRIDGEUTILS_H
