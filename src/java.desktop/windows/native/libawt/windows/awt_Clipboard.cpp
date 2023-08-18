/*
 * Copyright (c) 1996, 2020, Oracle and/or its affiliates. All rights reserved.
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

#include "awt_Clipboard.h"
#include "awt_DataTransferer.h"
#include "awt_Toolkit.h"
#include <shlobj.h>
#include <sun_awt_windows_WClipboard.h>
#include <wchar.h> // swprintf


// ================= IDEA-316996 AWT clipboard extra logging facilities =================

volatile jclass AwtClipboard::WClipboardCID = nullptr;
volatile jmethodID AwtClipboard::logSevereMID = nullptr;
volatile jmethodID AwtClipboard::logWarningMID = nullptr;
volatile jmethodID AwtClipboard::logInfoMID = nullptr;

void AwtClipboard::initializeLogging(JNIEnv * const env, const jclass WClipboardCls)
{
    if ((env == nullptr) || (WClipboardCls == nullptr)) {
        return;
    }

    if (WClipboardCID != nullptr) {
        return;
    }
    WClipboardCID = static_cast<jclass>(env->NewGlobalRef(WClipboardCls));
    if ((WClipboardCID == nullptr) || (env->ExceptionCheck() == JNI_TRUE)) {
        return;
    }

    logSevereMID = env->GetStaticMethodID(WClipboardCID, "logSevereImpl", "(Ljava/lang/String;)V");
    if ( (logSevereMID == nullptr) || (env->ExceptionCheck() == JNI_TRUE) ) {
        return;
    }

    logWarningMID = env->GetStaticMethodID(WClipboardCID, "logWarningImpl", "(Ljava/lang/String;)V");
    if ( (logWarningMID == nullptr) || (env->ExceptionCheck() == JNI_TRUE) ) {
        return;
    }

    logInfoMID = env->GetStaticMethodID(WClipboardCID, "logInfoImpl", "(Ljava/lang/String;)V");
}


template<size_t WCharsCapacity>
class AwtClipboard::FixedString
{
public:
    using RawType = wchar_t[WCharsCapacity];

public: // ctors/dtor
    FixedString() noexcept = default;

    FixedString(const FixedString& other) noexcept = default;
    FixedString(FixedString&& other) noexcept = default;

    FixedString(const wchar_t* cStr) noexcept;

    ~FixedString() noexcept = default;

public: // assignments
    FixedString& operator=(const FixedString& other) noexcept = default;
    FixedString& operator=(FixedString&& other) noexcept = default;

public: // getters
    static constexpr size_t GetCapacity() noexcept { return WCharsCapacity; }

    // Number of the characters before the null-terminator
    size_t GetLength() const noexcept { return contentLength_ + suffixLength_; }

    const RawType& GetRaw() const noexcept { return data_; }
    const wchar_t* GetCStr() const noexcept { return data_; }

public: // appenders
    FixedString& append(const bool val) noexcept
    {
        return val ? append("true") : append("false");
    }


    FixedString& append(const char ch) noexcept
    {
        return appendFormat(L"%c", static_cast<int>(ch));
    }

    // the native wchar_t type is disabled in the project (maps into short)
    //FixedString& append(wchar_t ch) noexcept;


    FixedString& append(const unsigned char val) noexcept
    {
        // treating unsigned char as a byte
        return appendFormat(L"0x%hX", static_cast<unsigned short>(val));
    }

    FixedString& append(const signed char val) noexcept
    {
        // treating signed char as a usual number
        return append(static_cast<long long>(val));
    }


    FixedString& append(const unsigned short val) noexcept { return append(static_cast<unsigned long long>(val)); }
    FixedString& append(const short val) noexcept { return append(static_cast<long long>(val)); }

    FixedString& append(const unsigned int val) noexcept { return append(static_cast<unsigned long long>(val)); }
    FixedString& append(const int val) noexcept { return append(static_cast<long long>(val)); }

    FixedString& append(const unsigned long int val) noexcept { return append(static_cast<unsigned long long>(val)); }
    FixedString& append(const long int val) noexcept { return append(static_cast<long long>(val)); }


    FixedString& append(const unsigned long long int val) noexcept
    {
        return appendFormat(L"%llu", val);
    }

    FixedString& append(const long long int val) noexcept
    {
        return appendFormat(L"%lld", val);
    }


    FixedString& append(const float val) noexcept
    {
        return append(static_cast<double>(val));
    }

    FixedString& append(const double val) noexcept
    {
        return appendFormat(L"%f", val);
    }

    FixedString& append(const long double val) noexcept
    {
        return appendFormat(L"%Lf", val);
    }


    template<size_t StringLength>
    FixedString& append(const char (&str)[StringLength]) noexcept
    {
        // The format should be L"%.*s" (without the h), but MSVC works in a non-standard way here and requires %hs
        return appendFormat(L"%.*hs", int{ StringLength }, &str[0]);
    }

    template<size_t StringLength>
    FixedString& append(const wchar_t (&str)[StringLength]) noexcept
    {
        return appendFormat(L"%.*ls", int{ StringLength }, &str[0]);
    }


    FixedString& append(const void* ptr) noexcept
    {
        return appendFormat(L"0x%p", ptr);
    }


    template<typename T>
    FixedString& append(const FormatArray<T>& arr) noexcept
    {
        append('[');

        if (arr.arrLength > 0) {
            append(arr.arr[0]);
        }
        for (size_t i = 1; i < arr.arrLength; ++i) {
            append(", ").append(arr.arr[i]);
        }

        return append(']');
    }


    template<typename Val1, typename Val2, typename... Vals>
    FixedString& append(const Val1& val1, const Val2& val2, const Vals&... vals) noexcept
    {
        const auto visitor = [this](const auto& val) {
            (void)append(val);
            return 0;
        };

        const int helper[] = { visitor(val1), visitor(val2), visitor(vals)... };
        (void)helper; // to avoid compiler warnings about non-used local vars

        return *this;
    }

private:
    RawType data_ = { 0 };
    // invariant: contentLength_ + suffixLength < WCharsCapacity
    size_t contentLength_ = 0;
    size_t suffixLength_ = 0;
    bool overflowOccurred_ = false;

private:
    template<size_t FormatStringLength, typename... Args>
    FixedString& appendFormat(const wchar_t (&format)[FormatStringLength], const Args&... args)
    {
        if (overflowOccurred_) {
            return *this;
        }

        const auto offset = contentLength_;
        const int result = swprintf(&data_[0] + offset, WCharsCapacity - offset, format, args...);
        if (result < 0) {
            overflowOccurred_ = true;
        } else {
            contentLength_ += result;
        }
        suffixLength_ = 0;
        ensureTheStringInvariants();

        return *this;
    }

    void ensureTheStringInvariants()
    {
        static_assert(WCharsCapacity > 3, "The capacity must be at least 4 (for the \"...\\0\" suffix");

        if (overflowOccurred_) { // must end with "..."
            suffixLength_ = 3;
            contentLength_ = ( contentLength_ < (WCharsCapacity - suffixLength_) ) ? contentLength_ : WCharsCapacity - suffixLength_ - 1;
            data_[contentLength_]     = L'.';
            data_[contentLength_ + 1] = L'.';
            data_[contentLength_ + 2] = L'.';
        } else if (contentLength_ > WCharsCapacity - 1) {
            // there is no space for terminating 0, so insert the "..." suffix at the end

            suffixLength_ = 3;
            contentLength_ = WCharsCapacity - 1 - suffixLength_;
            data_[contentLength_]     = L'.';
            data_[contentLength_ + 1] = L'.';
            data_[contentLength_ + 2] = L'.';
        } else {
            suffixLength_ = 0;
        }

        data_[contentLength_ + suffixLength_] = 0;
    }
};


template<typename Arg1, typename... Args>
void AwtClipboard::logSevere(JNIEnv * const env, const Arg1& arg1, const Args&... args)
{
    if ( (env == nullptr) || (WClipboardCID == nullptr ) || (logSevereMID == nullptr) ) {
        return;
    }

    FixedString<512> logLine;

    logLine.append(arg1, args...);

    // Append a dot ('.') at the end of the line
    const auto length = logLine.GetLength();
    if ( (length > 0) && (logLine.GetRaw()[length - 1] != L'.') ) {
        logLine.append('.');
    }

    logSevere(env, logLine);
}

template<size_t WCharsCapacity>
void AwtClipboard::logSevere(JNIEnv * const env, const FixedString<WCharsCapacity>& completedString)
{
    if ( (env == nullptr) || (WClipboardCID == nullptr ) || (logSevereMID == nullptr) ) {
        return;
    }

    static_assert(sizeof(completedString.GetRaw()[0]) == sizeof(jchar),
                  "sizeof wchar_t must be equal to sizeof jchar to perform the cast safely");
    const jstring javaString = env->NewString(
        reinterpret_cast<const jchar*>(completedString.GetCStr()),
        completedString.GetLength()
    );
    if (javaString == nullptr) {
        return;
    }
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->DeleteLocalRef(javaString);
        return;
    }

    env->CallStaticVoidMethod(WClipboardCID, logSevereMID, javaString);

    env->DeleteLocalRef(javaString);
}


template<typename Arg1, typename... Args>
void AwtClipboard::logWarning(JNIEnv * const env, const Arg1& arg1, const Args&... args)
{
    if ( (env == nullptr) || (WClipboardCID == nullptr ) || (logWarningMID == nullptr) ) {
        return;
    }

    FixedString<512> logLine;

    logLine.append(arg1, args...);

    // Append a dot ('.') at the end of the line
    const auto length = logLine.GetLength();
    if ( (length > 0) && (logLine.GetRaw()[length - 1] != L'.') ) {
        logLine.append('.');
    }

    logWarning(env, logLine);
}

template<size_t WCharsCapacity>
void AwtClipboard::logWarning(JNIEnv * const env, const FixedString<WCharsCapacity>& completedString)
{
    if ( (env == nullptr) || (WClipboardCID == nullptr ) || (logWarningMID == nullptr) ) {
        return;
    }

    static_assert(sizeof(completedString.GetRaw()[0]) == sizeof(jchar),
                  "sizeof wchar_t must be equal to sizeof jchar to perform the cast safely");
    const jstring javaString = env->NewString(
        reinterpret_cast<const jchar*>(completedString.GetCStr()),
        completedString.GetLength()
    );
    if (javaString == nullptr) {
        return;
    }
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->DeleteLocalRef(javaString);
        return;
    }

    env->CallStaticVoidMethod(WClipboardCID, logWarningMID, javaString);

    env->DeleteLocalRef(javaString);
}


template<typename Arg1, typename... Args>
void AwtClipboard::logInfo(JNIEnv * const env, const Arg1& arg1, const Args&... args)
{
    if ( (env == nullptr) || (WClipboardCID == nullptr ) || (logInfoMID == nullptr) ) {
        return;
    }

    FixedString<512> logLine;

    logLine.append(arg1, args...);

    // Append a dot ('.') at the end of the line
    const auto length = logLine.GetLength();
    if ( (length > 0) && (logLine.GetRaw()[length - 1] != L'.') ) {
        logLine.append('.');
    }

    logInfo(env, logLine);
}

template<size_t WCharsCapacity>
void AwtClipboard::logInfo(JNIEnv * const env, const FixedString<WCharsCapacity>& completedString)
{
    if ( (env == nullptr) || (WClipboardCID == nullptr ) || (logInfoMID == nullptr) ) {
        return;
    }

    static_assert(sizeof(completedString.GetRaw()[0]) == sizeof(jchar),
                  "sizeof wchar_t must be equal to sizeof jchar to perform the cast safely");
    const jstring javaString = env->NewString(
        reinterpret_cast<const jchar*>(completedString.GetCStr()),
        completedString.GetLength()
    );
    if (javaString == nullptr) {
        return;
    }
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->DeleteLocalRef(javaString);
        return;
    }

    env->CallStaticVoidMethod(WClipboardCID, logInfoMID, javaString);

    env->DeleteLocalRef(javaString);
}

// ======================================================================================


/************************************************************************
 * AwtClipboard fields
 */

jmethodID AwtClipboard::lostSelectionOwnershipMID;
jobject AwtClipboard::theCurrentClipboard;

/* This flag is set while we call EmptyClipboard to indicate to
   WM_DESTROYCLIPBOARD handler that we are not losing ownership */
BOOL AwtClipboard::isGettingOwnership = FALSE;

volatile jmethodID AwtClipboard::handleContentsChangedMID;
volatile BOOL AwtClipboard::isClipboardViewerRegistered = FALSE;

#define GALLOCFLG (GMEM_DDESHARE | GMEM_MOVEABLE | GMEM_ZEROINIT)

/************************************************************************
 * AwtClipboard methods
 */

void AwtClipboard::LostOwnership(JNIEnv *env) {
    logInfo(env, "-> AwtClipboard::LostOwnership(", env, ")...");

    logInfo(env, "     theCurrentClipboard=", theCurrentClipboard);

    if (theCurrentClipboard != NULL) {
        logInfo(env, "     falling into if (theCurrentClipboard != NULL) {...");

        env->CallVoidMethod(theCurrentClipboard, lostSelectionOwnershipMID);
        DASSERT(!safe_ExceptionOccurred(env));
    }

    logInfo(env, "<- AwtClipboard::LostOwnership(", env, ").");
}

void AwtClipboard::WmClipboardUpdate(JNIEnv *env) {
    logInfo(env, "-> AwtClipboard::WmClipboardUpdate(env=", env, ")...");

    logInfo(env, "     theCurrentClipboard=", theCurrentClipboard);

    if (theCurrentClipboard != NULL) {
        logInfo(env, "     falling into if (theCurrentClipboard != NULL) {...");

        env->CallVoidMethod(theCurrentClipboard, handleContentsChangedMID);
        DASSERT(!safe_ExceptionOccurred(env));
    }

    logInfo(env, "<- AwtClipboard::WmClipboardUpdate(env=", env, ").");
}

void AwtClipboard::RegisterClipboardViewer(JNIEnv *env, jobject jclipboard) {
    logInfo(env, "-> AwtClipboard::RegisterClipboardViewer(env=", env, ", jclipboard=", jclipboard, ")...");

    logInfo(env, "     isClipboardViewerRegistered=", isClipboardViewerRegistered);
    logInfo(env, "     theCurrentClipboard=", theCurrentClipboard);

    if (isClipboardViewerRegistered) {
        logInfo(env, "     falling into if (isClipboardViewerRegistered) {...");
        logWarning(env, "     A clipboard view has been already registered (isClipboardViewerRegistered is true).");
        logInfo(env, "<- AwtClipboard::RegisterClipboardViewer(env=", env, ", jclipboard=", jclipboard, ").");
        return;
    }

    if (theCurrentClipboard == NULL) {
        logInfo(env, "     falling into if (theCurrentClipboard == NULL) {...");
        theCurrentClipboard = env->NewGlobalRef(jclipboard);
        logInfo(env, "     theCurrentClipboard=", theCurrentClipboard);
    }

    jclass cls = env->GetObjectClass(jclipboard);
    logInfo(env, "     cls=", cls);

    AwtClipboard::handleContentsChangedMID =
            env->GetMethodID(cls, "handleContentsChanged", "()V");
    logInfo(env, "     AwtClipboard::handleContentsChangedMID=", AwtClipboard::handleContentsChangedMID);
    DASSERT(AwtClipboard::handleContentsChangedMID != NULL);

    const auto awtToolkitHwnd = AwtToolkit::GetInstance().GetHWnd();

    logInfo(env, "     calling ::AddClipboardFormatListener(hwnd=", awtToolkitHwnd, ")...");
    ::AddClipboardFormatListener(awtToolkitHwnd);
    isClipboardViewerRegistered = TRUE;

    logInfo(env, "<- AwtClipboard::RegisterClipboardViewer(env=", env, ", jclipboard=", jclipboard, ").");
}

void AwtClipboard::UnregisterClipboardViewer(JNIEnv *env) {
    logInfo(env, "-> AwtClipboard::UnregisterClipboardViewer(env=", env, ")...");

    TRY;

    logInfo(env, "     isClipboardViewerRegistered=", isClipboardViewerRegistered);

    if (isClipboardViewerRegistered) {
        logInfo(env, "     falling into if (isClipboardViewerRegistered) {...");
        const auto awtToolkitHwnd = AwtToolkit::GetInstance().GetHWnd();
        logInfo(env, "     calling ::RemoveClipboardFormatListener(hwnd=", awtToolkitHwnd, ")...");
        ::RemoveClipboardFormatListener(awtToolkitHwnd);
        isClipboardViewerRegistered = FALSE;
    }

    logInfo(env, "<- AwtClipboard::UnregisterClipboardViewer(env=", env, ").");

    CATCH_BAD_ALLOC;
}

extern "C" {

void awt_clipboard_uninitialize(JNIEnv *env) {
    AwtClipboard::logInfo(env, "-> awt_clipboard_uninitialize(env=", env, ")...");
    AwtClipboard::UnregisterClipboardViewer(env);
    env->DeleteGlobalRef(AwtClipboard::theCurrentClipboard);
    AwtClipboard::theCurrentClipboard = NULL;
    AwtClipboard::logInfo(env, "<- awt_clipboard_uninitialize(env=", env, ").");
}

/************************************************************************
 * WClipboard native methods
 */

/*
 * Class:     sun_awt_windows_WClipboard
 * Method:    init
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WClipboard_init(JNIEnv *env, jclass cls)
{
    TRY;

    AwtClipboard::initializeLogging(env, cls);

    AwtClipboard::logInfo(env, "-> Java_sun_awt_windows_WClipboard_init(env=", env, ", cls=", cls, ")...");

    AwtClipboard::lostSelectionOwnershipMID =
        env->GetMethodID(cls, "lostSelectionOwnershipImpl", "()V");
    AwtClipboard::logInfo(env, "     AwtClipboard::lostSelectionOwnershipMID=", AwtClipboard::lostSelectionOwnershipMID, ".");
    DASSERT(AwtClipboard::lostSelectionOwnershipMID != NULL);

    AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_init(env=", env, ", cls=", cls, ").");

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WClipboard
 * Method:    openClipboard
 * Signature: (Lsun/awt/windows/WClipboard;)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WClipboard_openClipboard(JNIEnv *env, jobject self,
                                              jobject newOwner)
{
    AwtClipboard::logInfo(env, "-> Java_sun_awt_windows_WClipboard_openClipboard(env=", env, ", self=", self, ", newOwner=", newOwner, ")...");

    TRY;

    DASSERT(::GetOpenClipboardWindow() != AwtToolkit::GetInstance().GetHWnd());

    const auto awtToolkitHwnd = AwtToolkit::GetInstance().GetHWnd();
    AwtClipboard::logInfo(env, "     awtToolkitHwnd=", awtToolkitHwnd);

    AwtClipboard::logInfo(env, "     calling ::OpenClipboard(hWndNewOwner=", awtToolkitHwnd, ")...");
    const auto openClipboardResult = ::OpenClipboard(awtToolkitHwnd);
    auto lastErr = ::GetLastError();
    AwtClipboard::logInfo(env, "     returned ", openClipboardResult);

    if (!openClipboardResult) {
        AwtClipboard::logInfo(env, "     falling into if (!::OpenClipboard(AwtToolkit::GetInstance().GetHWnd())) {...");
        AwtClipboard::logSevere(env, "::OpenClipboard failed (GetLastError=", lastErr, ")");

        JNU_ThrowByName(env, "java/lang/IllegalStateException",
                        "cannot open system clipboard");

        AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_openClipboard(env=", env, ", self=", self, ", newOwner=", newOwner, ").");
        return;
    }
    if (newOwner != NULL) {
        AwtClipboard::logInfo(env, "     falling into if (newOwner != NULL) {...");

        AwtClipboard::GetOwnership();

        AwtClipboard::logInfo(env, "     AwtClipboard::theCurrentClipboard=", AwtClipboard::theCurrentClipboard);
        if (AwtClipboard::theCurrentClipboard != NULL) {
            AwtClipboard::logInfo(env, "     falling into if (AwtClipboard::theCurrentClipboard != NULL) {...");
            env->DeleteGlobalRef(AwtClipboard::theCurrentClipboard);
        }
        AwtClipboard::theCurrentClipboard = env->NewGlobalRef(newOwner);
        AwtClipboard::logInfo(env, "     AwtClipboard::theCurrentClipboard=", AwtClipboard::theCurrentClipboard);
    }

    AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_openClipboard(env=", env, ", self=", self, ", newOwner=", newOwner, ").");

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WClipboard
 * Method:    closeClipboard
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WClipboard_closeClipboard(JNIEnv *env, jobject self)
{
    AwtClipboard::logInfo(env, "-> Java_sun_awt_windows_WClipboard_closeClipboard(env=", env, ", self=", self, ")...");

    TRY;

    AwtClipboard::logInfo(env, "     calling ::GetOpenClipboardWindow()...");
    const auto getOpenClipboardWindowResult = ::GetOpenClipboardWindow();
    AwtClipboard::logInfo(env, "     returned ", getOpenClipboardWindowResult);

    const auto awtToolkitHwnd = AwtToolkit::GetInstance().GetHWnd();
    AwtClipboard::logInfo(env, "     awtToolkitHwnd=", awtToolkitHwnd);

    if (getOpenClipboardWindowResult == awtToolkitHwnd) {
        AwtClipboard::logInfo(env, "     falling into if (::GetOpenClipboardWindow() == AwtToolkit::GetInstance().GetHWnd()) {...");

        AwtClipboard::logInfo(env, "     calling ::CloseClipboard()...");
        const auto closeClipboardResult = ::CloseClipboard();
        AwtClipboard::logInfo(env, "     returned ", closeClipboardResult);

        VERIFY(closeClipboardResult);
    }

    AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_closeClipboard(env=", env, ", self=", self, ")...");

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WClipboard
 * Method:    registerClipboardViewer
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WClipboard_registerClipboardViewer(JNIEnv *env, jobject self)
{
    AwtClipboard::logInfo(env, "-> Java_sun_awt_windows_WClipboard_registerClipboardViewer(env=", env, ", self=", self, ")...");
    TRY;

    AwtClipboard::RegisterClipboardViewer(env, self);

    AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_registerClipboardViewer(env=", env, ", self=", self, ").");

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WClipboard
 * Method:    publishClipboardData
 * Signature: (J[B)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WClipboard_publishClipboardData(JNIEnv *env,
                                                     jobject self,
                                                     jlong format,
                                                     jbyteArray bytes)
{
    AwtClipboard::logInfo(env, "-> Java_sun_awt_windows_WClipboard_publishClipboardData(env=", env, ", self=", self, ", format=", format, ", bytes=", bytes, ")...");

    TRY;

    DASSERT(::GetOpenClipboardWindow() == AwtToolkit::GetInstance().GetHWnd());

    if (bytes == NULL) {
        AwtClipboard::logInfo(env, "     falling into if (bytes == NULL) {");
        AwtClipboard::logWarning(env, "     bytes == NULL ; returning...");
        AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_publishClipboardData(env=", env, ", self=", self, ", format=", format, ", bytes=", bytes, ").");
        return;
    }

    jint nBytes = env->GetArrayLength(bytes);
    AwtClipboard::logInfo(env, "     nBytes=", nBytes);

    if (format == CF_ENHMETAFILE) {
        AwtClipboard::logInfo(env, "     falling into if (format == CF_ENHMETAFILE) {...");

        LPBYTE lpbEmfBuffer =
            (LPBYTE)env->GetPrimitiveArrayCritical(bytes, NULL);
        AwtClipboard::logInfo(env, "     lpbEmfBuffer=", lpbEmfBuffer);

        if (lpbEmfBuffer == NULL) {
            AwtClipboard::logInfo(env, "     falling into if (lpbEmfBuffer == NULL) {...");
            AwtClipboard::logSevere(env, "     failed to obtain the content of the \"bytes\" array.");
            env->PopLocalFrame(NULL);
            throw std::bad_alloc();
        }

        AwtClipboard::logInfo(env, "     calling ::SetEnhMetaFileBits(nSize=", nBytes, ", pb=", lpbEmfBuffer, ")...");
        HENHMETAFILE hemf = ::SetEnhMetaFileBits(nBytes, lpbEmfBuffer);
        auto lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "     returned ", hemf, " (GetLastError()=", lastErr, ").");

        env->ReleasePrimitiveArrayCritical(bytes, (LPVOID)lpbEmfBuffer,
                                           JNI_ABORT);

        if (hemf != NULL) {
            AwtClipboard::logInfo(env, "     falling into if (hemf != NULL) {...");

            AwtClipboard::logInfo(env, "     calling ::SetClipboardData(uFormat=", (UINT)format, ", hMem=", hemf, ")...");

            const auto setClipboardDataResult = ::SetClipboardData((UINT)format, hemf);
            lastErr = ::GetLastError();

            AwtClipboard::logInfo(env, "     returned ", setClipboardDataResult, " (GetLastError()=", lastErr, ").");

            VERIFY(setClipboardDataResult);
        }

        AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_publishClipboardData(env=", env, ", self=", self, ", format=", format, ", bytes=", bytes, ").");
        return;
    } else if (format == CF_METAFILEPICT) {
        AwtClipboard::logInfo(env, "     falling into if (format == CF_METAFILEPICT) {...");

        LPBYTE lpbMfpBuffer =
            (LPBYTE)env->GetPrimitiveArrayCritical(bytes, NULL);
        AwtClipboard::logInfo(env, "     lpbMfpBuffer=", lpbMfpBuffer);

        if (lpbMfpBuffer == NULL) {
            AwtClipboard::logInfo(env, "     falling into if (lpbMfpBuffer == NULL) {...");
            AwtClipboard::logSevere(env, "     failed to obtain the content of the \"bytes\" array.");
            env->PopLocalFrame(NULL);
            throw std::bad_alloc();
        }

        AwtClipboard::logInfo(env, "     calling ::SetMetaFileBitsEx(cbBuffer=", (UINT)(nBytes - sizeof(METAFILEPICT)), ", lpData=", (const BYTE*)(lpbMfpBuffer + sizeof(METAFILEPICT)), ")...");
        HMETAFILE hmf = ::SetMetaFileBitsEx(nBytes - sizeof(METAFILEPICT),
                                         lpbMfpBuffer + sizeof(METAFILEPICT));
        auto lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "      returned ", hmf, " (GetLastError()=", lastErr, ").");

        if (hmf == NULL) {
            AwtClipboard::logInfo(env, "     falling into if (hmf == NULL) {...");

            env->ReleasePrimitiveArrayCritical(bytes, (LPVOID)lpbMfpBuffer, JNI_ABORT);
            env->PopLocalFrame(NULL);

            AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_publishClipboardData(env=", env, ", self=", self, ", format=", format, ", bytes=", bytes, ").");
            return;
        }

        LPMETAFILEPICT lpMfpOld = (LPMETAFILEPICT)lpbMfpBuffer;
        AwtClipboard::logInfo(env, "      lpMfpOld=", lpMfpOld);

        AwtClipboard::logInfo(env, "      calling ::GlobalAlloc(uFlags=", (UINT)GALLOCFLG, ", dwBytes=", (SIZE_T)sizeof(METAFILEPICT), ")...");
        HMETAFILEPICT hmfp = ::GlobalAlloc(GALLOCFLG, sizeof(METAFILEPICT));
        lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "      returned ", lpMfpOld, " (::GetLastError()=", lastErr, ").");

        if (hmfp == NULL) {
            AwtClipboard::logInfo(env, "     falling into if (hmfp == NULL) {...");
            AwtClipboard::logSevere(env, "     ::GlobalAlloc failed! ::GetLastError()=", lastErr);

            VERIFY(::DeleteMetaFile(hmf));
            env->ReleasePrimitiveArrayCritical(bytes, (LPVOID)lpbMfpBuffer, JNI_ABORT);
            env->PopLocalFrame(NULL);
            throw std::bad_alloc();
        }

        LPMETAFILEPICT lpMfp = (LPMETAFILEPICT)::GlobalLock(hmfp);
        AwtClipboard::logInfo(env, "     lpMfp=", lpMfp);

        lpMfp->mm = lpMfpOld->mm;
        lpMfp->xExt = lpMfpOld->xExt;
        lpMfp->yExt = lpMfpOld->yExt;
        lpMfp->hMF = hmf;

        AwtClipboard::logInfo(env, "     lpMfp->mm=", lpMfp->mm, " ; lpMfp->xExt=", lpMfp->xExt, " ; lpMfp->yExt=", lpMfp->yExt, " ; lpMfp->hMF=", lpMfp->hMF);

        ::GlobalUnlock(hmfp);

        env->ReleasePrimitiveArrayCritical(bytes, (LPVOID)lpbMfpBuffer, JNI_ABORT);

        AwtClipboard::logInfo(env, "     calling ::SetClipboardData(format=", (UINT)format, ", hMem=", (HANDLE)hmfp, ")...");
        const auto setClipboardDataResult = ::SetClipboardData((UINT)format, hmfp);
        lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "     returned ", setClipboardDataResult, " (::GetLastError()=", lastErr, ").");

        VERIFY(setClipboardDataResult);

        AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_publishClipboardData(env=", env, ", self=", self, ", format=", format, ", bytes=", bytes, ").");
        return;
    }

    AwtClipboard::logInfo(env, "     calling ::GlobalAlloc(uFlags=", (UINT)GALLOCFLG, ", dwBytes=", (SIZE_T)(nBytes + ((format == CF_HDROP)? sizeof(DROPFILES): 0)) );
    // We have to prepend the DROPFILES structure here because WDataTransferer
    // doesn't.
    HGLOBAL hglobal = ::GlobalAlloc(GALLOCFLG, nBytes + ((format == CF_HDROP)
                                                            ? sizeof(DROPFILES)
                                                            : 0));
    auto lastErr = ::GetLastError();
    if (hglobal == NULL) {
        AwtClipboard::logInfo(env, "     falling into if (hglobal == NULL) {...");
        AwtClipboard::logSevere(env, "     ::GlobalAlloc failed! ::GetLastError()=", lastErr);
        throw std::bad_alloc();
    }
    char *dataout = (char *)::GlobalLock(hglobal);
    AwtClipboard::logInfo(env, "     dataout=", (void*)dataout);

    if (format == CF_HDROP) {
        AwtClipboard::logInfo(env, "     falling into if (format == CF_HDROP) {...");

        DROPFILES *dropfiles = (DROPFILES *)dataout;
        AwtClipboard::logInfo(env, "     dropfiles=", dropfiles);

        dropfiles->pFiles = sizeof(DROPFILES);
        dropfiles->fWide = TRUE; // we publish only Unicode
        AwtClipboard::logInfo(env, "     dropfiles->pFiles=", dropfiles->pFiles, " ; dropfiles->fWide=", dropfiles->fWide);

        dataout += sizeof(DROPFILES);
        AwtClipboard::logInfo(env, "     dataOut=", (void*)dataout);
    }

    env->GetByteArrayRegion(bytes, 0, nBytes, (jbyte *)dataout);
    AwtClipboard::logInfo(env, "     dataout=", AwtClipboard::fmtArr((unsigned char*)dataout, nBytes));

    AwtClipboard::logInfo(env, "     calling ::GlobalUnlock(hMem=", hglobal, ")...");
    {
        auto globalUnlockResult = ::GlobalUnlock(hglobal);
        lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "     returned ", globalUnlockResult, " (::GetLastError()=", lastErr, ").");
    }

    AwtClipboard::logInfo(env, "     calling ::SetClipboardData(uFormat=", (UINT)format, ", hMem=", hglobal, ")...");
    {
        const auto setClipboardDataResult = ::SetClipboardData((UINT)format, hglobal);
        lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "     returned ", setClipboardDataResult, " (::GetLastError()=", lastErr, ").");

        VERIFY(setClipboardDataResult);
    }

    AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_publishClipboardData(env=", env, ", self=", self, ", format=", format, ", bytes=", bytes, ").");

    CATCH_BAD_ALLOC;
}

/*
 * Class:     sun_awt_windows_WClipboard
 * Method:    getClipboardFormats
 * Signature: ()[J
 */
JNIEXPORT jlongArray JNICALL
Java_sun_awt_windows_WClipboard_getClipboardFormats
    (JNIEnv *env, jobject self)
{
    AwtClipboard::logInfo(env, "-> Java_sun_awt_windows_WClipboard_getClipboardFormats(env=", env, ", self=", self, ")...");

    TRY;

    DASSERT(::GetOpenClipboardWindow() == AwtToolkit::GetInstance().GetHWnd());

    jsize nFormats = ::CountClipboardFormats();
    AwtClipboard::logInfo(env, "     nFormats=", nFormats);

    jlongArray formats = env->NewLongArray(nFormats);
    AwtClipboard::logInfo(env, "     formats=", formats);

    if (formats == NULL) {
        AwtClipboard::logInfo(env, "     falling into if (formats == NULL) {...");
        AwtClipboard::logSevere(env, "     JNIEnv::NewLongArray failed.");

        throw std::bad_alloc();
    }
    if (nFormats == 0) {
        AwtClipboard::logInfo(env, "     falling into if (nFormats == 0) {...");
        AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardFormats(env=", env, ", self=", self, "): returning ", formats);
        return formats;
    }
    jboolean isCopy;
    jlong *lFormats = env->GetLongArrayElements(formats, &isCopy);
    jlong *saveFormats = lFormats;
    UINT num = 0;

    AwtClipboard::logInfo(env, "     lFormats=", lFormats, ", isCopy=", isCopy, ", saveFormats=", saveFormats);

    for (jsize i = 0; i < nFormats; i++, lFormats++) {
        *lFormats = num = ::EnumClipboardFormats(num);
    }

    AwtClipboard::logInfo(env, "     saveFormats=", AwtClipboard::fmtArr(saveFormats, nFormats));

    env->ReleaseLongArrayElements(formats, saveFormats, 0);

    AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardFormats(env=", env, ", self=", self, "): returning ", formats);

    return formats;

    CATCH_BAD_ALLOC_RET(NULL);
}

/*
 * Class:     sun_awt_windows_WClipboard
 * Method:    getClipboardData
 * Signature: (J)[B
 */
JNIEXPORT jbyteArray JNICALL
Java_sun_awt_windows_WClipboard_getClipboardData
    (JNIEnv *env, jobject self, jlong format)
{
    AwtClipboard::logInfo(env, "-> Java_sun_awt_windows_WClipboard_getClipboardData(env=", env, ", self=", self, ", format=", format, ")...");

    TRY;

    DASSERT(::GetOpenClipboardWindow() == AwtToolkit::GetInstance().GetHWnd());

    AwtClipboard::logInfo(env, "     calling ::GetClipboardData(uFormat=", (UINT)format, ")...");
    HANDLE handle = ::GetClipboardData((UINT)format);
    auto lastErr = ::GetLastError();
    AwtClipboard::logInfo(env, "     returned handle=", handle);

    if (handle == NULL) {
        AwtClipboard::logInfo(env, "     falling into if (handle == NULL) {...");
        AwtClipboard::logSevere(env, "     ::GetClipboardData failed! ::GetLastError()=", lastErr);

        JNU_ThrowIOException(env, "system clipboard data unavailable");

        AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardData(env=", env, ", self=", self, ", format=", format, "): returning NULL.");
        return NULL;
    }

    jbyteArray bytes = NULL;
    jbyteArray paletteData = NULL;

    switch (format) {
    case CF_ENHMETAFILE:
    case CF_METAFILEPICT: {
        AwtClipboard::logInfo(env, "     falling into case CF_ENHMETAFILE, CF_METAFILEPICT:...");

        HENHMETAFILE hemf = NULL;

        if (format == CF_METAFILEPICT) {
            AwtClipboard::logInfo(env, "     falling into if (format == CF_METAFILEPICT) {...");

            HMETAFILEPICT hMetaFilePict = (HMETAFILEPICT)handle;

            AwtClipboard::logInfo(env, "     calling ::GlobalLock(hMem=", (HGLOBAL)hMetaFilePict, ")...");
            LPMETAFILEPICT lpMetaFilePict =
                (LPMETAFILEPICT)::GlobalLock(hMetaFilePict);
            lastErr = ::GetLastError();
            AwtClipboard::logInfo(env, "     returned lpMetaFilePict=", lpMetaFilePict, " (::GetLastError()=", lastErr, ").");

            AwtClipboard::logInfo(env, "     calling ::GetMetaFileBitsEx(hMF=", (HMETAFILE)lpMetaFilePict->hMF, ", cbBuffer=0, lpData=NULL)...");
            UINT uSize = ::GetMetaFileBitsEx(lpMetaFilePict->hMF, 0, NULL);
            lastErr = ::GetLastError();
            AwtClipboard::logInfo(env, "     returned uSize=", uSize, " (::GetLastError()=", lastErr, ").");

            DASSERT(uSize != 0);

            try {
                LPBYTE lpMfBits = (LPBYTE)safe_Malloc(uSize);
                AwtClipboard::logInfo(env, "     lpMfBits=", lpMfBits);

                AwtClipboard::logInfo(env, "     calling ::GetMetaFileBitsEx(hMF=", (HMETAFILE)lpMetaFilePict->hMF, ", cbBuffer=", uSize, ", lpData=", lpMfBits, ")...");
                const auto getMetaFileBitsExResult = ::GetMetaFileBitsEx(lpMetaFilePict->hMF, uSize, lpMfBits);
                lastErr = ::GetLastError();
                AwtClipboard::logInfo(env, "     returned ", getMetaFileBitsExResult, " (::GetLastError()=", lastErr, ").");

                VERIFY(getMetaFileBitsExResult == uSize);

                AwtClipboard::logInfo(env, "     calling ::SetWinMetaFileBits(nSize=", uSize, ", lpMeta16Data=", (const BYTE*)lpMfBits, ", hdcRef=NULL, lpMFP=", lpMetaFilePict, ")...");
                hemf = ::SetWinMetaFileBits(uSize, lpMfBits, NULL,
                                            lpMetaFilePict);
                lastErr = ::GetLastError();
                AwtClipboard::logInfo(env, "     returned hemf=", hemf, " (::GetLastError()=", lastErr, ").");

                free(lpMfBits);
                if (hemf == NULL) {
                    AwtClipboard::logInfo(env, "     falling into if (hemf == NULL) {...");
                    AwtClipboard::logSevere(env, "     ::SetWinMetaFileBits failed! ::GetLastError()=", lastErr);

                    ::GlobalUnlock(hMetaFilePict);
                    JNU_ThrowIOException(env, "failed to get system clipboard data");

                    AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardData(env=", env, ", self=", self, ", format=", format, "): returning NULL.");
                    return NULL;
                }
            } catch (...) {
                AwtClipboard::logSevere(env, "     an unknown native exception occurred.");

                ::GlobalUnlock(hMetaFilePict);
                throw;
            }

            AwtClipboard::logInfo(env, "     calling ::GlobalUnlock(hMem=", (HGLOBAL)hMetaFilePict, ")...");
            AwtClipboard::logInfo(env, "     returned ",
            ::GlobalUnlock(hMetaFilePict));
        } else {
            AwtClipboard::logInfo(env, "     falling into ELSE of if (format == CF_METAFILEPICT) {...");
            hemf = (HENHMETAFILE)handle;
            AwtClipboard::logInfo(env, "     hemf=", hemf);
        }

        AwtClipboard::logInfo(env, "     calling ::GetEnhMetaFileBits(hEMF=", (HENHMETAFILE)hemf, ", nSize=0, lpData=NULL)...");
        UINT uEmfSize = ::GetEnhMetaFileBits(hemf, 0, NULL);
        lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "     returned uEmfSize=", uEmfSize, " (::GetLastError()=", lastErr, ").");

        if (uEmfSize == 0) {
            AwtClipboard::logInfo(env, "     falling into if (uEmfSize == 0) {...");
            AwtClipboard::logSevere(env, "     ::GetEnhMetaFileBits failed! ::GetLastError()=", lastErr);

            JNU_ThrowIOException(env, "cannot retrieve metafile bits");

            AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardData(env=", env, ", self=", self, ", format=", format, "): returning NULL.");
            return NULL;
        }

        bytes = env->NewByteArray(uEmfSize);
        AwtClipboard::logInfo(env, "     bytes=", bytes);

        if (bytes == NULL) {
            AwtClipboard::logInfo(env, "     falling into if (bytes == NULL) {...");
            throw std::bad_alloc();
        }

        LPBYTE lpbEmfBuffer =
            (LPBYTE)env->GetPrimitiveArrayCritical(bytes, NULL);
        AwtClipboard::logInfo(env, "     lpbEmfBuffer=", lpbEmfBuffer);

        if (lpbEmfBuffer == NULL) {
            AwtClipboard::logInfo(env, "     falling into if (lpbEmfBuffer == NULL) {...");
            env->DeleteLocalRef(bytes);
            throw std::bad_alloc();
        }

        AwtClipboard::logInfo(env, "     calling ::GetEnhMetaFileBits(hEMF=", (HENHMETAFILE)hemf, ", nSize=", (UINT)uEmfSize, ", lpData=", (LPBYTE)lpbEmfBuffer,")...");
        const auto getEnhMetaFileBitsResult = ::GetEnhMetaFileBits(hemf, uEmfSize, lpbEmfBuffer);
        lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "     returned ", getEnhMetaFileBitsResult, " (::GetLastError()=", lastErr, ")");

        VERIFY(getEnhMetaFileBitsResult == uEmfSize);
        env->ReleasePrimitiveArrayCritical(bytes, lpbEmfBuffer, 0);

        paletteData =
            AwtDataTransferer::GetPaletteBytes(hemf, OBJ_ENHMETAFILE, FALSE);
        AwtClipboard::logInfo(env, "     paletteData=", paletteData);

        break;
    }
    case CF_LOCALE: {
        AwtClipboard::logInfo(env, "     falling into case CF_LOCALE:...");

        AwtClipboard::logInfo(env, "     calling ::GlobalLock(hMem=", (HGLOBAL)handle, ")...");
        LCID *lcid = (LCID *)::GlobalLock(handle);
        lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "     returned lcid=", lcid, " (::GetLastError()=", lastErr, ")");

        if (lcid == NULL) {
            AwtClipboard::logInfo(env, "     falling into if (lcid == NULL) {...");
            JNU_ThrowIOException(env, "invalid LCID");

            AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardData(env=", env, ", self=", self, ", format=", format, "): returning NULL.");
            return NULL;
        }
        try {
            bytes = AwtDataTransferer::LCIDToTextEncoding(env, *lcid);
            AwtClipboard::logInfo(env, "     bytes=", bytes);
        } catch (...) {
            ::GlobalUnlock(handle);
            throw;
        }

        AwtClipboard::logInfo(env, "     calling ::GlobalUnlock(hMem=", (HGLOBAL)handle, ")...");
        const auto globalUnlockResult = ::GlobalUnlock(handle);
        lastErr = ::GetLastError();
        AwtClipboard::logInfo(env, "     returned ", globalUnlockResult, " (::GetLastError()=", lastErr, ").");

        break;
    }
    default: {
        AwtClipboard::logInfo(env, "     falling into default:...");

        ::SetLastError(0); // clear error
        // Warning C4244.
        // Cast SIZE_T (__int64 on 64-bit/unsigned int on 32-bit)
        // to jsize (long).
        SIZE_T globalSize = ::GlobalSize(handle);
        jsize size = (globalSize <= INT_MAX) ? (jsize)globalSize : INT_MAX;

        AwtClipboard::logInfo(env, "     globalSize=", globalSize, " ; size=", size);

        if (::GetLastError() != 0) {
            AwtClipboard::logInfo(env, "     falling into if (::GetLastError() != 0) {...");

            JNU_ThrowIOException(env, "invalid global memory block handle");

            AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardData(env=", env, ", self=", self, ", format=", format, "): returning NULL.");
            return NULL;
        }

        bytes = env->NewByteArray(size);
        AwtClipboard::logInfo(env, "     bytes=", bytes);

        if (bytes == NULL) {
            AwtClipboard::logInfo(env, "     falling into if (bytes == NULL) {...");

            throw std::bad_alloc();
        }

        if (size != 0) {
            AwtClipboard::logInfo(env, "     falling into if (size != 0) {...");

            AwtClipboard::logInfo(env, "     calling ::GlobalLock(hMem=", (HGLOBAL)handle, ")...");
            LPVOID data = ::GlobalLock(handle);
            lastErr = ::GetLastError();
            AwtClipboard::logInfo(env, "     returned data=", data, " (::GetLastError()=", lastErr, ")");

            env->SetByteArrayRegion(bytes, 0, size, (jbyte *)data);

            AwtClipboard::logInfo(env, "     calling ::GlobalUnlock(hMem=", (HGLOBAL)handle, ")...");
            const auto globalUnlockResult = ::GlobalUnlock(handle);
            lastErr = ::GetLastError();
            AwtClipboard::logInfo(env, "     returned ", globalUnlockResult, " (::GetLastError()=", lastErr, ").");
        }
        break;
    }
    }

    switch (format) {
    case CF_ENHMETAFILE:
    case CF_METAFILEPICT:
    case CF_DIB: {
        AwtClipboard::logInfo(env, "     falling into case CF_ENHMETAFILE, CF_METAFILEPICT, CF_DIB:...");

        if (JNU_IsNull(env, paletteData)) {
            AwtClipboard::logInfo(env, "     falling into if (JNU_IsNull(env, paletteData)) {...");

            AwtClipboard::logInfo(env, "     calling ::GetClipboardData(uFormat=CF_PALETTE)...");
            HPALETTE hPalette = (HPALETTE)::GetClipboardData(CF_PALETTE);
            lastErr = ::GetLastError();
            AwtClipboard::logInfo(env, "     returned hPalette=", hPalette, " (::GetLastError()=", lastErr, ")");

            paletteData =
                AwtDataTransferer::GetPaletteBytes(hPalette, OBJ_PAL, TRUE);
            AwtClipboard::logInfo(env, "     paletteData=", paletteData);
        }
        DASSERT(!JNU_IsNull(env, paletteData) &&
                !JNU_IsNull(env, bytes));

        jbyteArray concat =
            (jbyteArray)AwtDataTransferer::ConcatData(env, paletteData, bytes);
        AwtClipboard::logInfo(env, "     concat=", concat);

        if (!JNU_IsNull(env, safe_ExceptionOccurred(env))) {
            AwtClipboard::logInfo(env, "     falling into if (!JNU_IsNull(env, safe_ExceptionOccurred(env))) {...");

            env->ExceptionDescribe();
            env->ExceptionClear();
            env->DeleteLocalRef(bytes);
            env->DeleteLocalRef(paletteData);

            AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardData(env=", env, ", self=", self, ", format=", format, "): returning NULL.");
            return NULL;
        }

        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(paletteData);

        bytes = concat;
        AwtClipboard::logInfo(env, "     bytes=", bytes);

        break;
    }
    }

    AwtClipboard::logInfo(env, "<- Java_sun_awt_windows_WClipboard_getClipboardData(env=", env, ", self=", self, ", format=", format, "): returning ", bytes);
    return bytes;

    CATCH_BAD_ALLOC_RET(NULL);
}

} /* extern "C" */
