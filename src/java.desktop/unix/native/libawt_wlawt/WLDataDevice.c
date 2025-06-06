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
#include "sun_awt_wl_WLDataSource.h"
#include "sun_awt_wl_WLDataOffer.h"
#include "wayland-client-protocol.h"


// Types

enum DataTransferProtocol
{
    DATA_TRANSFER_PROTOCOL_WAYLAND = sun_awt_wl_WLDataDevice_DATA_TRANSFER_PROTOCOL_WAYLAND,
    DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION = sun_awt_wl_WLDataDevice_DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION,
};

// native part of WLDataDevice, one instance per seat
// seat's wl_data_device and zwp_primary_selection_device_v1 have user pointers to this struct
struct DataDevice
{
    // global reference to the corresponding WLDataDevice object
    // TODO: it's currently never destroyed, because WLDataDevice is never destroyed
    jobject javaObject;

    struct wl_event_queue *dataSourceQueue;
    struct wl_data_device *wlDataDevice;
    struct zwp_primary_selection_device_v1 *zwpPrimarySelectionDevice;
};

// native part of WLDataSource, remains alive until WLDataSource.destroy() is called
// pointer to this structure is the wl_data_source's (zwp_primary_selection_source_v1's) user pointer
struct DataSource
{
    enum DataTransferProtocol protocol;
    // global reference to the corresponding WLDataSource object
    // destroyed in WLDataSource.destroy()
    jobject javaObject;

    union
    {
        void *anyPtr;

        struct wl_data_source *wlDataSource;
        struct zwp_primary_selection_source_v1 *zwpPrimarySelectionSource;
    };
};

// native part of WLDataOffer, remains alive until WLDataOffer.destroy() is called
// pointer to this structure is the wl_data_offer's (zwp_primary_selection_offer_v1's) user pointer
struct DataOffer
{
    enum DataTransferProtocol protocol;
    // global reference to the corresponding WLDataOffer object
    // destroyed in WLDataOffer.destroy()
    jobject javaObject;

    union
    {
        void *anyPtr;

        struct wl_data_offer *wlDataOffer;
        struct zwp_primary_selection_offer_v1 *zwpPrimarySelectionOffer;
    };
};

// Java refs

static jclass wlDataOfferClass;
static jmethodID wlDataDeviceHandleDnDEnterMID;
static jmethodID wlDataDeviceHandleDnDLeaveMID;
static jmethodID wlDataDeviceHandleDnDMotionMID;
static jmethodID wlDataDeviceHandleDnDDropMID;
static jmethodID wlDataDeviceHandleSelectionMID;
static jmethodID wlDataSourceHandleTargetAcceptsMimeMID;
static jmethodID wlDataSourceHandleSendMID;
static jmethodID wlDataSourceHandleCancelledMID;
static jmethodID wlDataSourceHandleDnDDropPerformedMID;
static jmethodID wlDataSourceHandleDnDFinishedMID;
static jmethodID wlDataSourceHandleDnDActionMID;
static jmethodID wlDataOfferConstructorMID;
static jmethodID wlDataOfferHandleOfferMimeMID;
static jmethodID wlDataOfferHandleSourceActionsMID;
static jmethodID wlDataOfferHandleActionMID;

static bool
initJavaRefs(JNIEnv *env)
{
    jclass wlDataDeviceClass = NULL;
    jclass wlDataSourceClass = NULL;

    GET_CLASS_RETURN(wlDataDeviceClass, "sun/awt/wl/WLDataDevice", false);
    GET_CLASS_RETURN(wlDataSourceClass, "sun/awt/wl/WLDataSource", false);
    GET_CLASS_RETURN(wlDataOfferClass, "sun/awt/wl/WLDataOffer", false);

    GET_METHOD_RETURN(wlDataDeviceHandleDnDEnterMID, wlDataDeviceClass, "handleDnDEnter",
                      "(Lsun/awt/wl/WLDataOffer;JJDD)V", false);
    GET_METHOD_RETURN(wlDataDeviceHandleDnDLeaveMID, wlDataDeviceClass, "handleDnDLeave", "()V", false);
    GET_METHOD_RETURN(wlDataDeviceHandleDnDMotionMID, wlDataDeviceClass, "handleDnDMotion", "(JDD)V", false);
    GET_METHOD_RETURN(wlDataDeviceHandleDnDDropMID, wlDataDeviceClass, "handleDnDDrop", "()V", false);
    GET_METHOD_RETURN(wlDataDeviceHandleSelectionMID, wlDataDeviceClass, "handleSelection",
                      "(Lsun/awt/wl/WLDataOffer;I)V", false);
    GET_METHOD_RETURN(wlDataSourceHandleTargetAcceptsMimeMID, wlDataSourceClass, "handleTargetAcceptsMime",
                      "(Ljava/lang/String;)V", false);
    GET_METHOD_RETURN(wlDataSourceHandleSendMID, wlDataSourceClass, "handleSend", "(Ljava/lang/String;I)V", false);
    GET_METHOD_RETURN(wlDataSourceHandleCancelledMID, wlDataSourceClass, "handleCancelled", "()V", false);
    GET_METHOD_RETURN(wlDataSourceHandleDnDDropPerformedMID, wlDataSourceClass, "handleDnDDropPerformed", "()V",
                      false);
    GET_METHOD_RETURN(wlDataSourceHandleDnDFinishedMID, wlDataSourceClass, "handleDnDFinished", "()V", false);
    GET_METHOD_RETURN(wlDataSourceHandleDnDActionMID, wlDataSourceClass, "handleDnDAction", "(I)V", false);
    GET_METHOD_RETURN(wlDataOfferConstructorMID, wlDataOfferClass, "<init>", "(J)V", false);
    GET_METHOD_RETURN(wlDataOfferHandleOfferMimeMID, wlDataOfferClass, "handleOfferMime", "(Ljava/lang/String;)V",
                      false);
    GET_METHOD_RETURN(wlDataOfferHandleSourceActionsMID, wlDataOfferClass, "handleSourceActions", "(I)V", false);
    GET_METHOD_RETURN(wlDataOfferHandleActionMID, wlDataOfferClass, "handleAction", "(I)V", false);

    return true;
}

// Event handlers declarations

static void
wl_data_source_handle_target(void *user, struct wl_data_source *source, const char *mime);

static void
wl_data_source_handle_send(void *user, struct wl_data_source *source, const char *mime, int32_t fd);

static void
wl_data_source_handle_cancelled(void *user, struct wl_data_source *source);

static void
wl_data_source_handle_dnd_drop_performed(void *user, struct wl_data_source *source);

static void
wl_data_source_handle_dnd_finished(void *user, struct wl_data_source *source);

static void
wl_data_source_handle_action(void *user, struct wl_data_source *source, uint32_t action);

static const struct wl_data_source_listener wl_data_source_listener = {
        .target = wl_data_source_handle_target,
        .send = wl_data_source_handle_send,
        .cancelled = wl_data_source_handle_cancelled,
        .dnd_drop_performed = wl_data_source_handle_dnd_drop_performed,
        .dnd_finished = wl_data_source_handle_dnd_finished,
        .action = wl_data_source_handle_action,
};

static void
zwp_primary_selection_source_handle_send(void *user,
                                         struct zwp_primary_selection_source_v1 *source,
                                         const char *mime,
                                         int32_t fd);

static void
zwp_primary_selection_source_handle_cancelled(void *user, struct zwp_primary_selection_source_v1 *source);

static const struct zwp_primary_selection_source_v1_listener zwp_primary_selection_source_v1_listener = {
        .send = zwp_primary_selection_source_handle_send,
        .cancelled = zwp_primary_selection_source_handle_cancelled,
};

static void
wl_data_offer_handle_offer(void *user, struct wl_data_offer *offer, const char *mime);

static void
wl_data_offer_handle_source_actions(void *user, struct wl_data_offer *offer, uint32_t source_actions);

static void
wl_data_offer_handle_action(void *user, struct wl_data_offer *offer, uint32_t action);

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

static void
wl_data_device_handle_enter(void *user,
                            struct wl_data_device *wl_data_device,
                            uint32_t serial,
                            struct wl_surface *surface,
                            wl_fixed_t x,
                            wl_fixed_t y,
                            struct wl_data_offer *id);

static void
wl_data_device_handle_leave(void *user, struct wl_data_device *wl_data_device);

static void
wl_data_device_handle_motion(
        void *user, struct wl_data_device *wl_data_device, uint32_t time, wl_fixed_t x, wl_fixed_t y);

static void
wl_data_device_handle_drop(void *user, struct wl_data_device *wl_data_device);

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

static void
zwp_primary_selection_device_handle_data_offer(void *user,
                                               struct zwp_primary_selection_device_v1 *device,
                                               struct zwp_primary_selection_offer_v1 *id);

static void
zwp_primary_selection_device_handle_selection(void *user,
                                              struct zwp_primary_selection_device_v1 *device,
                                              struct zwp_primary_selection_offer_v1 *id);

static const struct zwp_primary_selection_device_v1_listener zwpPrimarySelectionDeviceListener = {
        .data_offer = zwp_primary_selection_device_handle_data_offer,
        .selection = zwp_primary_selection_device_handle_selection,
};

static void
DataSource_offer(const struct DataSource *source, const char *mime);

static void
DataSource_setDnDActions(const struct DataSource *source, uint32_t actions);

static struct DataOffer *
DataOffer_create(struct DataDevice *dataDevice, enum DataTransferProtocol protocol, void *waylandObject);

static void
DataOffer_destroy(struct DataOffer *offer);

static void
DataOffer_receive(struct DataOffer *offer, const char *mime, int fd);

static void
DataOffer_accept(struct DataOffer *offer, uint32_t serial, const char *mime);

static void
DataOffer_finishDnD(struct DataOffer *offer);

static void
DataOffer_setDnDActions(struct DataOffer *offer, int dnd_actions, int preferred_action);

static void
DataOffer_callOfferHandler(struct DataOffer *offer, const char *mime);

static void
DataOffer_callSelectionHandler(struct DataDevice *dataDevice, struct DataOffer *offer,
                               enum DataTransferProtocol protocol);

// Implementation

static void
DataSource_offer(const struct DataSource *source, const char *mime)
{
    if (source->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_source_offer(source->wlDataSource, mime);
    } else if (source->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_source_v1_offer(source->zwpPrimarySelectionSource, mime);
    }
}

static void
DataSource_setDnDActions(const struct DataSource *source, uint32_t actions)
{
    if (source->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_source_set_actions(source->wlDataSource, actions);
    }
}

static struct DataOffer *
DataOffer_create(struct DataDevice *dataDevice, enum DataTransferProtocol protocol, void *waylandObject)
{
    struct DataOffer *offer = calloc(1, sizeof(struct DataOffer));
    if (offer == NULL) {
        return NULL;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    jobject obj = (*env)->NewObject(env, wlDataOfferClass, wlDataOfferConstructorMID, ptr_to_jlong(offer));

    // Can't throw Java exceptions during Wayland event dispatch
    EXCEPTION_CLEAR(env);

    if (obj == NULL) {
        free(offer);
        return NULL;
    }

    // Cleared in DataOffer_destroy
    jobject globalRef = (*env)->NewGlobalRef(env, obj);

    EXCEPTION_CLEAR(env);
    if (globalRef == NULL) {
        (*env)->DeleteLocalRef(env, obj);
        free(offer);
        return NULL;
    }

    offer->javaObject = globalRef;

    if (protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        struct wl_data_offer *wlDataOffer = waylandObject;

        wl_data_offer_add_listener(wlDataOffer, &wlDataOfferListener, offer);

        offer->wlDataOffer = wlDataOffer;
        offer->protocol = DATA_TRANSFER_PROTOCOL_WAYLAND;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        struct zwp_primary_selection_offer_v1 *zwpPrimarySelectionOffer = waylandObject;

        zwp_primary_selection_offer_v1_add_listener(zwpPrimarySelectionOffer, &zwpPrimarySelectionOfferListener, offer);
        offer->zwpPrimarySelectionOffer = zwpPrimarySelectionOffer;
        offer->protocol = DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION;
    }

    return offer;
}

static void
DataOffer_destroy(struct DataOffer *offer)
{
    if (offer == NULL) {
        return;
    }

    if (offer->javaObject != NULL) {
        JNIEnv *env = getEnv();
        assert(env != NULL);
        (*env)->DeleteGlobalRef(env, offer->javaObject);
        offer->javaObject = NULL;
    }

    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_destroy(offer->wlDataOffer);
    } else if (offer->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_offer_v1_destroy(offer->zwpPrimarySelectionOffer);
    }

    free(offer);
}

static void
DataOffer_receive(struct DataOffer *offer, const char *mime, int fd)
{
    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_receive(offer->wlDataOffer, mime, fd);
    } else if (offer->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_offer_v1_receive(offer->zwpPrimarySelectionOffer, mime, fd);
    }
}

static void
DataOffer_accept(struct DataOffer *offer, uint32_t serial, const char *mime)
{
    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_accept(offer->wlDataOffer, serial, mime);
    }
}

static void
DataOffer_finishDnD(struct DataOffer *offer)
{
    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_finish(offer->wlDataOffer);
    }
}

static void
DataOffer_setDnDActions(struct DataOffer *offer, int dnd_actions, int preferred_action)
{
    if (offer->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_offer_set_actions(offer->wlDataOffer, dnd_actions, preferred_action);
    }
}

static void
DataOffer_callOfferHandler(struct DataOffer *offer, const char *mime)
{
    assert(offer != NULL);

    if (offer->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    jstring mimeJavaString = (*env)->NewStringUTF(env, mime);
    EXCEPTION_CLEAR(env);
    if (mimeJavaString != NULL) {
        (*env)->CallVoidMethod(env, offer->javaObject, wlDataOfferHandleOfferMimeMID, mimeJavaString);
        EXCEPTION_CLEAR(env);
        (*env)->DeleteLocalRef(env, mimeJavaString);
    }
}

static void
DataOffer_callSelectionHandler(struct DataDevice *dataDevice, struct DataOffer *offer,
                               enum DataTransferProtocol protocol)
{
    assert(dataDevice != NULL);
    // offer can be NULL, this means that the selection was cleared
    jobject offerObject = NULL;
    if (offer != NULL) {
        offerObject = offer->javaObject;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, dataDevice->javaObject, wlDataDeviceHandleSelectionMID, offerObject, protocol);
    EXCEPTION_CLEAR(env);
}

// Event handlers

static void
wl_data_source_handle_target(void *user, struct wl_data_source *wl_data_source, const char *mime)
{
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
        (*env)->CallVoidMethod(env, source->javaObject, wlDataSourceHandleTargetAcceptsMimeMID, mimeJavaString);
        EXCEPTION_CLEAR(env);
        (*env)->DeleteLocalRef(env, mimeJavaString);
    }
}

static void
wl_data_source_handle_send(void *user, struct wl_data_source *wl_data_source, const char *mime, int32_t fd)
{
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
        (*env)->CallVoidMethod(env, source->javaObject, wlDataSourceHandleSendMID, mimeJavaString, fd);
        EXCEPTION_CLEAR(env);
        (*env)->DeleteLocalRef(env, mimeJavaString);
    }
}

static void
wl_data_source_handle_cancelled(void *user, struct wl_data_source *wl_data_source)
{
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, wlDataSourceHandleCancelledMID);
    EXCEPTION_CLEAR(env);
}

static void
wl_data_source_handle_dnd_drop_performed(void *user, struct wl_data_source *wl_data_source)
{
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, wlDataSourceHandleDnDDropPerformedMID);
    EXCEPTION_CLEAR(env);
}

static void
wl_data_source_handle_dnd_finished(void *user, struct wl_data_source *wl_data_source)
{
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, wlDataSourceHandleDnDFinishedMID);
    EXCEPTION_CLEAR(env);
}

static void
wl_data_source_handle_action(void *user, struct wl_data_source *wl_data_source, uint32_t action)
{
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, wlDataSourceHandleDnDActionMID, action);
    EXCEPTION_CLEAR(env);
}

static void
zwp_primary_selection_source_handle_send(
        void *user, struct zwp_primary_selection_source_v1 *zwp_primary_selection_source_v1, const char *mime,
        int32_t fd)
{
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
        (*env)->CallVoidMethod(env, source->javaObject, wlDataSourceHandleSendMID, mimeJavaString, fd);
        EXCEPTION_CLEAR(env);
        (*env)->DeleteLocalRef(env, mimeJavaString);
    }
}

static void
zwp_primary_selection_source_handle_cancelled(void *user,
                                              struct zwp_primary_selection_source_v1 *zwp_primary_selection_source_v1)
{
    struct DataSource *source = user;
    assert(source != NULL);

    if (source->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, source->javaObject, wlDataSourceHandleCancelledMID);
    EXCEPTION_CLEAR(env);
}

static void
wl_data_offer_handle_offer(void *user, struct wl_data_offer *wl_data_offer, const char *mime)
{
    DataOffer_callOfferHandler((struct DataOffer *) user, mime);
}

static void
wl_data_offer_handle_source_actions(void *user, struct wl_data_offer *wl_data_offer, uint32_t source_actions)
{
    struct DataOffer *offer = user;
    assert(offer != NULL);

    if (offer->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, offer->javaObject, wlDataOfferHandleSourceActionsMID, (jint) source_actions);
    EXCEPTION_CLEAR(env);
}

static void
wl_data_offer_handle_action(void *user, struct wl_data_offer *wl_data_offer, uint32_t action)
{
    struct DataOffer *offer = user;
    assert(offer != NULL);

    if (offer->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, offer->javaObject, wlDataOfferHandleActionMID, (jint) action);
    EXCEPTION_CLEAR(env);
}

static void
zwp_primary_selection_offer_handle_offer(void *user,
                                         struct zwp_primary_selection_offer_v1 *zwp_primary_selection_offer_v1,
                                         const char *mime)
{
    DataOffer_callOfferHandler((struct DataOffer *) user, mime);
}

static void
wl_data_device_handle_data_offer(void *user, struct wl_data_device *wl_data_device, struct wl_data_offer *id)
{
    struct DataDevice *dataDevice = user;
    assert(dataDevice != NULL);

    struct DataOffer *offer = DataOffer_create(dataDevice, DATA_TRANSFER_PROTOCOL_WAYLAND, id);
    if (offer == NULL) {
        // This can only happen in OOM scenarios.
        // We can't throw a Java exception here.
        // Destroy the offer, since we won't be able to do anything useful with it.
        wl_data_offer_destroy(id);
        return;
    }
    // no memory leak here: allocated DataOffer object will be
    // associated with the wl_data_offer
}

static void
wl_data_device_handle_enter(void *user,
                            struct wl_data_device *wl_data_device,
                            uint32_t serial,
                            struct wl_surface *surface,
                            wl_fixed_t x,
                            wl_fixed_t y,
                            struct wl_data_offer *id)
{
    struct DataDevice *dataDevice = user;
    assert(dataDevice != NULL);
    struct DataOffer *offer = (struct DataOffer *) wl_data_offer_get_user_data(id);
    assert(offer != NULL);

    if (offer->javaObject == NULL) {
        return;
    }

    JNIEnv *env = getEnv();
    assert(env != NULL);

    (*env)->CallVoidMethod(env, dataDevice->javaObject, wlDataDeviceHandleDnDEnterMID, offer->javaObject,
                           (jlong) serial,
                           ptr_to_jlong(surface), wl_fixed_to_double(x), wl_fixed_to_double(y));
    EXCEPTION_CLEAR(env);
}

static void
wl_data_device_handle_leave(void *user, struct wl_data_device *wl_data_device)
{
    struct DataDevice *dataDevice = user;
    assert(dataDevice != NULL);

    JNIEnv *env = getEnv();
    assert(env != NULL);
    (*env)->CallVoidMethod(env, dataDevice->javaObject, wlDataDeviceHandleDnDLeaveMID);
    EXCEPTION_CLEAR(env);
}

static void
wl_data_device_handle_motion(
        void *user, struct wl_data_device *wl_data_device, uint32_t time, wl_fixed_t x, wl_fixed_t y)
{
    struct DataDevice *dataDevice = user;
    assert(dataDevice != NULL);

    JNIEnv *env = getEnv();
    assert(env != NULL);
    (*env)->CallVoidMethod(env, dataDevice->javaObject, wlDataDeviceHandleDnDMotionMID, (jlong) time,
                           wl_fixed_to_double(x),
                           wl_fixed_to_double(y));
    EXCEPTION_CLEAR(env);
}

static void
wl_data_device_handle_drop(void *user, struct wl_data_device *wl_data_device)
{
    struct DataDevice *dataDevice = user;
    assert(dataDevice != NULL);

    JNIEnv *env = getEnv();
    assert(env != NULL);
    (*env)->CallVoidMethod(env, dataDevice->javaObject, wlDataDeviceHandleDnDDropMID);
    EXCEPTION_CLEAR(env);
}

static void
wl_data_device_handle_selection(void *user, struct wl_data_device *wl_data_device, struct wl_data_offer *id)
{
    struct DataDevice *dataDevice = user;
    assert(dataDevice != NULL);
    struct DataOffer *offer = NULL;

    // id can be NULL, this means that the selection was cleared
    if (id != NULL) {
        offer = (struct DataOffer *) wl_data_offer_get_user_data(id);
        assert(offer != NULL);
    }

    DataOffer_callSelectionHandler(dataDevice, offer, DATA_TRANSFER_PROTOCOL_WAYLAND);
}

static void
zwp_primary_selection_device_handle_data_offer(void *user,
                                               struct zwp_primary_selection_device_v1 *zwp_primary_selection_device_v1,
                                               struct zwp_primary_selection_offer_v1 *id)
{
    struct DataDevice *dataDevice = user;
    struct DataOffer *offer = DataOffer_create(dataDevice, DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION, id);
    if (offer == NULL) {
        // This can only happen in OOM scenarios.
        // We can't throw a Java exception here.
        // Destroy the offer, since we won't be able to do anything useful with it.
        zwp_primary_selection_offer_v1_destroy(id);
        return;
    }
    // no memory leak here: allocated DataOffer object will be
    // associated with the zwp_primary_selection_offer_v1
}

static void
zwp_primary_selection_device_handle_selection(void *user,
                                              struct zwp_primary_selection_device_v1 *zwp_primary_selection_device_v1,
                                              struct zwp_primary_selection_offer_v1 *id)
{
    struct DataDevice *dataDevice = user;
    assert(dataDevice != NULL);

    struct DataOffer *offer = NULL;

    // id can be NULL, this means that the selection was cleared
    if (id != NULL) {
        offer = (struct DataOffer *) zwp_primary_selection_offer_v1_get_user_data(id);
        assert(offer != NULL);
    }

    DataOffer_callSelectionHandler(dataDevice, offer, DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION);
}

// JNI functions

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataDevice_initIDs(JNIEnv *env, jclass clazz)
{
    if (!initJavaRefs(env)) {
        JNU_ThrowInternalError(env, "Failed to initialize WLDataDevice java refs");
    }
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLDataDevice_initNative(JNIEnv *env, jobject obj, jlong wlSeatPtr)
{
    struct wl_seat *seat = (wlSeatPtr == 0) ? wl_seat : (struct wl_seat *) jlong_to_ptr(wlSeatPtr);

    struct DataDevice *dataDevice = calloc(1, sizeof(struct DataDevice));
    if (dataDevice == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Failed to allocate DataDevice");
        return 0;
    }

    dataDevice->javaObject = (*env)->NewGlobalRef(env, obj);
    if ((*env)->ExceptionCheck(env)) {
        goto error_cleanup;
    }
    if (dataDevice->javaObject == NULL) {
        JNU_ThrowInternalError(env, "Failed to initialize WLDataDevice");
        goto error_cleanup;
    }

    dataDevice->wlDataDevice = wl_data_device_manager_get_data_device(wl_ddm, seat);
    if (dataDevice->wlDataDevice == NULL) {
        JNU_ThrowInternalError(env, "Couldn't get a Wayland data device");
        goto error_cleanup;
    }

    dataDevice->zwpPrimarySelectionDevice = NULL;
    if (zwp_selection_dm != NULL) {
        dataDevice->zwpPrimarySelectionDevice = zwp_primary_selection_device_manager_v1_get_device(zwp_selection_dm,
                                                                                                   seat);
        if (dataDevice->zwpPrimarySelectionDevice == NULL) {
            JNU_ThrowInternalError(env, "Couldn't get zwp_primary_selection_device");
            goto error_cleanup;
        }
    }

    dataDevice->dataSourceQueue = wl_display_create_queue(wl_display);
    if (dataDevice->dataSourceQueue == NULL) {
        JNU_ThrowInternalError(env, "Couldn't create an event queue for the data device");
        goto error_cleanup;
    }

    wl_data_device_add_listener(dataDevice->wlDataDevice, &wlDataDeviceListener, dataDevice);

    if (dataDevice->zwpPrimarySelectionDevice != NULL) {
        zwp_primary_selection_device_v1_add_listener(dataDevice->zwpPrimarySelectionDevice,
                                                     &zwpPrimarySelectionDeviceListener,
                                                     dataDevice);
    }

    return ptr_to_jlong(dataDevice);

    error_cleanup:
    if (dataDevice->dataSourceQueue != NULL) {
        wl_event_queue_destroy(dataDevice->dataSourceQueue);
    }

    if (dataDevice->zwpPrimarySelectionDevice != NULL) {
        zwp_primary_selection_device_v1_destroy(dataDevice->zwpPrimarySelectionDevice);
    }

    if (dataDevice->wlDataDevice != NULL) {
        wl_data_device_destroy(dataDevice->wlDataDevice);
    }

    if (dataDevice->javaObject != NULL) {
        (*env)->DeleteGlobalRef(env, dataDevice->javaObject);
    }

    if (dataDevice != NULL) {
        free(dataDevice);
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_sun_awt_wl_WLDataDevice_isProtocolSupportedImpl(JNIEnv *env, jclass clazz, jlong nativePtr, jint protocol)
{
    struct DataDevice *dataDevice = jlong_to_ptr(nativePtr);
    assert(dataDevice != NULL);

    if (protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        return true;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        return dataDevice->zwpPrimarySelectionDevice != NULL;
    }

    return false;
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataDevice_dispatchDataSourceQueueImpl(JNIEnv *env, jclass clazz, jlong nativePtr)
{
    struct DataDevice *dataDevice = jlong_to_ptr(nativePtr);
    assert(dataDevice != NULL);

    while (wl_display_dispatch_queue(wl_display, dataDevice->dataSourceQueue) != -1) {
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataDevice_setSelectionImpl(JNIEnv *env,
                                              jclass clazz,
                                              jint protocol,
                                              jlong dataDeviceNativePtr,
                                              jlong dataSourceNativePtr,
                                              jlong serial)
{
    struct DataDevice *dataDevice = jlong_to_ptr(dataDeviceNativePtr);
    assert(dataDevice != NULL);

    struct DataSource *source = jlong_to_ptr(dataSourceNativePtr);

    if (protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_device_set_selection(dataDevice->wlDataDevice, (source == NULL) ? NULL : source->wlDataSource, serial);
    } else if (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_device_v1_set_selection(
                dataDevice->zwpPrimarySelectionDevice, (source == NULL) ? NULL : source->zwpPrimarySelectionSource,
                serial);
    }
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataDevice_startDragImpl(JNIEnv *env, jclass clazz, jlong dataDeviceNativePtr,
                                           jlong dataSourceNativePtr, jlong wlSurfacePtr,
                                           jlong iconPtr, jlong serial)
{
    struct DataDevice *dataDevice = jlong_to_ptr(dataDeviceNativePtr);
    assert(dataDevice != NULL);

    struct DataSource *source = jlong_to_ptr(dataSourceNativePtr);
    assert(source != NULL);

    wl_data_device_start_drag(dataDevice->wlDataDevice, source->wlDataSource, jlong_to_ptr(wlSurfacePtr),
                              jlong_to_ptr(iconPtr), serial);
}

JNIEXPORT jlong JNICALL
Java_sun_awt_wl_WLDataSource_initNative(JNIEnv *env, jobject javaObject, jlong dataDeviceNativePtr, jint protocol)
{
    struct DataDevice *dataDevice = jlong_to_ptr(dataDeviceNativePtr);
    assert(dataDevice != NULL);

    struct DataSource *dataSource = calloc(1, sizeof(struct DataSource));

    if (dataSource == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Failed to allocate DataSource");
        return 0;
    }

    // cleaned up in WLDataSource.destroy()
    dataSource->javaObject = (*env)->NewGlobalRef(env, javaObject);
    if ((*env)->ExceptionCheck(env)) {
        free(dataSource);
        return 0;
    }
    if (dataSource->javaObject == NULL) {
        free(dataSource);
        JNU_ThrowInternalError(env, "Failed to create a reference to WLDataSource");
        return 0;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION && !zwp_selection_dm) {
        protocol = DATA_TRANSFER_PROTOCOL_WAYLAND;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        struct wl_data_source *wlDataSource = wl_data_device_manager_create_data_source(wl_ddm);
        if (wlDataSource == NULL) {
            free(dataSource);
            JNU_ThrowByName(env, "java/awt/AWTError", "Wayland error creating wl_data_source proxy");
            return 0;
        }

        wl_proxy_set_queue((struct wl_proxy *) wlDataSource, dataDevice->dataSourceQueue);
        wl_data_source_add_listener(wlDataSource, &wl_data_source_listener, dataSource);

        dataSource->protocol = DATA_TRANSFER_PROTOCOL_WAYLAND;
        dataSource->wlDataSource = wlDataSource;
    }

    if (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        struct zwp_primary_selection_source_v1 *zwpPrimarySelectionSource =
                zwp_primary_selection_device_manager_v1_create_source(zwp_selection_dm);
        if (zwpPrimarySelectionSource == NULL) {
            free(dataSource);
            JNU_ThrowByName(env, "java/awt/AWTError", "Wayland error creating zwp_primary_selection_source_v1 proxy");
            return 0;
        }

        wl_proxy_set_queue((struct wl_proxy *) zwpPrimarySelectionSource, dataDevice->dataSourceQueue);
        zwp_primary_selection_source_v1_add_listener(zwpPrimarySelectionSource,
                                                     &zwp_primary_selection_source_v1_listener, dataSource);

        dataSource->protocol = DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION;
        dataSource->zwpPrimarySelectionSource = zwpPrimarySelectionSource;
    }

    return ptr_to_jlong(dataSource);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataSource_offerMimeImpl(JNIEnv *env,
                                           jclass clazz,
                                           jlong nativePtr,
                                           jstring mimeJavaString)
{
    struct DataSource *source = jlong_to_ptr(nativePtr);
    const char *mime = (*env)->GetStringUTFChars(env, mimeJavaString, NULL);
    JNU_CHECK_EXCEPTION(env);
    DataSource_offer(source, mime);
    (*env)->ReleaseStringUTFChars(env, mimeJavaString, mime);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataSource_destroyImpl(JNIEnv *env, jclass clazz, jlong nativePtr)
{
    struct DataSource *source = jlong_to_ptr(nativePtr);
    if (source == NULL) {
        return;
    }

    if (source->javaObject != NULL) {
        (*env)->DeleteGlobalRef(env, source->javaObject);
        source->javaObject = NULL;
    }

    if (source->protocol == DATA_TRANSFER_PROTOCOL_WAYLAND) {
        wl_data_source_destroy(source->wlDataSource);
    } else if (source->protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) {
        zwp_primary_selection_source_v1_destroy(source->zwpPrimarySelectionSource);
    }

    free(source);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataSource_setDnDActionsImpl(JNIEnv *env,
                                               jclass clazz,
                                               jlong nativePtr,
                                               jint actions)
{
    struct DataSource *source = jlong_to_ptr(nativePtr);
    DataSource_setDnDActions(source, actions);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataOffer_destroyImpl(JNIEnv *env, jclass clazz, jlong nativePtr)
{
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    DataOffer_destroy(offer);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataOffer_acceptImpl(
        JNIEnv *env, jclass clazz, jlong nativePtr, jlong serial, jstring mimeJavaString)
{
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    const char *mime = NULL;

    if (mimeJavaString != NULL) {
        mime = (*env)->GetStringUTFChars(env, mimeJavaString, NULL);
        JNU_CHECK_EXCEPTION(env);
    }

    DataOffer_accept(offer, (uint32_t) serial, mime);

    if (mime != NULL) {
        (*env)->ReleaseStringUTFChars(env, mimeJavaString, mime);
    }
}

JNIEXPORT jint JNICALL
Java_sun_awt_wl_WLDataOffer_openReceivePipe(JNIEnv *env,
                                            jclass clazz,
                                            jlong nativePtr,
                                            jstring mimeJavaString)
{
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

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataOffer_finishDnDImpl(JNIEnv *env, jclass clazz, jlong nativePtr)
{
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    DataOffer_finishDnD(offer);
}

JNIEXPORT void JNICALL
Java_sun_awt_wl_WLDataOffer_setDnDActionsImpl(
        JNIEnv *env, jclass clazz, jlong nativePtr, jint actions, jint preferredAction)
{
    struct DataOffer *offer = jlong_to_ptr(nativePtr);
    DataOffer_setDnDActions(offer, actions, preferredAction);
}
