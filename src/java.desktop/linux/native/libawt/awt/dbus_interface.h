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

#ifndef JETBRAINSRUNTIME_DBUS_INTERFACE_H
#define JETBRAINSRUNTIME_DBUS_INTERFACE_H

#include <dbus/dbus.h>

typedef struct DBusApi {
    void (*dbus_get_version)(int *major_version_p, int *minor_version_p, int *micro_version_p);

    void (*dbus_error_init)(DBusError *error);

    DBusConnection *(*dbus_bus_get)(DBusBusType type, DBusError *error);

    dbus_bool_t (*dbus_error_is_set)(const DBusError *error);

    void (*dbus_error_free)(DBusError *error);

    int (*dbus_bus_request_name)(DBusConnection *connection, const char *name, unsigned int flags, DBusError *error);

    void (*dbus_connection_flush)(DBusConnection *connection);

    DBusMessage* (*dbus_message_new_method_call)(const char *bus_name, const char *path,
            const char *iface, const char *method);

    dbus_bool_t (*dbus_message_set_destination)(DBusMessage *message, const char *destination);

    void (*dbus_message_iter_init_append)(DBusMessage *message, DBusMessageIter *iter);

    dbus_bool_t (*dbus_message_iter_append_basic)(DBusMessageIter *iter, int type, const void *value);

    DBusMessage *(*dbus_connection_send_with_reply_and_block)(DBusConnection *connection, DBusMessage *message,
                                                           int timeout_milliseconds, DBusError *error);

    dbus_bool_t (*dbus_message_iter_init)(DBusMessage *message, DBusMessageIter *iter);

    int (*dbus_message_iter_get_arg_type)(DBusMessageIter *iter);

    void (*dbus_message_iter_get_basic)(DBusMessageIter *iter, void *value);

    void (*dbus_message_iter_recurse)(DBusMessageIter *iter, DBusMessageIter *sub);

    dbus_bool_t (*dbus_message_iter_next)(DBusMessageIter *iter);

    void (*dbus_message_unref)(DBusMessage *message);
} DBusApi;

DBusApi* DBusApi_setupDBus(void *libhandle);
DBusApi* DBusApi_setupDBusDefault();

#endif //JETBRAINSRUNTIME_DBUS_INTERFACE_H
