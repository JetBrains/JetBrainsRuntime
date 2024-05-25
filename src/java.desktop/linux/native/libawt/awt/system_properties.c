/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
 *
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

#include "system_properties.h"

#ifdef DBUS_FOUND

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define UNKNOWN_RESULT -1
#define SETTING_INTERFACE "org.freedesktop.portal.Settings"
#define SETTING_INTERFACE_METHOD "Read"
#define DESKTOP_DESTINATION "org.freedesktop.portal.Desktop"
#define DESKTOP_PATH "/org/freedesktop/portal/desktop"
#define REPLY_TIMEOUT 150

static DBusConnection *connection = NULL;
static JNIEnv *env = NULL;
static DBusApi *dBus = NULL;
static DBusMessage *msg_freedesktop_appearance = NULL;
static DBusMessage *msg_gnome_desktop = NULL;
static bool initialized = false;
static bool logEnabled = true;
extern JavaVM *jvm;

static void printError(const char* fmt, ...) {
    if (!logEnabled) {
        return;
    }

    env = (JNIEnv *)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    char* buf = (char*)malloc(1024);

    if (env && buf) {
        va_list vargs;
        va_start(vargs, fmt);
        vsnprintf(buf, 1024, fmt, vargs);
        jstring text = JNU_NewStringPlatform(env, buf);
        free(buf);
        va_end(vargs);

        jboolean ignoreException;
        JNU_CallStaticMethodByName(env, &ignoreException, "sun/awt/UNIXToolkit", "printError",
                                   "(Ljava/lang/String;)V", text);
    }
}

static bool dbusCheckError(DBusError *err, const char *msg) {
    bool is_error_set = dBus->dbus_error_is_set(err);
    if (is_error_set) {
        printError("DBus error: %s. %s\n", msg, err->message);
        dBus->dbus_error_free(err);
    }
    return is_error_set;
}

// current implementation of object decomposition supports only
// primitive types (including a recursive type wrapper)
static bool decomposeDBusReply(void *val, DBusMessageIter *iter, int demand_type) {
    int cur_type = dBus->dbus_message_iter_get_arg_type(iter);
    switch (cur_type)
    {
        case DBUS_TYPE_INT16:
        case DBUS_TYPE_UINT16:
        case DBUS_TYPE_INT32:
        case DBUS_TYPE_UINT32:
        case DBUS_TYPE_INT64:
        case DBUS_TYPE_UINT64:
        case DBUS_TYPE_STRING:
        {
            if (cur_type != demand_type) {
                return false;
            }
            dBus->dbus_message_iter_get_basic(iter, val);
            return true;
        }
        case DBUS_TYPE_VARIANT:
        {
            DBusMessageIter unwrap_iter;
            dBus->dbus_message_iter_recurse(iter, &unwrap_iter);
            bool res = decomposeDBusReply(val, &unwrap_iter, demand_type);
            // current implementation doesn't support types with multiple fields
            if (dBus->dbus_message_iter_next(iter)) {
                return false;
            }
            return res;
        }
        case DBUS_TYPE_INVALID:
        default:
            return false;
    }
}

static DBusMessage *createDBusMessage(const char *messages[], int message_count) {
    DBusMessage *msg = NULL;
    DBusMessageIter msg_iter;

    if ((msg = dBus->dbus_message_new_method_call(NULL, DESKTOP_PATH, SETTING_INTERFACE, SETTING_INTERFACE_METHOD)) == NULL) {
        printError("DBus error: cannot allocate message\n");
        goto cleanup;
    }

    if (!dBus->dbus_message_set_destination(msg, DESKTOP_DESTINATION)) {
        printError("DBus error: cannot set destination\n");
        goto cleanup;
    }

    dBus->dbus_message_iter_init_append(msg, &msg_iter);

    for (int i = 0; i < message_count; i++) {
        if (!dBus->dbus_message_iter_append_basic(&msg_iter, DBUS_TYPE_STRING, &messages[i])) {
            printError("DBus error: cannot append to message\n");
            goto cleanup;
        }
    }

    return msg;
cleanup:
    if (msg) {
        dBus->dbus_message_unref(msg);
    }
    return NULL;
}

static bool sendDBusMessageWithReply(DBusMessage *msg, void *val, int demand_type) {
    DBusError error;
    DBusMessage *msg_reply = NULL;
    DBusMessageIter msg_iter;
    bool res = false;

    dBus->dbus_error_init(&error);
    if ((msg_reply = dBus->dbus_connection_send_with_reply_and_block(connection, msg, REPLY_TIMEOUT, &error)) == NULL) {
        printError("DBus error: cannot get msg_reply or sent message. %s\n", dBus->dbus_error_is_set(&error) ? error.message : "");
        goto cleanup;
    }

    if (!dBus->dbus_message_iter_init(msg_reply, &msg_iter)) {
        printError("DBus error: cannot process message\n");
        goto cleanup;
    }

    res = decomposeDBusReply(val, &msg_iter, demand_type);
cleanup:
    if (msg_reply) {
        dBus->dbus_message_unref(msg_reply);
    }
    return res;
}

JNIEXPORT jint JNICALL Java_sun_awt_UNIXToolkit_isSystemDarkColorScheme() {
    static int use_freedesktop_appearance = -1;
    if (!initialized) {
        return UNKNOWN_RESULT;
    }

    if (use_freedesktop_appearance == -1) {
        unsigned int res = 0;
        logEnabled = false;
        use_freedesktop_appearance =
                sendDBusMessageWithReply(msg_freedesktop_appearance, &res, DBUS_TYPE_UINT32);
        logEnabled = true;
    }

    if (use_freedesktop_appearance) {
        unsigned int res = 0;
        if (!sendDBusMessageWithReply(msg_freedesktop_appearance, &res, DBUS_TYPE_UINT32)) {
            return UNKNOWN_RESULT;
        }
        /* From org.freedesktop.portal color-scheme specs:
         * 0: No preference, 1: Prefer dark appearance, 2: Prefer light appearance
         */
        return res == 1;
    } else {
        char *res = NULL;
        if (!sendDBusMessageWithReply(msg_gnome_desktop, &res, DBUS_TYPE_STRING)) {
            return UNKNOWN_RESULT;
        }
        return (res != NULL) ? strstr(res, "dark") != NULL : UNKNOWN_RESULT;
    }
}

jboolean SystemProperties_setup(DBusApi *dBus_, JNIEnv *env_) {
    env = env_;
    dBus = dBus_;
    DBusError err;
    int ret;

    dBus->dbus_error_init(&err);
    if ((connection = dBus->dbus_bus_get(DBUS_BUS_SESSION, &err)) == NULL) {
        printError("DBus error: connection is Null\n");
        return JNI_FALSE;
    }
    if (dbusCheckError(&err, "connection error")) {
        return JNI_FALSE;
    }

    ret = dBus->dbus_bus_request_name(connection, "dbus.JBR.server", DBUS_NAME_FLAG_REPLACE_EXISTING , &err);
    if (ret != DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER && ret != DBUS_REQUEST_NAME_REPLY_IN_QUEUE) {
        printError("DBus error: Failed to acquire service name \n");
        return JNI_FALSE;
    }
    if (dbusCheckError(&err, "error request 'dbus.JBR.server' name on the bus")) {
        return JNI_FALSE;
    }

    dBus->dbus_connection_flush(connection);

    const char *freedesktop_appearance_messages[] = {"org.freedesktop.appearance", "color-scheme"};
    const char *gnome_desktop_messages[] = {"org.gnome.desktop.interface", "gtk-theme"};
    msg_freedesktop_appearance = createDBusMessage(freedesktop_appearance_messages, 2);
    msg_gnome_desktop = createDBusMessage(gnome_desktop_messages, 2);
    if (msg_freedesktop_appearance == NULL || msg_gnome_desktop == NULL) {
        return JNI_FALSE;
    }

    initialized = true;

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_UNIXToolkit_dbusInit() {
    DBusApi *dBus = DBusApi_setupDBusDefault();
    if (dBus) {
        return SystemProperties_setup(dBus, env);
    }
    return JNI_FALSE;
}

#else

JNIEXPORT jint JNICALL Java_sun_awt_UNIXToolkit_isSystemDarkColorScheme() {
    return -1;
}

JNIEXPORT jboolean JNICALL Java_sun_awt_UNIXToolkit_dbusInit() {
    return JNI_FALSE;
}

#endif