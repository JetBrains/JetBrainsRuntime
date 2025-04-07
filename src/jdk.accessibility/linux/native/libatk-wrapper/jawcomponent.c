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

/**
 * (From Atk documentation)
 *
 * AtkComponent:
 *
 * The ATK interface provided by UI components
 * which occupy a physical area on the screen.
 * which the user can activate/interact with.
 *
 * #AtkComponent should be implemented by most if not all UI elements
 * with an actual on-screen presence, i.e. components which can be
 * said to have a screen-coordinate bounding box.  Virtually all
 * widgets will need to have #AtkComponent implementations provided
 * for their corresponding #AtkObject class.  In short, only UI
 * elements which are *not* GUI elements will omit this ATK interface.
 *
 * A possible exception might be textual information with a
 * transparent background, in which case text glyph bounding box
 * information is provided by #AtkText.
 */

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
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return;
    }

    iface->contains = jaw_component_contains;
    iface->get_alpha = NULL; // missing java support for iface->get_alpha
    iface->get_layer = jaw_component_get_layer;
    iface->get_extents = jaw_component_get_extents;
    iface->get_mdi_zorder = NULL; // TODO: jaw_component_get_mdi_zorder;
    // done by atk: iface->get_position (atk_component_real_get_position)
    // done by atk: iface->get_size (atk_component_real_get_size)
    iface->grab_focus = jaw_component_grab_focus;
    iface->ref_accessible_at_point = jaw_component_ref_accessible_at_point;
    iface->remove_focus_handler =
        NULL;                // deprecated: iface->remove_focus_handler
    iface->scroll_to = NULL; // missing java support for iface->scroll_to
    iface->scroll_to_point =
        NULL; // missing java support for iface->scroll_to_point
    iface->set_extents = jaw_component_set_extents;
    iface->set_position =
        NULL;               // TODO: iface->set_position similar to set_extents
    iface->set_size = NULL; // TODO: iface->set_size similar to set_extents
}

gpointer jaw_component_data_init(jobject ac) {
    JAW_DEBUG_ALL("%p", ac);

    if (!ac) {
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return NULL;
    }

    ComponentData *data = g_new0(ComponentData, 1);

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);
    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classComponent) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetStaticMethodID(
        jniEnv, classComponent, "create_atk_component",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkComponent;");
    if (!jmid) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject jatk_component =
        (*jniEnv)->CallStaticObjectMethod(jniEnv, classComponent, jmid, ac);
    if (!jatk_component) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    data->atk_component = (*jniEnv)->NewGlobalRef(jniEnv, jatk_component);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_component_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (!p) {
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return;
    }

    ComponentData *data = (ComponentData *)p;

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, );

    if (data->atk_component) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_component);
        data->atk_component = NULL;
    }
}

/**
 * jaw_component_contains:
 * @component: the #AtkComponent
 * @x: x coordinate
 * @y: y coordinate
 * @coord_type: specifies whether the coordinates are relative to the screen
 * or to the components top level window
 *
 * Checks whether the specified point is within the extent of the @component.
 *
 * Toolkit implementor note: ATK provides a default implementation for
 * this virtual method. In general there are little reason to
 * re-implement it.
 *
 * Returns: %TRUE or %FALSE indicating whether the specified point is within
 * the extent of the @component or not
 **/
static gboolean jaw_component_contains(AtkComponent *component, gint x, gint y,
                                       AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %d, %d, %d", component, x, y, coord_type);

    if (!component) {
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_COMPONENT(component, FALSE);
    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_component); // deleting ref that was created in
                            // JAW_GET_COMPONENT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkComponent, "contains", "(III)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jcontains = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_component, jmid, (jint)x, (jint)y, (jint)coord_type);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jcontains;
}

/**
 * jaw_component_ref_accessible_at_point:
 * @component: the #AtkComponent
 * @x: x coordinate
 * @y: y coordinate
 * @coord_type: specifies whether the coordinates are relative to the screen
 * or to the components top level window
 *
 * Gets a reference to the accessible child, if one exists, at the
 * coordinate point specified by @x and @y.
 *
 * Returns: (nullable) (transfer full): a reference to the accessible
 * child, if one exists
 **/
static AtkObject *
jaw_component_ref_accessible_at_point(AtkComponent *component, gint x, gint y,
                                      AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %d, %d, %d", component, x, y, coord_type);

    if (!component) {
        g_warning("%s: Null argument passed to function ", G_STRFUNC);
        return NULL;
    }

    JAW_GET_COMPONENT(component, NULL);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_component); // deleting ref that was created in
                            // JAW_GET_COMPONENT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkComponent, "get_accessible_at_point",
        "(III)Ljavax/accessibility/AccessibleContext;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }
    jobject child_ac = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_component, jmid, (jint)x, (jint)y, (jint)coord_type);
    if (!child_ac) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, child_ac);

    // From the documentation of the `ref_accessible_at_point`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full annotation), so
    // we have to ref the `jaw_impl`
    if (jaw_impl) {
        g_object_ref(G_OBJECT(jaw_impl));
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return ATK_OBJECT(jaw_impl);
}

/**
 * jaw_component_get_extents:
 * @component: an #AtkComponent
 * @x: (out) (optional): address of #gint to put x coordinate
 * @y: (out) (optional): address of #gint to put y coordinate
 * @width: (out) (optional): address of #gint to put width
 * @height: (out) (optional): address of #gint to put height
 * @coord_type: specifies whether the coordinates are relative to the screen
 * or to the components top level window
 *
 * Gets the rectangle which gives the extent of the @component.
 *
 * If the extent can not be obtained (e.g. a non-embedded plug or missing
 * support), all of x, y, width, height are set to -1.
 *
 **/
static void jaw_component_get_extents(AtkComponent *component, gint *x, gint *y,
                                      gint *width, gint *height,
                                      AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %p, %p, %p, %p, %d", component, x, y, width, height,
                coord_type);

    if (!component || !x || !y || !width || !height) {
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return;
    }

    (*x) = -1;
    (*y) = -1;
    (*width) = -1;
    (*height) = -1;

    JAW_GET_COMPONENT(component, );

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_component); // deleting ref that was created in
                            // JAW_GET_COMPONENT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(
        jniEnv, classAtkComponent, "get_extents", "(I)Ljava/awt/Rectangle;");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jobject jrectangle = (*jniEnv)->CallObjectMethod(jniEnv, atk_component,
                                                     jmid, (jint)coord_type);
    if (!jrectangle) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        JAW_DEBUG_I("jrectangle == NULL");
        return;
    }

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);

    jclass classRectangle = (*jniEnv)->FindClass(jniEnv, "java/awt/Rectangle");
    if (!classRectangle) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidX = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "x", "I");
    if (!jfidX) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidY = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "y", "I");
    if (!jfidY) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }
    jfieldID jfidW =
        (*jniEnv)->GetFieldID(jniEnv, classRectangle, "width", "I");
    if (!jfidW) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    };
    jfieldID jfidH =
        (*jniEnv)->GetFieldID(jniEnv, classRectangle, "height", "I");
    if (!jfidH) {
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*x) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidX);
    (*y) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidY);
    (*width) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidW);
    (*height) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidH);

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);
}

/**
 * jaw_component_set_extents:
 * @component: an #AtkComponent
 * @x: x coordinate
 * @y: y coordinate
 * @width: width to set for @component
 * @height: height to set for @component
 * @coord_type: specifies whether the coordinates are relative to the screen
 * or to the components top level window
 *
 * Sets the extents of @component.
 *
 * Returns: %TRUE or %FALSE whether the extents were set or not
 **/
static gboolean jaw_component_set_extents(AtkComponent *component, gint x,
                                          gint y, gint width, gint height,
                                          AtkCoordType coord_type) {
    JAW_DEBUG_C("%p, %d, %d, %d, %d, %d", component, x, y, width, height,
                coord_type);

    if (!component) {
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_COMPONENT(component, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_component); // deleting ref that was created in
                            // JAW_GET_COMPONENT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkComponent,
                                            "set_extents", "(IIIII)Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean assigned = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_component, jmid, (jint)x, (jint)y, (jint)width,
        (jint)height, (jint)coord_type);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return assigned;
}

/**
 * jaw_component_grab_focus:
 * @component: an #AtkComponent
 *
 * Grabs focus for this @component.
 *
 * Returns: %TRUE if successful, %FALSE otherwise.
 **/
static gboolean jaw_component_grab_focus(AtkComponent *component) {
    JAW_DEBUG_C("%p", component);

    if (!component) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_COMPONENT(component, FALSE);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_component); // deleting ref that was created in
                            // JAW_GET_COMPONENT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return FALSE;
    }

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkComponent, "grab_focus", "()Z");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return FALSE;
    }
    jboolean jresult =
        (*jniEnv)->CallBooleanMethod(jniEnv, atk_component, jmid);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return jresult;
}

/**
 * jaw_component_get_layer:
 * @component: an #AtkComponent
 *
 * Gets the layer of the component.
 *
 * Returns: an #AtkLayer which is the layer of the component, 0 if an error
 *happened.
 **/
static AtkLayer jaw_component_get_layer(AtkComponent *component) {
    JAW_DEBUG_C("%p", component);

    if (!component) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_COMPONENT(component, 0);

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteGlobalRef(
            jniEnv,
            atk_component); // deleting ref that was created in
                            // JAW_GET_COMPONENT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return 0;
    }

    jclass classAtkComponent =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if (!classAtkComponent) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jmethodID jmid =
        (*jniEnv)->GetMethodID(jniEnv, classAtkComponent, "get_layer", "()I");
    if (!jmid) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return 0;
    }
    jint jlayer = (*jniEnv)->CallIntMethod(jniEnv, atk_component, jmid);

    (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return (AtkLayer)jlayer;
}
