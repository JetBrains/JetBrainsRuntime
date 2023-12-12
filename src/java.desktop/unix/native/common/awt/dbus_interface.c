#include "dbus_interface.h"

#include <dlfcn.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdbool.h>

#define DBUS_LIB_NAME "libdbus-1.so"

static bool DBusApi_init(DBusApi* dBusApi, void *libhandle) {
    dBusApi->dbus_error_init = dlsym(libhandle, "dbus_error_init");
    dBusApi->dbus_bus_get = dlsym(libhandle, "dbus_bus_get");
    dBusApi->dbus_error_is_set = dlsym(libhandle, "dbus_error_is_set");
    dBusApi->dbus_bus_request_name = dlsym(libhandle, "dbus_bus_request_name");
    dBusApi->dbus_bus_add_match = dlsym(libhandle, "dbus_bus_add_match");
    dBusApi->dbus_connection_add_filter = dlsym(libhandle, "dbus_connection_add_filter");
    dBusApi->dbus_connection_flush = dlsym(libhandle, "dbus_connection_flush");
    dBusApi->dbus_connection_read_write = dlsym(libhandle, "dbus_connection_read_write");
    dBusApi->dbus_connection_dispatch = dlsym(libhandle, "dbus_connection_dispatch");
    dBusApi->dbus_message_is_signal = dlsym(libhandle, "dbus_message_is_signal");
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
    dBusApi->dbus_message_set_auto_start = dlsym(libhandle, "dbus_message_set_auto_start");

    return dBusApi->dbus_error_init != NULL && dBusApi->dbus_bus_get != NULL && dBusApi->dbus_error_is_set != NULL &&
            dBusApi->dbus_bus_request_name != NULL && dBusApi->dbus_bus_add_match != NULL &&
            dBusApi->dbus_connection_add_filter != NULL && dBusApi->dbus_connection_flush != NULL &&
            dBusApi->dbus_connection_read_write != NULL && dBusApi->dbus_connection_dispatch != NULL &&
            dBusApi->dbus_message_is_signal != NULL && dBusApi->dbus_message_new_method_call != NULL &&
            dBusApi->dbus_message_set_destination != NULL && dBusApi->dbus_message_iter_init_append != NULL &&
            dBusApi->dbus_message_iter_append_basic != NULL && dBusApi->dbus_connection_send_with_reply_and_block != NULL &&
            dBusApi->dbus_message_iter_init != NULL && dBusApi->dbus_message_iter_get_arg_type != NULL &&
            dBusApi->dbus_message_iter_get_basic != NULL && dBusApi->dbus_message_iter_recurse != NULL &&
            dBusApi->dbus_message_iter_next != NULL && dBusApi->dbus_message_unref != NULL &&
            dBusApi->dbus_message_set_auto_start != NULL;
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
    void *dbus_libhandle = dlopen(DBUS_LIB_NAME, RTLD_LAZY | RTLD_LOCAL);
    if (dbus_libhandle == NULL) {
        return NULL;
    }

    return DBusApi_setupDBus(dbus_libhandle);
}