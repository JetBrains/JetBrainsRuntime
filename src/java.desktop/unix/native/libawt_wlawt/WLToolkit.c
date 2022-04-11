/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

#ifdef HEADLESS
    #error This file should not be included in headless library
#endif


#include <stdbool.h>
#include <dlfcn.h>
#include <string.h>
#include <poll.h>
#include <errno.h>
#include <Trace.h>

#include "jni_util.h"
#include "sun_awt_wl_WLToolkit.h"
#include "WLToolkit.h"
#include "WLRobotPeer.h"

#ifdef WAKEFIELD_ROBOT
#include "wakefield-client-protocol.h"
#include "sun_awt_wl_WLRobotPeer.h"
#endif

extern JavaVM *jvm;

struct wl_display *wl_display = NULL;
struct wl_shm *wl_shm = NULL;
struct wl_compositor *wl_compositor = NULL;
struct xdg_wm_base *xdg_wm_base = NULL;

JNIEnv *getEnv() {
    JNIEnv *env;
    // assuming we're always called from Java thread
    (*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_2);
    return env;
}

static void xdg_wm_base_ping(void *data, struct xdg_wm_base *xdg_wm_base, uint32_t serial) {
    xdg_wm_base_pong(xdg_wm_base, serial);
}

static const struct xdg_wm_base_listener xdg_wm_base_listener = {
        .ping = xdg_wm_base_ping,
};

static void registry_global(void *data, struct wl_registry *wl_registry,
                            uint32_t name, const char *interface, uint32_t version) {
    if (strcmp(interface, wl_shm_interface.name) == 0) {
        wl_shm = wl_registry_bind( wl_registry, name, &wl_shm_interface, 1);
    } else if (strcmp(interface, wl_compositor_interface.name) == 0) {
        wl_compositor = wl_registry_bind(wl_registry, name, &wl_compositor_interface, 4);
    } else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
        xdg_wm_base = wl_registry_bind(wl_registry, name, &xdg_wm_base_interface, 1);
        xdg_wm_base_add_listener(xdg_wm_base, &xdg_wm_base_listener, NULL);
    }
#ifdef WAKEFIELD_ROBOT
    else if (strcmp(interface, wakefield_interface.name) == 0) {
        wakefield = wl_registry_bind(wl_registry, name, &wakefield_interface, 1);
        wakefield_add_listener(wakefield, &wakefield_listener, NULL);
        robot_queue = wl_display_create_queue(wl_display);
        if (robot_queue == NULL) {
            J2dTrace(J2D_TRACE_ERROR, "WLToolkit: Failed to create wakefield robot queue\n");
            wakefield_destroy(wakefield);
            wakefield = NULL;
        } else {
            wl_proxy_set_queue((struct wl_proxy*)wakefield, robot_queue);
        }
        // TODO: call before destroying the display:
        //  wl_event_queue_destroy(robot_queue);
    }
#endif
}

static void registry_global_remove(void *data, struct wl_registry *wl_registry, uint32_t name) {
}

static const struct wl_registry_listener wl_registry_listener = {
        .global = registry_global,
        .global_remove = registry_global_remove,
};

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_initIDs
  (JNIEnv *env, jclass clazz)
{
    wl_display = wl_display_connect(NULL);
    if (!wl_display) {
        J2dTrace(J2D_TRACE_ERROR, "WLToolkit: Failed to connect to Wayland display\n");
        JNU_ThrowByName(env, "java/awt/AWTError", "Can't connect to the Wayland server");
        return;
    }

    struct wl_registry *wl_registry = wl_display_get_registry(wl_display);
    wl_registry_add_listener(wl_registry, &wl_registry_listener, NULL);
    wl_display_roundtrip(wl_display);
    J2dTrace1(J2D_TRACE_INFO, "WLToolkit: Connection to display(%p) established\n", wl_display);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_dispatchEventsOnEDT
  (JNIEnv *env, jobject obj)
{
    // Dispatch all the events on the display's default event queue.
    // The handlers of those events will be called from here, i.e. on EDT,
    // and therefore must not block indefinitely.
    wl_display_dispatch_pending(wl_display);
}

static int
wl_display_poll(struct wl_display *display, int events, int poll_timeout)
{
    int rc = 0;
    struct pollfd pfd[1] = { {.fd = wl_display_get_fd(display), .events = events} };
    do {
        errno = 0;
        rc = poll(pfd, 1, poll_timeout);
    } while (rc == -1 && errno == EINTR);
    return rc;
}

static void
dispatch_nondefault_queues(JNIEnv *env)
{
    // The handlers of the events on these queues will be called from here, i.e. on
    // the 'AWT-Wayland' (toolkit) thread. The handlers must *not* execute any
    // arbitrary user code that can block.
    int rc = 0;

#ifdef WAKEFIELD_ROBOT
    if (robot_queue) {
        rc = wl_display_dispatch_queue_pending(wl_display, robot_queue);
    }
#endif

    if (rc < 0) {
        JNU_ThrowByName(env, "java/awt/AWTError", "Wayland error during events processing");
        return;
    }
}

int
wl_flush_to_server(JNIEnv *env)
{
    int rc = 0;

    while (true) {
        errno = 0;
        rc = wl_display_flush(wl_display);
        if (rc != -1 || errno != EAGAIN) {
            break;
        }

        rc = wl_display_poll(wl_display, POLLOUT, -1);
        if (rc == -1) {
            JNU_ThrowByName(env, "java/awt/AWTError", "Wayland display error polling out to the server");
            return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
        }
    }

    if (rc < 0 && errno != EPIPE) {
        JNU_ThrowByName(env, "java/awt/AWTError", "Wayland display error flushing data out to the server");
        return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
    }

    return 0;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_flushImpl
  (JNIEnv *env, jobject obj)
{
    (void) wl_flush_to_server(env);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLToolkit_dispatchNonDefaultQueuesImpl
  (JNIEnv *env, jobject obj)
{
    int rc = 0;
    while (rc != -1) {
#ifdef WAKEFIELD_ROBOT
        if (robot_queue) {
            rc = wl_display_dispatch_queue(wl_display, robot_queue);
        } else {
            break;
        }
#else
    break;
#endif
    }
    // Simply return in case of any error; the actual error reporting (exception)
    // and/or shutdown will happen on the "main" toolkit thread AWT-Wayland,
    // see readEvents() below.
}

JNIEXPORT jint JNICALL
Java_sun_awt_wl_WLToolkit_readEvents
  (JNIEnv *env, jobject obj)
{
    // NB: this method should be modeled after wl_display_dispatch_queue() from the Wayland code
    int rc = 0;

    // Check if there's anything in the default event queue already *and*
    // lock the queue for this thread.
    rc = wl_display_prepare_read(wl_display);
    if (rc != 0) {
        // Ask the caller to give dispatchEventsOnEDT() more time to process
        // the default queue before calling us again.
        return sun_awt_wl_WLToolkit_READ_RESULT_NO_NEW_EVENTS;
    }

    rc = wl_flush_to_server(env);
    if (rc != 0) {
        wl_display_cancel_read(wl_display);
        return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
    }

    // Wait for new data *from* the server.
    // Specify some timeout because otherwise 'flush' above that sends data
    // to the server will have to wait too long.
    rc = wl_display_poll(wl_display, POLLIN,
                         sun_awt_wl_WLToolkit_WAYLAND_DISPLAY_INTERACTION_TIMEOUT_MS);
    if (rc == -1) {
        wl_display_cancel_read(wl_display);
        JNU_ThrowByName(env, "java/awt/AWTError", "Wayland display error polling for data from the server");
        return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
    }

    // Transform the data read by the above call into events on the corresponding queues of the display.
    rc = wl_display_read_events(wl_display);
    if (rc == -1) { // display disconnect has likely happened
        return sun_awt_wl_WLToolkit_READ_RESULT_ERROR;
    }

    rc = wl_display_prepare_read(wl_display);
    if (rc != 0) {
        return sun_awt_wl_WLToolkit_READ_RESULT_FINISHED_WITH_EVENTS;
    } else {
        wl_display_cancel_read(wl_display);
        return sun_awt_wl_WLToolkit_READ_RESULT_FINISHED_NO_EVENTS;
    }
}

JNIEXPORT jint JNICALL
DEF_JNI_OnLoad(JavaVM *vm, void *reserved)
{
    jvm = vm;

    return JNI_VERSION_1_2;
}


JNIEXPORT void JNICALL
Java_java_awt_Component_initIDs
  (JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL
Java_java_awt_Container_initIDs
  (JNIEnv *env, jclass cls)
{

}


JNIEXPORT void JNICALL
Java_java_awt_Button_initIDs
  (JNIEnv *env, jclass cls)
{

}

JNIEXPORT void JNICALL
Java_java_awt_Scrollbar_initIDs
  (JNIEnv *env, jclass cls)
{

}


JNIEXPORT void JNICALL
Java_java_awt_Window_initIDs
  (JNIEnv *env, jclass cls)
{

}

JNIEXPORT void JNICALL
Java_java_awt_Frame_initIDs
  (JNIEnv *env, jclass cls)
{

}


JNIEXPORT void JNICALL
Java_java_awt_MenuComponent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Cursor_initIDs(JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL Java_java_awt_MenuItem_initIDs
  (JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL Java_java_awt_Menu_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_TextArea_initIDs
  (JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL
Java_java_awt_Checkbox_initIDs
  (JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL Java_java_awt_ScrollPane_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_TextField_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL Java_java_awt_Dialog_initIDs (JNIEnv *env, jclass cls)
{
}



/*
 * Class:     java_awt_TrayIcon
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_java_awt_TrayIcon_initIDs(JNIEnv *env , jclass clazz)
{
}


/*
 * Class:     java_awt_Cursor
 * Method:    finalizeImpl
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_java_awt_Cursor_finalizeImpl(JNIEnv *env, jclass clazz, jlong pData)
{
}

JNIEXPORT void JNICALL
Java_java_awt_FileDialog_initIDs
  (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_AWTEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Insets_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_KeyboardFocusManager_initIDs
    (JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Font_initIDs(JNIEnv *env, jclass cls) {
}

JNIEXPORT void JNICALL
Java_java_awt_event_InputEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_event_KeyEvent_initIDs(JNIEnv *env, jclass cls)
{
}

JNIEXPORT void JNICALL
Java_java_awt_Event_initIDs(JNIEnv *env, jclass cls)
{
}


JNIEXPORT void JNICALL
Java_sun_awt_SunToolkit_closeSplashScreen(JNIEnv *env, jclass cls)
{
    typedef void (*SplashClose_t)();
    SplashClose_t splashClose;
    void* hSplashLib = dlopen(0, RTLD_LAZY);
    if (!hSplashLib) {
        return;
    }
    splashClose = (SplashClose_t)dlsym(hSplashLib,
        "SplashClose");
    if (splashClose) {
        splashClose();
    }
    dlclose(hSplashLib);
}
