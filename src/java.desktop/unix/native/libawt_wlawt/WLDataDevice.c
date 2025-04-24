/*
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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

#include <assert.h>
#include <stdbool.h>
#include <stdlib.h>

#include "JNIUtilities.h"
#include "WLToolkit.h"
#include "sun_awt_wl_WLDataDevice.h"
#include "wayland-client-protocol.h"

static struct wl_event_queue *dataTransferQueue;
static struct wl_data_device *wlDataDevice;
static struct zwp_primary_selection_device_v1 *zwpPrimarySelectionDevice;

static jclass WLDataDeviceClass;
static jclass WLDataSourceClass;
static jclass WLDataOfferClass;

static jmethodID WLDataDevice_handleDnDEnterMID;
static jmethodID WLDataDevice_handleDnDLeaveMID;
static jmethodID WLDataDevice_handleDnDMotionMID;
static jmethodID WLDataDevice_handleDnDDropMID;
static jmethodID WLDataDevice_handleSelectionMID;
static jmethodID WLDataSource_handleTargetAcceptsMimeMID;
static jmethodID WLDataSource_handleSendMID;
static jmethodID WLDataSource_handleCancelledMID;
static jmethodID WLDataSource_handleDnDDropPerformedMID;
static jmethodID WLDataSource_handleDnDFinishedMID;
static jmethodID WLDataSource_handleDnDActionMID;
static jmethodID WLDataOffer_constructorMID;
static jmethodID WLDataOffer_handleOfferMimeMID;
static jmethodID WLDataOffer_handleSourceActionsMID;
static jmethodID WLDataOffer_handleActionMID;

static jobject WLDataDeviceObject;

static bool initJavaRefs(JNIEnv *env) {
    GET_CLASS_RETURN(WLDataDeviceClass, "sun/awt/wl/WLDataDevice", false);
    GET_CLASS_RETURN(WLDataSourceClass, "sun/awt/wl/WLDataSource", false);
    GET_CLASS_RETURN(WLDataOfferClass, "sun/awt/wl/WLDataOffer", false);

    GET_METHOD_RETURN(WLDataDevice_handleDnDEnterMID, WLDataDeviceClass, "handleDnDEnter",
                      "(Lsun/awt/wl/WLDataOffer;JJDD)V", false);
    GET_METHOD_RETURN(WLDataDevice_handleDnDLeaveMID, WLDataDeviceClass, "handleDnDLeave", "()V", false);
    GET_METHOD_RETURN(WLDataDevice_handleDnDMotionMID, WLDataDeviceClass, "handleDnDMotion", "(JDD)V", false);
    GET_METHOD_RETURN(WLDataDevice_handleDnDDropMID, WLDataDeviceClass, "handleDnDDrop", "()V", false);
    GET_METHOD_RETURN(WLDataDevice_handleSelectionMID, WLDataDeviceClass, "handleSelection",
                      "(Lsun/awt/wl/WLDataOffer;Z)V", false);
    GET_METHOD_RETURN(WLDataSource_handleTargetAcceptsMimeMID, WLDataSourceClass, "handleTargetAcceptsMime",
                      "(Ljava/lang/String;)V", false);
    GET_METHOD_RETURN(WLDataSource_handleSendMID, WLDataSourceClass, "handleSend", "(Ljava/lang/String;I)V", false);
    GET_METHOD_RETURN(WLDataSource_handleCancelledMID, WLDataSourceClass, "handleCancelled", "()V", false);
    GET_METHOD_RETURN(WLDataSource_handleDnDDropPerformedMID, WLDataSourceClass, "handleDnDDropPerformed", "()V",
                      false);
    GET_METHOD_RETURN(WLDataSource_handleDnDFinishedMID, WLDataSourceClass, "handleDnDFinished", "()V", false);
    GET_METHOD_RETURN(WLDataSource_handleDnDActionMID, WLDataSourceClass, "handleDnDAction", "(I)V", false);
    GET_METHOD_RETURN(WLDataOffer_constructorMID, WLDataOfferClass, "<init>", "(J)V", false);
    GET_METHOD_RETURN(WLDataOffer_handleOfferMimeMID, WLDataOfferClass, "handleOfferMime", "(Ljava/lang/String;)V",
                      false);
    GET_METHOD_RETURN(WLDataOffer_handleSourceActionsMID, WLDataOfferClass, "handleSourceActions", "(I)V", false);
    GET_METHOD_RETURN(WLDataOffer_handleActionMID, WLDataOfferClass, "handleAction", "(I)V", false);

    return true;
}

static void wl_data_source_handle_target(void *user, struct wl_data_source *source, const char *mime);
static void wl_data_source_handle_send(void *user, struct wl_data_source *source, const char *mime, int32_t fd);
static void wl_data_source_handle_cancelled(void *user, struct wl_data_source *source);
static void wl_data_source_handle_dnd_drop_performed(void *user, struct wl_data_source *source);
static void wl_data_source_handle_dnd_finished(void *user, struct wl_data_source *source);
static void wl_data_source_handle_action(void *user, struct wl_data_source *source, uint32_t action);

static const struct wl_data_source_listener wl_data_source_listener = {
    .target = wl_data_source_handle_target,
    .send = wl_data_source_handle_send,
    .cancelled = wl_data_source_handle_cancelled,
    .dnd_drop_performed = wl_data_source_handle_dnd_drop_performed,
    .dnd_finished = wl_data_source_handle_dnd_finished,
    .action = wl_data_source_handle_action,
};

static void zwp_primary_selection_source_handle_send(void *user,
                                                     struct zwp_primary_selection_source_v1 *source,
                                                     const char *mime,
                                                     int32_t fd);
static void zwp_primary_selection_source_handle_cancelled(void *user, struct zwp_primary_selection_source_v1 *source);

static const struct zwp_primary_selection_source_v1_listener zwp_primary_selection_source_v1_listener = {
    .send = zwp_primary_selection_source_handle_send,
    .cancelled = zwp_primary_selection_source_handle_cancelled,
};

static void wl_data_offer_handle_offer(void *user, struct wl_data_offer *offer, const char *mime);
static void wl_data_offer_handle_source_actions(void *user, struct wl_data_offer *offer, uint32_t source_actions);
static void wl_data_offer_handle_action(void *user, struct wl_data_offer *offer, uint32_t action);

static const struct wl_data_offer_listener wlDataOfferListener = {
    .offer = wl_data_offer_handle_offer,
    .source_actions = wl_data_offer_handle_source_actions,
    .action = wl_data_offer_handle_action,
};

static void
zwp_primary_selection_offer_handle_offer(void *user, struct zwp_primary_selection_offer_v1 *offer, const char *mime);

static const struct zwp_primary_selection_offer_v1_listener zwpPrimarySelectionOfferListener = {
    .offer = zwp_primary_selection_offer_handle_offer,
};

static void
wl_data_device_handle_data_offer(void *user, struct wl_data_device *wl_data_device, struct wl_data_offer *id);
static void wl_data_device_handle_enter(void *user,
                                        struct wl_data_device *wl_data_device,
                                        uint32_t serial,
                                        struct wl_surface *surface,
                                        wl_fixed_t x,
                                        wl_fixed_t y,
                                        struct wl_data_offer *id);
static void wl_data_device_handle_leave(void *user, struct wl_data_device *wl_data_device);
static void wl_data_device_handle_motion(
    void *user, struct wl_data_device *wl_data_device, uint32_t time, wl_fixed_t x, wl_fixed_t y);
static void wl_data_device_handle_drop(void *user, struct wl_data_device *wl_data_device);
static void
wl_data_device_handle_selection(void *user, struct wl_data_device *wl_data_device, struct wl_data_offer *id);

static const struct wl_data_device_listener wlDataDeviceListener = {
    .data_offer = wl_data_device_handle_data_offer,
    .enter = wl_data_device_handle_enter,
    .leave = wl_data_device_handle_leave,
    .motion = wl_data_device_handle_motion,
    .drop = wl_data_device_handle_drop,
    .selection = wl_data_device_handle_selection,
};

static void zwp_primary_selection_device_handle_data_offer(void *user,
                                                           struct zwp_primary_selection_device_v1 *device,
                                                           struct zwp_primary_selection_offer_v1 *id);
static void zwp_primary_selection_device_handle_selection(void *user,
                                                          struct zwp_primary_selection_device_v1 *device,
                                                          struct zwp_primary_selection_offer_v1 *id);

static const struct zwp_primary_selection_device_v1_listener zwpPrimarySelectionDeviceListener = {
    .data_offer = zwp_primary_selection_device_handle_data_offer,
    .selection = zwp_primary_selection_device_handle_selection,
};

enum DataTransferProtocol {
    DATA_TRANSFER_PROTOCOL_INVALID = 0,

    DATA_TRANSFER_PROTOCOL_WAYLAND = 1,
    DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION = 2,
};

struct DataSource {
    enum DataTransferProtocol protocol;
    jobject javaObject;

    union {
        void *anyPtr;

        struct wl_data_source *wlDataSource;
        struct zwp_primary_selection_source_v1 *zwpPrimarySelectionSource;
    };
};

static struct DataSource *DataSource_create(enum DataTransferProtocol protocol);
static void DataSource_destroy(struct DataSource *source);
static void DataSource_offer(const struct DataSource *source, const char *mime);
static void DataSource_setDnDActions(const struct DataSource *source, uint32_t actions);
static void DataSource_setSelection(const struct DataSource *source, uint32_t serial);

struct DataOffer {
    enum DataTransferProtocol protocol;
    jobject javaObject;

    union {
        void *anyPtr;

        struct wl_data_offer *wlDataOffer;
        struct zwp_primary_selection_offer_v1 *zwpPrimarySelectionOffer;
    };
};

static struct DataOffer *DataOffer_create(enum DataTransferProtocol protocol, void *waylandObject);
static void DataOffer_createJavaObject(JNIEnv *env, struct DataOffer *offer);
static void DataOffer_destroy(struct DataOffer *offer);
static void DataOffer_receive(struct DataOffer *offer, const char *mime, int fd);
static void DataOffer_accept(struct DataOffer *offer, uint32_t serial, const char *mime);
static void DataOffer_finishDnD(struct DataOffer *offer);
static void DataOffer_setDnDActions(struct DataOffer *offer, int dnd_actions, int preferred_action);
static void DataOffer_callOfferHandler(struct DataOffer *offer, const char *mime);
static void DataOffer_callSelectionHandler(struct DataOffer *offer, enum DataTransferProtocol protocol);

// Implementation

static struct DataSource *DataSource_create(enum DataTransferProtocol protocol) {
    struct DataSource *source = calloc(1, sizeof(struct DataSource));

    if (source == NULL) {
        return NULL;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION && !zwp_selection_dm) {
        protocol = DATA_TRANSFER_PROTOCOL_WAYLAND;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        struct wl_data_source *wlDataSource = wl_data_device_manager_create_data_source(wl_ddm);
        if (wlDataSource == NULL) {
            free(source);
            return NULL;
        }

        wl_proxy_set_queue((struct wl_proxy *)wlDataSource, dataTransferQueue);
        wl_data_source_add_listener(wlDataSource, &wl_data_source_listener, source);

        source->protocol = DATA_TRANSFER_PROTOCOL_WAYLAND;
        source->wlDataSource = wlDataSource;

        return source;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        struct zwp_primary_selection_source_v1 *zwpPrimarySelectionSource =
            zwp_primary_selection_device_manager_v1_create_source(zwp_selection_dm);
        if (zwpPrimarySelectionSource == NULL) {
            free(source);
            return NULL;
        }

        wl_proxy_set_queue((struct wl_proxy *)zwpPrimarySelectionSource, dataTransferQueue);
        zwp_primary_selection_source_v1_add_listener(zwpPrimarySelectionSource,
                                                     &zwp_primary_selection_source_v1_listener, source);

        source->protocol = DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION;
        source->zwpPrimarySelectionSource = zwpPrimarySelectionSource;

        return source;
    }

    free(source);
    return NULL;
}

static void DataSource_destroy(struct DataSource *source) {
    if (source == NULL) {
        return;
    }

    if (source->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_source_destroy(source->wlDataSource);
    } else if (source->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_source_v1_destroy(source->zwpPrimarySelectionSource);
    }

    free(source);
}

static void DataSource_offer(const struct DataSource *source, const char *mime) {
    if (source->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_source_offer(source->wlDataSource, mime);
    } else if (source->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_source_v1_offer(source->zwpPrimarySelectionSource, mime);
    }
}

static void DataSource_setDnDActions(const struct DataSource *source, uint32_t actions) {
    if (source->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_source_set_actions(source->wlDataSource, actions);
    }
}

static void DataSource_setSelection(const struct DataSource *source, uint32_t serial) {
    if (source->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_device_set_selection(wlDataDevice, source->wlDataSource, serial);
    } else if (source->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_device_v1_set_selection(zwpPrimarySelectionDevice, source->zwpPrimarySelectionSource,
                                                      serial);
    }
}

static struct DataOffer *DataOffer_create(enum DataTransferProtocol protocol, void *waylandObject) {
    struct DataOffer *offer = calloc(1, sizeof(struct DataOffer));
    if (offer == NULL) {
        return NULL;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        struct wl_data_offer *wlDataOffer = waylandObject;

        wl_proxy_set_queue((struct wl_proxy *)wlDataOffer, dataTransferQueue);
        wl_data_offer_add_listener(wlDataOffer, &wlDataOfferListener, offer);

        offer->wlDataOffer = wlDataOffer;
        offer->protocol = DATA_TRANSFER_PROTOCOL_WAYLAND;
        return offer;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        struct zwp_primary_selection_offer_v1 *zwpPrimarySelectionOffer = waylandObject;

        wl_proxy_set_queue((struct wl_proxy *)zwpPrimarySelectionOffer, dataTransferQueue);
        zwp_primary_selection_offer_v1_add_listener(zwpPrimarySelectionOffer, &zwpPrimarySelectionOfferListener, offer);
        offer->zwpPrimarySelectionOffer = zwpPrimarySelectionOffer;
        offer->protocol = DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION;
        return offer;
    }

    free(offer);
    return NULL;
}

static void DataOffer_createJavaObject(JNIEnv *env, struct DataOffer *offer) {
    jobject obj = (*env)->NewObject(env, WLDataOfferClass, WLDataOffer_constructorMID, ptr_to_jlong(offer));
    EXCEPTION_CLEAR(env);
    if (obj == NULL) {
        return;
    }

    jobject globalRef = (*env)->NewGlobalRef(env, obj);
    EXCEPTION_CLEAR(env);
    if (globalRef == NULL) {
        return;
    }

    offer->javaObject = globalRef;
}

static void DataOffer_destroy(struct DataOffer *offer) {
    if (offer == NULL) {
        return;
    }

    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_destroy(offer->wlDataOffer);
    } else if (offer->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_offer_v1_destroy(offer->zwpPrimarySelectionOffer);
    }

    free(offer);
}

static void DataOffer_receive(struct DataOffer *offer, const char *mime, int fd) {
    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_receive(offer->wlDataOffer, mime, fd);
    } else if (offer->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_offer_v1_receive(offer->zwpPrimarySelectionOffer, mime, fd);
    }
}

static void DataOffer_accept(struct DataOffer *offer, uint32_t serial, const char *mime) {
    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_accept(offer->wlDataOffer, serial, mime);
    }
}

static void DataOffer_finishDnD(struct DataOffer *offer) {
    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_finish(offer->wlDataOffer);
    }
}

static void DataOffer_setDnDActions(struct DataOffer *offer, int dnd_actions, int preferred_action) {
    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_set_actions(offer->wlDataOffer, dnd_actions, preferred_action);
    }
}

static void DataOffer_callOfferHandler(struct DataOffer *offer, const char *mime) {
    assert(offer != NULL);

    if (offer->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    jstring mimeJavaString = (*env)->NewStringUTF(env, mime);
    EXCEPTION_CLEAR(env);
    if (mimeJavaString != NULL) {
        (*env)->CallVoidMethod(env, offer->javaObject, WLDataOffer_handleOfferMimeMID, mimeJavaString);
        EXCEPTION_CLEAR(env);
        (*env)->DeleteLocalRef(env, mimeJavaString);
    }
}

static void DataOffer_callSelectionHandler(struct DataOffer *offer, enum DataTransferProtocol protocol) {
    jobject offerObject = NULL;
    if (offer != NULL) {
        offerObject = offer->javaObject;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, WLDataDeviceObject, WLDataDevice_handleSelectionMID, offerObject,
                           protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION);
    EXCEPTION_CLEAR(env);
}

// Event handlers

static void wl_data_source_handle_target(void *user, struct wl_data_source *wl_data_source, const char *mime) {
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    jstring mimeJavaString = (*env)->NewStringUTF(env, mime);
    EXCEPTION_CLEAR(env);
    if (mimeJavaString != NULL) {
        (*env)->CallVoidMethod(env, source->javaObject, WLDataSource_handleTargetAcceptsMimeMID, mimeJavaString);
        EXCEPTION_CLEAR(env);
        (*env)->DeleteLocalRef(env, mimeJavaString);
    }
}

static void
wl_data_source_handle_send(void *user, struct wl_data_source *wl_data_source, const char *mime, int32_t fd) {
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    jstring mimeJavaString = (*env)->NewStringUTF(env, mime);
    EXCEPTION_CLEAR(env);
    if (mimeJavaString != NULL) {
        (*env)->CallVoidMethod(env, source->javaObject, WLDataSource_handleSendMID, mimeJavaString, fd);
        EXCEPTION_CLEAR(env);
        (*env)->DeleteLocalRef(env, mimeJavaString);
    }
}

static void wl_data_source_handle_cancelled(void *user, struct wl_data_source *wl_data_source) {
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, WLDataSource_handleCancelledMID);
    EXCEPTION_CLEAR(env);
}

static void wl_data_source_handle_dnd_drop_performed(void *user, struct wl_data_source *wl_data_source) {
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, WLDataSource_handleDnDDropPerformedMID);
    EXCEPTION_CLEAR(env);
}

static void wl_data_source_handle_dnd_finished(void *user, struct wl_data_source *wl_data_source) {
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, WLDataSource_handleDnDFinishedMID);
    EXCEPTION_CLEAR(env);
}

static void wl_data_source_handle_action(void *user, struct wl_data_source *wl_data_source, uint32_t action) {
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, WLDataSource_handleDnDActionMID, action);
    EXCEPTION_CLEAR(env);
}

static void zwp_primary_selection_source_handle_send(
    void *user, struct zwp_primary_selection_source_v1 *zwp_primary_selection_source_v1, const char *mime, int32_t fd) {
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    jstring mimeJavaString = (*env)->NewStringUTF(env, mime);
    EXCEPTION_CLEAR(env);
    if (mimeJavaString != NULL) {
        (*env)->CallVoidMethod(env, source->javaObject, WLDataSource_handleSendMID, mimeJavaString, fd);
        EXCEPTION_CLEAR(env);
        (*env)->DeleteLocalRef(env, mimeJavaString);
    }
}

static void
zwp_primary_selection_source_handle_cancelled(void *user,
                                              struct zwp_primary_selection_source_v1 *zwp_primary_selection_source_v1) {
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, WLDataSource_handleCancelledMID);
    EXCEPTION_CLEAR(env);
}

static void wl_data_offer_handle_offer(void *user, struct wl_data_offer *wl_data_offer, const char *mime) {
    DataOffer_callOfferHandler((struct DataOffer *)user, mime);
}

static void
wl_data_offer_handle_source_actions(void *user, struct wl_data_offer *wl_data_offer, uint32_t source_actions) {
    struct DataOffer *offer = user;
    assert(offer != NULL);

    if (offer->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, offer->javaObject, WLDataOffer_handleSourceActionsMID, (jint)source_actions);
    EXCEPTION_CLEAR(env);
}

static void wl_data_offer_handle_action(void *user, struct wl_data_offer *wl_data_offer, uint32_t action) {
    struct DataOffer *offer = user;
    assert(offer != NULL);

    if (offer->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, offer->javaObject, WLDataOffer_handleActionMID, (jint)action);
    EXCEPTION_CLEAR(env);
}

static void zwp_primary_selection_offer_handle_offer(
    void *user, struct zwp_primary_selection_offer_v1 *zwp_primary_selection_offer_v1, const char *mime) {
    DataOffer_callOfferHandler((struct DataOffer *)user, mime);
}

static void
wl_data_device_handle_data_offer(void *user, struct wl_data_device *wl_data_device, struct wl_data_offer *id) {
    struct DataOffer *offer = DataOffer_create(DATA_TRANSFER_PROTOCOL_WAYLAND, id);
    if (offer == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    DataOffer_createJavaObject(env, offer);
    if (offer->javaObject == NULL) {
        DataOffer_destroy(offer);
        return;
    }
}

static void wl_data_device_handle_enter(void *user,
                                        struct wl_data_device *wl_data_device,
                                        uint32_t serial,
                                        struct wl_surface *surface,
                                        wl_fixed_t x,
                                        wl_fixed_t y,
                                        struct wl_data_offer *id) {
    struct DataOffer *offer = (struct DataOffer *)wl_data_offer_get_user_data(id);
    assert(offer != NULL);

    if (offer->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, WLDataDeviceObject, WLDataDevice_handleDnDEnterMID, offer->javaObject, (jlong)serial,
                           ptr_to_jlong(surface), (jdouble)x / 256.0, (jdouble)y / 256.0);
    EXCEPTION_CLEAR(env);
}

static void wl_data_device_handle_leave(void *user, struct wl_data_device *wl_data_device) {
    JNIEnv *env = getEnv();
    assert(env != NULL);
    (*env)->CallVoidMethod(env, WLDataDeviceObject, WLDataDevice_handleDnDLeaveMID);
    EXCEPTION_CLEAR(env);
}

static void wl_data_device_handle_motion(
    void *user, struct wl_data_device *wl_data_device, uint32_t time, wl_fixed_t x, wl_fixed_t y) {
    JNIEnv *env = getEnv();
    assert(env != NULL);
    (*env)->CallVoidMethod(env, WLDataDeviceObject, WLDataDevice_handleDnDMotionMID, (jlong)time, (jdouble)x / 256.0,
                           (jdouble)y / 256.0);
    EXCEPTION_CLEAR(env);
}

static void wl_data_device_handle_drop(void *user, struct wl_data_device *wl_data_device) {
    JNIEnv *env = getEnv();
    assert(env != NULL);
    (*env)->CallVoidMethod(env, WLDataDeviceObject, WLDataDevice_handleDnDDropMID);
    EXCEPTION_CLEAR(env);
}

static void
wl_data_device_handle_selection(void *user, struct wl_data_device *wl_data_device, struct wl_data_offer *id) {
    struct DataOffer *offer = NULL;

    if (id != NULL) {
        offer = (struct DataOffer *)wl_data_offer_get_user_data(id);
        assert(offer != NULL);
    }

    DataOffer_callSelectionHandler(offer, DATA_TRANSFER_PROTOCOL_WAYLAND);
}

static void
zwp_primary_selection_device_handle_data_offer(void *user,
                                               struct zwp_primary_selection_device_v1 *zwp_primary_selection_device_v1,
                                               struct zwp_primary_selection_offer_v1 *id) {
    struct DataOffer *offer = DataOffer_create(DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION, id);
    if (offer == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    DataOffer_createJavaObject(env, offer);
    if (offer->javaObject == NULL) {
        DataOffer_destroy(offer);
        return;
    }
}

static void
zwp_primary_selection_device_handle_selection(void *user,
                                              struct zwp_primary_selection_device_v1 *zwp_primary_selection_device_v1,
                                              struct zwp_primary_selection_offer_v1 *id) {
    struct DataOffer *offer = NULL;

    if (id != NULL) {
        offer = (struct DataOffer *)zwp_primary_selection_offer_v1_get_user_data(id);
        assert(offer != NULL);
    }

    DataOffer_callSelectionHandler(offer, DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION);
}

// JNI functions

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataDevice_initNative(JNIEnv *env, jobject obj) {
    if (!initJavaRefs(env)) {
        JNU_ThrowInternalError(env, "Failed to initialize WLDataDevice java refs");
        return;
    }

    WLDataDeviceObject = (*env)->NewGlobalRef(env, obj);
    if (WLDataDeviceObject == NULL) {
        JNU_ThrowInternalError(env, "Couldn't get a global reference to the WLDataDevice object");
        return;
    }

    wlDataDevice = wl_data_device_manager_get_data_device(wl_ddm, wl_seat);
    if (wlDataDevice == NULL) {
        (*env)->DeleteGlobalRef(env, WLDataDeviceObject);
        WLDataDeviceObject = NULL;
        JNU_ThrowInternalError(env, "Couldn't get a Wayland data device");
        return;
    }

    zwpPrimarySelectionDevice = NULL;
    if (zwp_selection_dm != NULL) {
        zwpPrimarySelectionDevice = zwp_primary_selection_device_manager_v1_get_device(zwp_selection_dm, wl_seat);
        if (zwpPrimarySelectionDevice == NULL) {
            wl_data_device_destroy(wlDataDevice);
            (*env)->DeleteGlobalRef(env, WLDataDeviceObject);
            WLDataDeviceObject = NULL;
            JNU_ThrowInternalError(env, "Couldn't get zwp_primary_selection_device");
            return;
        }
    }

    dataTransferQueue = wl_display_create_queue(wl_display);
    if (dataTransferQueue == NULL) {
        if (zwpPrimarySelectionDevice != NULL) {
            zwp_primary_selection_device_v1_destroy(zwpPrimarySelectionDevice);
        }
        wl_data_device_destroy(wlDataDevice);
        (*env)->DeleteGlobalRef(env, WLDataDeviceObject);
        WLDataDeviceObject = NULL;

        JNU_ThrowInternalError(env, "Couldn't create an event queue for the data device");
        return;
    }

    wl_data_device_add_listener(wlDataDevice, &wlDataDeviceListener, NULL);

    if (zwpPrimarySelectionDevice != NULL) {
        zwp_primary_selection_device_v1_add_listener(zwpPrimarySelectionDevice, &zwpPrimarySelectionDeviceListener,
                                                     NULL);
    }
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataDevice_dispatchDataTransferQueueImpl(JNIEnv *env, jclass clazz) {
    assert(dataTransferQueue != NULL);

    while (wl_display_dispatch_queue(wl_display, dataTransferQueue) != -1) {
    }
}

JNIEXPORT jlong JNICALL Java_sun_awt_wl_WLDataSource_createImpl(JNIEnv *env, jclass wlDataSourceClass, jint protocol) {
    struct DataSource *source = DataSource_create(protocol);
    return ptr_to_jlong(source);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataSource_bindNative(JNIEnv *env,
                                                               jclass clazz,
                                                               jlong nativePtr,
                                                               jobject javaObject) {
    struct DataSource *source = jlong_to_ptr(nativePtr);
    jobject globalRef = (*env)->NewGlobalRef(env, javaObject);
    source->javaObject = globalRef;
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataSource_offerMimeImpl(JNIEnv *env,
                                                                  jclass clazz,
                                                                  jlong nativePtr,
                                                                  jstring mimeJavaString) {
    struct DataSource *source = jlong_to_ptr(nativePtr);
    const char *mime = (*env)->GetStringUTFChars(env, mimeJavaString, NULL);
    JNU_CHECK_EXCEPTION(env);
    DataSource_offer(source, mime);
    (*env)->ReleaseStringUTFChars(env, mimeJavaString, mime);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataSource_destroyImpl(JNIEnv *env, jclass clazz, jlong nativePtr) {
    struct DataSource *source = jlong_to_ptr(nativePtr);
    if (source->javaObject != NULL) {
        (*env)->DeleteGlobalRef(env, source->javaObject);
    }
    DataSource_destroy(source);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataSource_setDnDActionsImpl(JNIEnv *env,
                                                                      jclass clazz,
                                                                      jlong nativePtr,
                                                                      jint actions) {
    struct DataSource *source = jlong_to_ptr(nativePtr);
    DataSource_setDnDActions(source, actions);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataSource_setSelectionImpl(JNIEnv *env,
                                                                     jclass clazz,
                                                                     jlong nativePtr,
                                                                     jlong serial) {
    struct DataSource *source = jlong_to_ptr(nativePtr);
    DataSource_setSelection(source, (uint32_t)serial);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataOffer_destroyImpl(JNIEnv *env, jclass clazz, jlong nativePtr) {
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    if (offer->javaObject != NULL) {
        (*env)->DeleteGlobalRef(env, offer->javaObject);
    }
    DataOffer_destroy(offer);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataOffer_acceptImpl(
    JNIEnv *env, jclass clazz, jlong nativePtr, jlong serial, jstring mimeJavaString) {
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    const char *mime = NULL;

    if (mimeJavaString != NULL) {
        mime = (*env)->GetStringUTFChars(env, mimeJavaString, NULL);
        JNU_CHECK_EXCEPTION(env);
    }

    DataOffer_accept(offer, (uint32_t)serial, mime);

    if (mime != NULL) {
        (*env)->ReleaseStringUTFChars(env, mimeJavaString, mime);
    }
}

JNIEXPORT jint JNICALL Java_sun_awt_wl_WLDataOffer_openReceivePipe(JNIEnv *env,
                                                                   jclass clazz,
                                                                   jlong nativePtr,
                                                                   jstring mimeJavaString) {
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    const char *mime = (*env)->GetStringUTFChars(env, mimeJavaString, NULL);
    JNU_CHECK_EXCEPTION_RETURN(env, -1);

    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) {
        JNU_ThrowIOExceptionWithMessageAndLastError(env, "pipe2");
        return -1;
    }

    DataOffer_receive(offer, mime, fds[1]);

    // Flush the request to receive
    wlFlushToServer(env);

    close(fds[1]);
    (*env)->ReleaseStringUTFChars(env, mimeJavaString, mime);

    return fds[0];
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataOffer_finishDnDImpl(JNIEnv *env, jclass clazz, jlong nativePtr) {
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    DataOffer_finishDnD(offer);
}

JNIEXPORT void JNICALL Java_sun_awt_wl_WLDataOffer_setDnDActionsImpl(
    JNIEnv *env, jclass clazz, jlong nativePtr, jint actions, jint preferredAction) {
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    DataOffer_setDnDActions(offer, actions, preferredAction);
}
