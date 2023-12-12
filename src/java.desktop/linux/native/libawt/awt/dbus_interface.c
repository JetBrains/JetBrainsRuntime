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


#include "dbus_interface.h"

#include <dlfcn.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdbool.h>

#include "jvm_md.h"

#define DBUS_LIB JNI_LIB_NAME("dbus-1")
#define DBUS_LIB_VERSIONED VERSIONED_JNI_LIB_NAME("dbus-1", "3")

static bool isCurrentVersionSupported(DBusApi* dBusApi) {
    int major = 0, minor = 0, micro = 0;
    dBusApi->dbus_get_version(&major, &minor, &micro);
    return major == 1;
}

static bool DBusApi_init(DBusApi* dBusApi, void *libhandle) {
    dBusApi->dbus_get_version = dlsym(libhandle, "dbus_get_version");
    if (dBusApi->dbus_get_version == NULL || !isCurrentVersionSupported(dBusApi)) {
        return false;
    }

    dBusApi->dbus_error_init = dlsym(libhandle, "dbus_error_init");
    dBusApi->dbus_bus_get = dlsym(libhandle, "dbus_bus_get");
    dBusApi->dbus_error_is_set = dlsym(libhandle, "dbus_error_is_set");
    dBusApi->dbus_bus_request_name = dlsym(libhandle, "dbus_bus_request_name");
    dBusApi->dbus_connection_flush = dlsym(libhandle, "dbus_connection_flush");
    dBusApi->dbus_message_new_method_call = dlsym(libhandle, "dbus_message_new_method_call");
    dBusApi->dbus_message_set_destination = dlsym(libhandle, "dbus_message_set_destination");
    dBusApi->dbus_message_iter_init_append = dlsym(libhandle, "dbus_message_iter_init_append");
    dBusApi->dbus_message_iter_append_basic = dlsym(libhandle, "dbus_message_iter_append_basic");
    dBusApi->dbus_connection_send_with_reply_and_block = dlsym(libhandle, "dbus_connection_send_with_reply_and_block");
    dBusApi->dbus_message_iter_init = dlsym(libhandle, "dbus_message_iter_init");
    dBusApi->dbus_message_iter_get_arg_type = dlsym(libhandle, "dbus_message_iter_get_arg_type");
    dBusApi->dbus_message_iter_get_basic = dlsym(libhandle, "dbus_message_iter_get_basic");
    dBusApi->dbus_message_iter_recurse = dlsym(libhandle, "dbus_message_iter_recurse");
    dBusApi->dbus_message_iter_next = dlsym(libhandle, "dbus_message_iter_next");
    dBusApi->dbus_message_unref = dlsym(libhandle, "dbus_message_unref");
    dBusApi->dbus_error_free = dlsym(libhandle, "dbus_error_free");

    return dBusApi->dbus_error_init != NULL && dBusApi->dbus_bus_get != NULL && dBusApi->dbus_error_is_set != NULL &&
            dBusApi->dbus_bus_request_name != NULL && dBusApi->dbus_connection_flush != NULL &&
            dBusApi->dbus_message_set_destination != NULL && dBusApi->dbus_message_iter_init_append != NULL &&
            dBusApi->dbus_message_iter_append_basic != NULL && dBusApi->dbus_connection_send_with_reply_and_block != NULL &&
            dBusApi->dbus_message_iter_init != NULL && dBusApi->dbus_message_iter_get_arg_type != NULL &&
            dBusApi->dbus_message_iter_get_basic != NULL && dBusApi->dbus_message_iter_recurse != NULL &&
            dBusApi->dbus_message_iter_next != NULL && dBusApi->dbus_message_unref != NULL &&
            dBusApi->dbus_message_new_method_call != NULL && dBusApi->dbus_error_free != NULL;
}

DBusApi* DBusApi_setupDBus(void *libhandle) {
    DBusApi *dBusApi = (DBusApi*)malloc(sizeof(DBusApi));
    if (dBusApi == NULL || !DBusApi_init(dBusApi, libhandle)) {
        free(dBusApi);
        dBusApi = NULL;
    }

    return dBusApi;
}

DBusApi* DBusApi_setupDBusDefault() {
    void *dbus_libhandle = dlopen(DBUS_LIB, RTLD_LAZY | RTLD_LOCAL);
    if (dbus_libhandle == NULL) {
        dbus_libhandle = dlopen(DBUS_LIB_VERSIONED, RTLD_LAZY | RTLD_LOCAL);
        if (dbus_libhandle == NULL) {
            return NULL;
        }
    }

    return DBusApi_setupDBus(dbus_libhandle);
}