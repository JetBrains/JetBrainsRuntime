/*
 * Copyright 2023 JetBrains s.r.o.
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

#ifndef JDK_JAVAACCESSBRIDGEJNIREFSTRACKER_H
#define JDK_JAVAACCESSBRIDGEJNIREFSTRACKER_H

#include <jni.h>                    // jobject, JNIEnv
#include "AccessBridgePackages.h"   // JOBJECT64
#include <cstddef>                  // std::nullptr_t


class JavaAccessBridgeJniRefsTracker {
public: // nested types
    class LocalRef {
    public: // ctors/dtor
        LocalRef(JavaAccessBridgeJniRefsTracker& tracker, jobject&& localRef);
        LocalRef(JavaAccessBridgeJniRefsTracker& tracker, jobject& localRef);

        ~LocalRef();

    public: // making non-copyable and non-movable
        LocalRef(const LocalRef&) = delete;
        LocalRef(LocalRef&&) = delete;

        LocalRef& operator=(const LocalRef&) = delete;
        LocalRef& operator=(LocalRef&&) = delete;

    public: // comparison
        /**
         * Allows to check if the reference doesn't point to any object:
         * @code
         * if (localRef == nullptr) {
         *     // ...
         * }
         * @endcode
         *
         * Effectively equals to the expression localRef.getRaw() == nullptr.
         */
        [[nodiscard]] bool operator==(std::nullptr_t) const noexcept;

        [[nodiscard]] inline bool operator!=(std::nullptr_t) const noexcept { return !(*this == nullptr); }

    public:
        /**
         * Returns the raw JNI reference object. Doesn't release the ownership.
         * void* is returned instead of jobject to prevent unintentional "sharing" the ownership of the JNI ref.
         * This is supposed to be used only for read-only code, e.g. for logging.
         */
        [[nodiscard]] const void* getRaw() const noexcept;
    };

    class GlobalRef {
    public: // ctors/dtor
        GlobalRef();
        GlobalRef(JavaAccessBridgeJniRefsTracker& tracker, jobject&& globalRef);
        GlobalRef(JavaAccessBridgeJniRefsTracker& tracker, jobject& globalRef);

        ~GlobalRef();

    public: // making movable but non-copyable
        GlobalRef(const GlobalRef&) = delete;
        GlobalRef& operator=(const GlobalRef&) = delete;

        GlobalRef(GlobalRef&& other) noexcept;
        GlobalRef& operator=(GlobalRef&& rhs);

    public: // comparison to nullptr
        /**
         * Allows to check if the reference doesn't point to any object:
         * @code
         * if (globalRef == nullptr) {
         *     // ...
         * }
         * @endcode
         *
         * Effectively equals to the expression globalRef.getRaw() == nullptr.
         */
        [[nodiscard]] bool operator==(std::nullptr_t) const noexcept;

        [[nodiscard]] inline bool operator!=(std::nullptr_t) const noexcept { return !(*this == nullptr); }

    public:
        /**
         * Returns the raw JNI reference object. Doesn't release the ownership.
         * void* is returned instead of jobject to prevent unintentional "sharing" the ownership of the JNI ref.
         * This is supposed to be used only for read-only code, e.g. for logging.
         */
        [[nodiscard]] const void* getRaw() const noexcept;

    public: // transferring the ownership
        jobject release() noexcept;
        [[nodiscard]] JOBJECT64 releaseAsJOBJECT64() noexcept;

    private:
        GlobalRef(JavaAccessBridgeJniRefsTracker* tracker, jobject globalRef);
    };

public: // ctors/dtor
    JavaAccessBridgeJniRefsTracker(JNIEnv& thisThreadJniEnv);
    ~JavaAccessBridgeJniRefsTracker();

/*public:
    GlobalRef CreateAndRegisterNewGlobalRefFrom(const LocalRef& jniLocalRef);
    bool DeregisterAndDeleteGlobalRef(GlobalRef& registeredGlobalRef);

public:
    GlobalRef FindRegisteredGlobalRef(JOBJECT64 globalRefHandle);*/

public:
    /* ??? */ void CreateAndRegisterGlobalRef(jobject objRef);
    bool DeregisterAndDeleteGlobalRef(/* ??? */ jobject globalRef);
    /* ??? */ void FindRegisteredGlobalRef(JOBJECT64 globalRefHandle) const;
};


// 83778374gnklndsub829fun329&G*SGFpi3n4f8yBOYF67voyubYUV&&OVBL;l;asndiui
// gnklndsub829fun329&G*SGFpi3n4f8yBOY


#endif //JDK_JAVAACCESSBRIDGEJNIREFSTRACKER_H
