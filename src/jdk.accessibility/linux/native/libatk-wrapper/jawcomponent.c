/*
 * Java ATK Wrapper for GNOME
 * Copyright (C) 2009 Sun Microsystems Inc.
 * Copyright (C) 2015 Magdalen Berns <m.berns@thismagpie.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301 USA
 */

#include "jawimpl.h"
#include "jawutil.h"
#include <atk/atk.h>
#include <glib-object.h>
#include <glib.h>

static gboolean jaw_component_contains(AtkComponent *component, gint x, gint y,
                                       AtkCoordType coord_type);

static AtkObject *
jaw_component_ref_accessible_at_point(AtkComponent *component, gint x, gint y,
                                      AtkCoordType coord_type);

static void jaw_component_get_extents(AtkComponent *component, gint *x, gint *y,
                                      gint *width, gint *height,
                                      AtkCoordType coord_type);

static gboolean jaw_component_set_extents(AtkComponent *component, gint x,
                                          gint y, gint width, gint height,
                                          AtkCoordType coord_type);

static gboolean jaw_component_grab_focus(AtkComponent *component);
static AtkLayer jaw_component_get_layer(AtkComponent *component);
/*static gin jaw_component_get_mdi_zorder(AtkComponent		*component); */

typedef struct _ComponentData {
    jobject atk_component;
} ComponentData;

#define JAW_GET_COMPONENT(component, def_ret)                                  \
    JAW_GET_OBJ_IFACE(component, INTERFACE_COMPONENT, ComponentData,           \
                      atk_component, jniEnv, atk_component, def_ret)

/*
 * Atk.Component Methods
 * contains (x, y, coord_type)
 * get_alpha ()
 * get_extents (coord_type)
 * get_layer ()
 * get_mdi_zorder ()
 * get_position (coord_type)
 * get_size ()
 * grab_focus ()
 * ref_accessible_at_point (x, y, coord_type)
 * remove_focus_handler (handler_id)
 * scroll_to (type)
 * scroll_to_point (coords, x, y)
 * set_extents (x, y, width, height, coord_type)
 * set_position (x, y, coord_type)
 * set_size (width, height)
 */
void jaw_component_interface_init(AtkComponentIface *iface, gpointer data) {
    JAW_DEBUG_ALL("%p,%p", iface, data);

    if (!iface) {
        g_warning("Null argument passed to function");
        return;
    }

    // deprecated: iface->add_focus_handler
    iface->contains = jaw_component_contains;
    iface->ref_accessible_at_point = jaw_component_ref_accessible_at_point;
    iface->get_extents = jaw_component_get_extents;
    // done by atk: iface->get_position
    // done by atk: iface->get_size
    iface->grab_focus = jaw_component_grab_focus;
    // deprecated: iface->remove_focus_handler
    iface->set_extents = jaw_component_set_extents;
    // TODO: iface->set_position similar to set_extents
    // TODO: iface->set_size similar to set_extents
    iface->get_layer = jaw_component_get_layer;
    iface->get_mdi_zorder = NULL; /* TODO: jaw_component_get_mdi_zorder;*/
    // TODO: missing java support for iface->get_alpha
    // TODO: missing java support for iface->scroll_to
    // TODO: missing java support for iface->scroll_to_point
}

gpointer jaw_component_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    ComponentData *data = g_new0(ComponentData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, NULL);

    jclass classComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    CHECK_NULL(classComponent, NULL);
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classComponent, "create_atk_component",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkComponent;");
    CHECK_NULL(jmid, NULL);
    jobject jatk_component =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classComponent, jmid, ac);
    CHECK_NULL(jatk_component, NULL);
    data->atk_component = (*jniEnv)->NewGlobalRef(jniEnv, jatk_component);

    return data;
}

void jaw_component_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("Null argument passed to function");
        return;
    }

    ComponentData *data = (ComponentData *)p;

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    CHECK_NULL(jniEnv, );

    if (data->atk_component) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_component);
        data->atk_component = NULL;
    }
}

static gboolean jaw_component_contains(AtkComponent *component, gint x, gint y,
                                       AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %d, %d, %d", component, x, y, coord_type);

    if (!component) {
        g_warning("Null argument passed to function");
        return FALSE;
    }

    JAW_GET_COMPONENT(component, FALSE);

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkComponent, "contains", "(III)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return FALSE;
    }
    jboolean jcontains = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_component, jmid, (jint)x, (jint)y, (jint)coord_type);
    // deleting ref that was created in JAW_GET_COMPONENT
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    CHECK_NULL(jcontains, FALSE);

    return jcontains;
}

static AtkObject *
jaw_component_ref_accessible_at_point(AtkComponent *component, gint x, gint y,
                                      AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %d, %d, %d", component, x, y, coord_type);

    if (!component) {
        g_warning("Null argument passed to function");
        return NULL;
    }

    JAW_GET_COMPONENT(component, NULL);

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkComponent, "get_accessible_at_point",
        "(III)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return NULL;
    }
    jobject child_ac = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_component, jmid, (jint)x, (jint)y, (jint)coord_type);
    // deleting ref that was created in JAW_GET_COMPONENT
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    CHECK_NULL(child_ac, NULL);

    JawImpl *jaw_impl = jaw_impl_get_instance_from_jaw(jniEnv, child_ac);

    if (jaw_impl)
        g_object_ref(G_OBJECT(jaw_impl));

    return ATK_OBJECT(jaw_impl);
}

static void jaw_component_get_extents(AtkComponent *component, gint *x, gint *y,
                                      gint *width, gint *height,
                                      AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %p, %p, %p, %p, %d", component, x, y, width, height,
                coord_type);

    if (!component || !x || !y || !width || !height) {
        g_warning("Null argument passed to function");
        return;
    }

    (*x) = -1;
    (*y) = -1;
    (*width) = -1;
    (*height) = -1;

    JAW_GET_COMPONENT(component, );

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkComponent, "get_extents", "(I)Ljava/awt/Rectangle;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return;
    }
    jobject jrectangle = (*jniEnv)->CallObjectMethod(jniEnv, atk_component,
                                                     jmid, (jint)coord_type);
    // deleting ref that was created in JAW_GET_COMPONENT
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    CHECK_NULL(jrectangle, );

    if (jrectangle == NULL) {
        JAW_DEBUG_I("jrectangle == NULL");
        return;
    }

    jclass classRectangle = (*jniEnv)->FindClass(jniEnv, "java/awt/Rectangle");
    CHECK_NULL(classRectangle, );
    jfieldID jfidX = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "x", "I");
    CHECK_NULL(jfidX, );
    jfieldID jfidY = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "y", "I");
    CHECK_NULL(jfidY, );
    jfieldID jfidW =
        (*jniEnv)->GetFieldID(jniEnv, classRectangle, "width", "I");
    CHECK_NULL(jfidW, );
    jfieldID jfidH =
        (*jniEnv)->GetFieldID(jniEnv, classRectangle, "height", "I");
    CHECK_NULL(jfidH, );
    (*x) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidX);
    (*y) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidY);
    (*width) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidW);
    (*height) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidH);
}

static gboolean jaw_component_set_extents(AtkComponent *component, gint x,
                                          gint y, gint width, gint height,
                                          AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %d, %d, %d, %d, %d", component, x, y, width, height,
                coord_type);

    if (!component) {
        g_warning("Null argument passed to function");
        return FALSE;
    }

    JAW_GET_COMPONENT(component, FALSE);

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkComponent,
                                            "set_extents", "(IIIII)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return FALSE;
    }
    jboolean assigned = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_component, jmid, (jint)x, (jint)y, (jint)width,
        (jint)height, (jint)coord_type);
    // deleting ref that was created in JAW_GET_COMPONENT
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    CHECK_NULL(assigned, FALSE);
    return assigned;
}

static gboolean jaw_component_grab_focus(AtkComponent *component) {
    JAW_DEBUG_C("%p", component);

    if (!component) {
        g_warning("Null argument passed to function");
        return FALSE;
    }

    JAW_GET_COMPONENT(component, FALSE);

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkComponent, "grab_focus", "()Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return FALSE;
    }
    jboolean jresult =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_component, jmid);
    // deleting ref that was created in JAW_GET_COMPONENT
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    CHECK_NULL(jresult, FALSE);
    return jresult;
}

static AtkLayer jaw_component_get_layer(AtkComponent *component) {
    JAW_DEBUG_C("%p", component);

    if (!component) {
        g_warning("Null argument passed to function");
        return 0;
    }

    JAW_GET_COMPONENT(component, 0);

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkComponent, "get_layer", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        return FALSE;
    }
    jint jlayer = (*jniEnv)->CallIntMethod(jniEnv, atk_component, jmid);
    // deleting ref that was created in JAW_GET_COMPONENT
    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    CHECK_NULL(jlayer, 0);

    return (AtkLayer)jlayer;
}
