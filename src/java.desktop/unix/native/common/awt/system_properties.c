/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>

#define SIGNAL_NAME "SettingChanged"
#define THEMEISDARK_DESKTOPPROPERTY "awt.os.theme.isDark"

static const char *interface = "org.freedesktop.portal.Settings";
static const char *destination = "org.freedesktop.portal.Desktop";
static const char *path = "/org/freedesktop/portal/desktop";

#define DBUS_MESSAGE_MAX 1024
#define REPLY_TIMEOUT 100

static DBusConnection *conn = NULL;
static JNIEnv *env = NULL;
static DBusApi *dBus = NULL;
static bool initialized = false;

void updateAllProperties();

static DBusHandlerResult messageFilter(DBusConnection *conn, DBusMessage *msg, void *data) {
    if (dBus->dbus_message_is_signal(msg, interface, SIGNAL_NAME)) {
        updateAllProperties();
        return DBUS_HANDLER_RESULT_HANDLED;
    }
    return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
}

bool SystemProperties_setup(DBusApi *dBus_, JNIEnv *env_) {
    env = env_;
    dBus = dBus_;
    DBusError err;

    // initialise the error
    dBus->dbus_error_init(&err);

    // connect to the bus and check for errors
    conn = dBus->dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (NULL == conn) {
        fprintf(stderr, "Connection Null\n");
        return false;
    }
    if (dBus->dbus_error_is_set(&err)) {
        fprintf(stderr, "Connection Error (%s)\n", err.message);
        dBus->dbus_error_free(&err);
        return false;
    }

    // request our name on the bus and check for errors
    int ret = dBus->dbus_bus_request_name(conn, "test.selector.server",
                                DBUS_NAME_FLAG_REPLACE_EXISTING , &err);
    if (dBus->dbus_error_is_set(&err)) {
        fprintf(stderr, "Name Error (%s)\n", err.message);
        dBus->dbus_error_free(&err);
        return false;
    }
    if (DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER != ret) {
        fprintf(stderr, "Not Primary Owner (%d)\n", ret);
        return false;
    }

    char *match_rule = (char *)(malloc(DBUS_MESSAGE_MAX));
    sprintf(match_rule, "type='signal',sender='%s',path='%s',interface='%s'", destination, path, interface);
    dBus->dbus_bus_add_match(conn, match_rule, &err);
    free(match_rule);

    dBus->dbus_connection_add_filter(conn, &messageFilter, dBus, NULL);
    if (dBus->dbus_error_is_set(&err)) {
        fprintf(stderr, "connection_add_filter (%s)\n", err.message);
        return false;
    }

    dBus->dbus_connection_flush(conn);
    if (dBus->dbus_error_is_set(&err)) {
        fprintf(stderr, "Match Error (%s)\n", err.message);
        return false;
    }

    initialized = true;
    updateAllProperties();

    return true;
}

void SystemProperties_pullEvent() {
    if (initialized) {
        if (!dBus->dbus_connection_read_write(conn, 0)) {
            return;
        }

        while (dBus->dbus_connection_dispatch(conn) == DBUS_DISPATCH_DATA_REMAINS) {
            usleep(10);
        }

    }
}

static bool get_basic_iter(void *val, DBusMessageIter *iter, int demand_type) {
    int type = dBus->dbus_message_iter_get_arg_type(iter);
    switch (type)
    {
        case DBUS_TYPE_INT16:
        case DBUS_TYPE_UINT16:
        case DBUS_TYPE_INT32:
        case DBUS_TYPE_UINT32:
        case DBUS_TYPE_INT64:
        case DBUS_TYPE_UINT64:
        case DBUS_TYPE_DOUBLE:
        case DBUS_TYPE_BYTE:
        case DBUS_TYPE_BOOLEAN:
        case DBUS_TYPE_STRING:
        {
            if (type != demand_type) {
                return false;
            }
            dBus->dbus_message_iter_get_basic(iter, val);
            return true;
        }
        case DBUS_TYPE_VARIANT: {
            DBusMessageIter *subiter = malloc(DBUS_MESSAGE_MAX);
            dBus->dbus_message_iter_recurse(iter, subiter);
            bool res = get_basic_iter(val, subiter, demand_type);
            if (subiter) {
                free(subiter);
            }
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

static bool sendDBusMessageWithReply(const char *name_function, const char **messages,
                                       int *messages_type, int message_count, void *val, int demand_type) {
    DBusError error;
    DBusMessage *message = NULL;
    DBusMessageIter *iter = malloc(DBUS_MESSAGE_MAX);
    DBusMessage *reply = NULL;
    bool res = false;

    if (!initialized) {
        return false;
    }

    dBus->dbus_error_init(&error);
    message = dBus->dbus_message_new_method_call(NULL, path, interface, name_function);
    dBus->dbus_message_set_auto_start(message, true);
    if (message == NULL) {
        fprintf(stderr, "Couldn't allocate D-Bus message\n");
        goto cleanup;
    }

    if (!dBus->dbus_message_set_destination(message, destination)) {
        fprintf(stderr, "Not enough memory\n");
        goto cleanup;
    }

    dBus->dbus_message_iter_init_append(message, iter);
    for (int i = 0; i < message_count; i++) {
        dBus->dbus_message_iter_append_basic(iter, messages_type[i], &messages[i]);
    }

    reply = dBus->dbus_connection_send_with_reply_and_block(conn, message, REPLY_TIMEOUT, &error);
    if (dBus->dbus_error_is_set(&error)) {
        fprintf(stderr, "Error %s: %s\n", error.name, error.message);
        goto cleanup;
    }

    dBus->dbus_message_iter_init (reply, iter);
    res = get_basic_iter(val, iter, demand_type);

cleanup:
    if (iter) {
        free(iter);
    }
    if (reply) {
        dBus->dbus_message_unref(reply);
    }
    if (message) {
        dBus->dbus_message_unref(message);
    }
    return res;
}

static void setDesktopProperty(jstring property, jstring value) {
    jclass TKClass = (*env)->FindClass(env, "java/awt/Toolkit");

    jmethodID setDesktopProperty = (*env)->GetMethodID(env, TKClass, "setDesktopProperty", "(Ljava/lang/String;Ljava/lang/Object;)V");
    jmethodID getDefaultToolkit = (*env)->GetStaticMethodID(env, TKClass, "getDefaultToolkit", "()Ljava/awt/Toolkit;");

    jobject toolkitObject = (*env)->CallStaticObjectMethod(env, TKClass, getDefaultToolkit);

    (*env)->CallVoidMethod(env, toolkitObject, setDesktopProperty, property, value);
}

static bool isDarkColorScheme() {
    const char *name_function = "Read";
    char *messages[] = { "org.freedesktop.appearance", "color-scheme"};
    int messages_type[] = {DBUS_TYPE_STRING, DBUS_TYPE_STRING};
    int res = 0;
    bool status = sendDBusMessageWithReply(name_function, (const char **)messages, messages_type, 2, &res, DBUS_TYPE_UINT32);
    return status && res;
}

static updateAllProperties() {
    setDesktopProperty(JNU_NewStringPlatform(env, THEMEISDARK_DESKTOPPROPERTY),
                       JNU_NewStringPlatform(env, isDarkColorScheme() ? "1" : "0"));
}