#include <mutex>
#include <condition_variable>
#include <functional>

#include <jni.h>
#include <windows.h>
#include <winuser.h>

static const UINT INTERRUPT = WM_USER + 1;

struct Context {
    DWORD threadId = 0;

    bool ready = false;
    std::mutex mu;
    std::condition_variable cv;
    std::function<void()> fn;

    void markReady() noexcept {
        {
            std::unique_lock<std::mutex> lock(mu);
            ready = true;
        }
        cv.notify_all();
    }

    void processInPollThread(std::function<void()> func) noexcept {
        std::unique_lock<std::mutex> lock(mu);
        while (!ready || fn) {
            cv.wait(lock);
        }

        if (GetCurrentThreadId() == threadId) {
            func();
            return;
        }

        fn = func;
        PostThreadMessage(threadId, INTERRUPT, 0, 0);
        while (fn) {
            cv.wait(lock);
        }
    }

    void runQueuedFunction() noexcept {
        std::unique_lock<std::mutex> lock(mu);
        if (fn) {
            fn();
            fn = {};
            lock.unlock();
            cv.notify_all();
        }
    }
};

extern "C" JNIEXPORT jboolean JNICALL Java_com_jetbrains_hotkey_WindowsGlobalHotkeyProvider_nativeRegisterHotkey(
        JNIEnv *jniEnv,
        jclass jniClass,
        jlong ctxPtr,
        jint id,
        jint keyCode,
        jint modifiers
) noexcept {
    auto ctx = reinterpret_cast<Context*>(ctxPtr);
    bool result = false;
    ctx->processInPollThread([=, &result]() {
        result = RegisterHotKey(
                NULL,
                static_cast<int>(id),
                static_cast<UINT>(modifiers),
                static_cast<UINT>(keyCode)
        );
    });
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_jetbrains_hotkey_WindowsGlobalHotkeyProvider_nativeUnregisterHotkey(
        JNIEnv *jniEnv,
        jclass jniClass,
        jlong ctxPtr,
        jint id
) noexcept {
    auto ctx = reinterpret_cast<Context*>(ctxPtr);
    ctx->processInPollThread([=]() {
        UnregisterHotKey(NULL, id);
    });
}

extern "C" JNIEXPORT jint JNICALL Java_com_jetbrains_hotkey_WindowsGlobalHotkeyProvider_nativePollHotkey(
        JNIEnv *jniEnv,
        jclass jniClass,
        jlong ctxPtr
) noexcept {
    auto ctx = reinterpret_cast<Context*>(ctxPtr);
    MSG msg = {0};
    while (GetMessage(&msg, NULL, 0, 0) != 0) {
        if (msg.message == WM_HOTKEY) {
            return static_cast<jint>(msg.wParam);
        }
        ctx->runQueuedFunction();
    }

    return 0;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_jetbrains_hotkey_WindowsGlobalHotkeyProvider_nativeCreateContext(
        JNIEnv *jniEnv,
        jclass jniClass
) noexcept {
    auto ctx = new Context{};
    auto ctxPtr = reinterpret_cast<jlong>(ctx);
    return ctxPtr;
}

extern "C" JNIEXPORT void JNICALL Java_com_jetbrains_hotkey_WindowsGlobalHotkeyProvider_nativeDestroyContext(
        JNIEnv *jniEnv,
        jclass jniClass,
        jlong ctxPtr
) noexcept {
    auto ctx = reinterpret_cast<Context*>(ctxPtr);
    delete ctx;
}

extern "C" JNIEXPORT void JNICALL Java_com_jetbrains_hotkey_WindowsGlobalHotkeyProvider_nativeInitContext(
        JNIEnv *jniEnv,
        jclass jniClass,
        jlong ctxPtr
) noexcept {
    auto ctx = reinterpret_cast<Context*>(ctxPtr);
    ctx->threadId = GetCurrentThreadId();

    // Force thread message queue creation
    MSG msg;
    PeekMessage(&msg, NULL, 0, 0, PM_NOREMOVE);

    ctx->markReady();
}