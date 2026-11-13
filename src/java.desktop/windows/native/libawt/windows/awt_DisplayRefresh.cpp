/*
 * Copyright 2026 JetBrains s.r.o.
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

#include "awt.h"
#include <jdk_util.h>
#include "awt_Win32GraphicsDevice.h"
#include "Devices.h"

#include <dxgi.h>

typedef HRESULT (WINAPI *CreateDXGIFactory1Type)(REFIID, void **);

/*
 * DXGI is loaded on demand: libawt has no link-time dependency on it.
 */
static CreateDXGIFactory1Type GetCreateDXGIFactory1() {
    static BOOL initialized = FALSE;
    static CreateDXGIFactory1Type function = NULL;
    if (!initialized) {
        HMODULE dxgi = JDK_LoadSystemLibrary("dxgi.dll");
        if (dxgi != NULL) {
            function = (CreateDXGIFactory1Type) ::GetProcAddress(dxgi, "CreateDXGIFactory1");
        }
        initialized = TRUE;
    }
    return function;
}

/*
 * Returns the DXGI output that drives the monitor, with one reference, or NULL.
 */
static IDXGIOutput *FindOutput(HMONITOR monitor) {
    CreateDXGIFactory1Type createFactory = GetCreateDXGIFactory1();
    if (createFactory == NULL || monitor == NULL) {
        return NULL;
    }
    IDXGIFactory1 *factory = NULL;
    if (FAILED(createFactory(__uuidof(IDXGIFactory1), (void **) &factory)) || factory == NULL) {
        return NULL;
    }
    IDXGIOutput *result = NULL;
    IDXGIAdapter1 *adapter = NULL;
    for (UINT a = 0; result == NULL && SUCCEEDED(factory->EnumAdapters1(a, &adapter)); a++) {
        IDXGIOutput *output = NULL;
        for (UINT o = 0; result == NULL && SUCCEEDED(adapter->EnumOutputs(o, &output)); o++) {
            DXGI_OUTPUT_DESC desc;
            if (SUCCEEDED(output->GetDesc(&desc)) && desc.Monitor == monitor) {
                result = output;
            } else {
                output->Release();
            }
        }
        adapter->Release();
    }
    factory->Release();
    return result;
}

extern "C" {

/*
 * Class:     sun_awt_windows_WDisplayRefreshSource
 * Method:    nativeIsRemoteSession
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL
Java_sun_awt_windows_WDisplayRefreshSource_nativeIsRemoteSession(JNIEnv *env, jclass cls) {
    return ::GetSystemMetrics(SM_REMOTESESSION) != 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     sun_awt_windows_WDisplayRefreshSource
 * Method:    nativeOpenOutput
 * Signature: (I)J
 */
JNIEXPORT jlong JNICALL
Java_sun_awt_windows_WDisplayRefreshSource_nativeOpenOutput(JNIEnv *env, jclass cls, jint screen) {
    TRY;

    HMONITOR monitor = NULL;
    {
        Devices::InstanceAccess devices;
        AwtWin32GraphicsDevice *device = devices->GetDevice(screen);
        if (device != NULL) {
            monitor = device->GetMonitor();
        }
    }
    return ptr_to_jlong(FindOutput(monitor));

    CATCH_BAD_ALLOC_RET(0);
}

/*
 * Class:     sun_awt_windows_WDisplayRefreshSource
 * Method:    nativeWaitForVBlank
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL
Java_sun_awt_windows_WDisplayRefreshSource_nativeWaitForVBlank(JNIEnv *env, jclass cls, jlong ptr) {
    IDXGIOutput *output = (IDXGIOutput *) jlong_to_ptr(ptr);
    return output != NULL && SUCCEEDED(output->WaitForVBlank()) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     sun_awt_windows_WDisplayRefreshSource
 * Method:    nativeCloseOutput
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_sun_awt_windows_WDisplayRefreshSource_nativeCloseOutput(JNIEnv *env, jclass cls, jlong ptr) {
    IDXGIOutput *output = (IDXGIOutput *) jlong_to_ptr(ptr);
    if (output != NULL) {
        output->Release();
    }
}

} /* extern "C" */
