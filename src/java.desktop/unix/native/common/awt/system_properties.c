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
#define SETTING_INTERFACE "org.freedesktop.portal.Settings"
#define DESKTOP_DESTINATION "org.freedesktop.portal.Desktop"
#define DESKTOP_PATH "/org/freedesktop/portal/desktop"
#define MATCH_RULE "type='signal',sender='" DESKTOP_DESTINATION "',path='"  DESKTOP_PATH "',interface='"  SETTING_INTERFACE "'"
#define DBUS_MESSAGE_MAX 1024
#define REPLY_TIMEOUT 150

static DBusConnection *connection = NULL;
static JNIEnv *env = NULL;
static DBusApi *dBus = NULL;
static bool initialized = false;

static void updateAllProperties(void);

static DBusHandlerResult messageFilter(DBusConnection *conn, DBusMessage *msg, void *data) {
    if (dBus->dbus_message_is_signal(msg, SETTING_INTERFACE, SIGNAL_NAME)) {
        updateAllProperties();
        return DBUS_HANDLER_RESULT_HANDLED;
    }
    return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
}

static bool dbusCheckError(DBusError *err, const char *msg) {
    bool is_error_set = dBus->dbus_error_is_set(err);
    if (is_error_set) {
        fprintf(stderr, "DBus error: %s. %s\n", msg, err->message);
        dBus->dbus_error_free(err);
    }
    return is_error_set;
}

bool SystemProperties_setup(DBusApi *dBus_, JNIEnv *env_) {
    env = env_;
    dBus = dBus_;
    DBusError err;
    int ret;

    dBus->dbus_error_init(&err);
    if ((connection = dBus->dbus_bus_get(DBUS_BUS_SESSION, &err)) == NULL) {
        fprintf(stderr, "DBus error: connection is Null\n");
        return false;
    }
    if (dbusCheckError(&err, "connection error")) {
        return false;
    }

    if ((ret = dBus->dbus_bus_request_name(connection, "dbus.JBR.server", DBUS_NAME_FLAG_REPLACE_EXISTING , &err))
            != DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER) {
        fprintf(stderr, "DBus error: failed to replace the current primary owner\n");
        return false;
    }
    if (dbusCheckError(&err, "error request 'dbus.JBR.server' name on the bus")) {
        return false;
    }

    dBus->dbus_bus_add_match(connection, MATCH_RULE, &err);
    if (dbusCheckError(&err, "cannot set match rule")) {
        return false;
    }

    dBus->dbus_connection_add_filter(connection, &messageFilter, NULL, NULL);
    if (dbusCheckError(&err, "cannot add filter")) {
        return false;
    }

    dBus->dbus_connection_flush(connection);
    initialized = true;
    updateAllProperties();

    return true;
}

void SystemProperties_pullEvent(void) {
    if (initialized) {
        if (!dBus->dbus_connection_read_write(connection, 0)) {
            return;
        }

        while (dBus->dbus_connection_dispatch(connection) == DBUS_DISPATCH_DATA_REMAINS) {
        }
    }
}

// current implementation of object decomposition supports only
// primitive types (including a recursive type wrapper)
static bool getBasicIter(void *val, DBusMessageIter *iter, int demand_type) {
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
        case DBUS_TYPE_VARIANT:
        {
            DBusMessageIter *sub_iter = malloc(DBUS_MESSAGE_MAX);
            if (sub_iter == NULL) {
                return false;
            }
            dBus->dbus_message_iter_recurse(iter, sub_iter);
            bool res = getBasicIter(val, sub_iter, demand_type);
            free(sub_iter);
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

static bool sendDBusMessageWithReply(const char *name_function, const char **messages,
                                       int *messages_type, int message_count, void *val, int demand_type) {
    DBusError error;
    DBusMessage *message = NULL;
    DBusMessageIter *iter = NULL;
    DBusMessage *reply = NULL;
    bool res = false;

    if (!initialized) {
        return false;
    }

    dBus->dbus_error_init(&error);
    message = dBus->dbus_message_new_method_call(NULL, DESKTOP_PATH, SETTING_INTERFACE, name_function);
    if (message == NULL) {
        fprintf(stderr, "DBus error: cannot allocate message\n");
        goto cleanup;
    }

    dBus->dbus_message_set_auto_start(message, true);
    if (!dBus->dbus_message_set_destination(message, DESKTOP_DESTINATION)) {
        fprintf(stderr, "DBus error: cannot set destination\n");
        goto cleanup;
    }

    iter = malloc(DBUS_MESSAGE_MAX);
    if (iter == NULL) {
        fprintf(stderr, "DBus error: cannot allocate memory\n");
        goto cleanup;
    }

    dBus->dbus_message_iter_init_append(message, iter);
    for (int i = 0; i < message_count; i++) {
        if (!dBus->dbus_message_iter_append_basic(iter, messages_type[i], &messages[i])) {
            fprintf(stderr, "DBus error: cannot append to message\n");
            goto cleanup;
        }
    }

    if ((reply = dBus->dbus_connection_send_with_reply_and_block(connection, message, REPLY_TIMEOUT, &error)) == NULL) {
        fprintf(stderr, "DBus error: cannot get reply to sent message\n");
        goto cleanup;
    }
    if (dBus->dbus_error_is_set(&error)) {
        fprintf(stderr, "DBus error: cannot send message. %s\n", error.message);
        goto cleanup;
    }

    if (!dBus->dbus_message_iter_init (reply, iter)) {
        fprintf(stderr, "DBus error: cannot process message\n");
        goto cleanup;
    }

    res = getBasicIter(val, iter, demand_type);

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
    if (!TKClass) {
        return;
    }

    jmethodID setDesktopProperty = (*env)->GetMethodID(env, TKClass, "setDesktopProperty", "(Ljava/lang/String;Ljava/lang/Object;)V");
    jmethodID getDefaultToolkit = (*env)->GetStaticMethodID(env, TKClass, "getDefaultToolkit", "()Ljava/awt/Toolkit;");
    if (!setDesktopProperty || !getDefaultToolkit) {
        return;
    }

    jobject toolkitObject = (*env)->CallStaticObjectMethod(env, TKClass, getDefaultToolkit);
    JNU_CHECK_EXCEPTION(env);
    if (!toolkitObject) {
        return;
    }

    (*env)->CallVoidMethod(env, toolkitObject, setDesktopProperty, property, value);
    JNU_CHECK_EXCEPTION(env);
}

static void updateProperty(const char *property_name, bool (*getProperty)(char *res)) {
    char str[DBUS_MESSAGE_MAX];
    if ((getProperty)(str)) {
        jstring j_property_name = JNU_NewStringPlatform(env, property_name);
        jstring j_property_value = JNU_NewStringPlatform(env, str);
        if (j_property_name && j_property_value) {
            setDesktopProperty(j_property_name, j_property_value);
        }
    }
}

static bool isDarkColorScheme(char *res) {
    const char *name_function = "Read";
    char *messages[] = { "org.freedesktop.appearance", "color-scheme"};
    int messages_type[] = {DBUS_TYPE_STRING, DBUS_TYPE_STRING};
    unsigned int int_res = 0;
    if (!sendDBusMessageWithReply(name_function, (const char **)messages, messages_type, 2, &int_res, DBUS_TYPE_UINT32)) {
        return false;
    }
    if (sprintf(res, "%u", int_res) < 0) {
        return false;
    }
    return true;
}

static void updateAllProperties(void) {
    updateProperty("awt.os.theme.isDark", isDarkColorScheme);
}