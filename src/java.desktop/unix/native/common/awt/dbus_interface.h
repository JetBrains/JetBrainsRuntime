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

#ifndef JETBRAINSRUNTIME_DBUS_INTERFACE_H
#define JETBRAINSRUNTIME_DBUS_INTERFACE_H

#define DBUS_NAME_FLAG_ALLOW_REPLACEMENT 0x1
#define DBUS_NAME_FLAG_REPLACE_EXISTING  0x2
#define DBUS_NAME_FLAG_DO_NOT_QUEUE      0x4

#define DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER  1
#define DBUS_REQUEST_NAME_REPLY_IN_QUEUE       2
#define DBUS_REQUEST_NAME_REPLY_EXISTS         3
#define DBUS_REQUEST_NAME_REPLY_ALREADY_OWNER  4

typedef enum
{
    DBUS_BUS_SESSION,    /**< The login session bus */
    DBUS_BUS_SYSTEM,     /**< The systemwide bus */
    DBUS_BUS_STARTER     /**< The bus that started us, if any */
} DBusBusType;

typedef enum
{
    DBUS_HANDLER_RESULT_HANDLED,         /**< Message has had its effect - no need to run more handlers. */
    DBUS_HANDLER_RESULT_NOT_YET_HANDLED, /**< Message has not had any effect - see if other handlers want it. */
    DBUS_HANDLER_RESULT_NEED_MEMORY      /**< Need more memory in order to return #DBUS_HANDLER_RESULT_HANDLED or #DBUS_HANDLER_RESULT_NOT_YET_HANDLED. Please try again later with more memory. */
} DBusHandlerResult;

typedef enum
{
    DBUS_DISPATCH_DATA_REMAINS,  /**< There is more data to potentially convert to messages. */
    DBUS_DISPATCH_COMPLETE,      /**< All currently available data has been processed. */
    DBUS_DISPATCH_NEED_MEMORY    /**< More memory is needed to continue. */
} DBusDispatchStatus;

typedef struct DBusConnection DBusConnection;
typedef struct DBusMessage DBusMessage;

typedef DBusHandlerResult (*DBusHandleMessageFunction) (DBusConnection     *connection,
                                                        DBusMessage        *message,
                                                        void               *user_data);

typedef void (*DBusFreeFunction) (void *memory);

typedef struct DBusError DBusError;

struct DBusError
{
    const char *name;
    const char *message;

    unsigned int dummy1 : 1;
    unsigned int dummy2 : 1;
    unsigned int dummy3 : 1;
    unsigned int dummy4 : 1;
    unsigned int dummy5 : 1;

    void *padding1;
};

typedef unsigned int dbus_uint32_t;
typedef dbus_uint32_t  dbus_bool_t;

typedef struct DBusMessageIter DBusMessageIter;

struct DBusMessageIter
{
    void *dummy1;
    void *dummy2;
    dbus_uint32_t dummy3;
    int dummy4;
    int dummy5;
    int dummy6;
    int dummy7;
    int dummy8;
    int dummy9;
    int dummy10;
    int dummy11;
    int pad1;
    int pad2;
    void *pad3;
};

#define DBUS_TYPE_INT16         ((int) 'n')
#define DBUS_TYPE_UINT16        ((int) 'q')
#define DBUS_TYPE_INT32         ((int) 'i')
#define DBUS_TYPE_UINT32        ((int) 'u')
#define DBUS_TYPE_INT64         ((int) 'x')
#define DBUS_TYPE_UINT64        ((int) 't')
#define DBUS_TYPE_DOUBLE        ((int) 'd')
#define DBUS_TYPE_BYTE          ((int) 'y')
#define DBUS_TYPE_BOOLEAN       ((int) 'b')
#define DBUS_TYPE_STRING        ((int) 's')
#define DBUS_TYPE_VARIANT       ((int) 'v')
#define DBUS_TYPE_INVALID       ((int) '\0')

typedef struct DBusApi {
    void (*dbus_get_version)(int *major_version_p, int *minor_version_p, int *micro_version_p);

    void (*dbus_error_init)(DBusError *error);

    DBusConnection *(*dbus_bus_get)(DBusBusType type, DBusError *error);

    dbus_bool_t (*dbus_error_is_set)(const DBusError *error);

    void (*dbus_error_free)(DBusError *error);

    int (*dbus_bus_request_name)(DBusConnection *connection, const char *name, unsigned int flags, DBusError *error);

    void (*dbus_bus_add_match)(DBusConnection *connection, const char *rule, DBusError *error);

    dbus_bool_t (*dbus_connection_add_filter)(DBusConnection *connection, DBusHandleMessageFunction function,
            void *user_data, DBusFreeFunction free_data_function);

    void (*dbus_connection_flush)(DBusConnection *connection);

    dbus_bool_t (*dbus_connection_read_write)(DBusConnection *connection, int timeout_milliseconds);

    DBusDispatchStatus (*dbus_connection_dispatch)(DBusConnection *connection);

    dbus_bool_t (*dbus_message_is_signal)(DBusMessage *message, const char *iface, const char *signal_name);

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

    void (*dbus_message_set_auto_start)(DBusMessage *message, dbus_bool_t auto_start);
} DBusApi;


DBusApi* DBusApi_setupDBus(void *libhandle);
DBusApi* DBusApi_setupDBusDefault();

#endif //JETBRAINSRUNTIME_DBUS_INTERFACE_H
