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

#include "jawcache.h"
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

static jclass cachedComponentAtkComponentClass = NULL;
static jmethodID cachedComponentCreateAtkComponentMethod = NULL;
static jmethodID cachedComponentContainsMethod = NULL;
static jmethodID cachedComponentGetAccessibleAtPointMethod = NULL;
static jmethodID cachedComponentGetExtentsMethod = NULL;
static jmethodID cachedComponentSetExtentsMethod = NULL;
static jmethodID cachedComponentGrabFocusMethod = NULL;
static jmethodID cachedComponentGetLayerMethod = NULL;
static jclass cachedComponentRectangleClass = NULL;
static jfieldID cachedComponentRectangleXField = NULL;
static jfieldID cachedComponentRectangleYField = NULL;
static jfieldID cachedComponentRectangleWidthField = NULL;
static jfieldID cachedComponentRectangleHeightField = NULL;

static GMutex cache_mutex;
static gboolean cache_initialized = FALSE;

static gboolean jaw_component_init_jni_cache(JNIEnv *jniEnv);

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
    JAW_GET_OBJ_IFACE(                                                         \
        component, org_GNOME_Accessibility_AtkInterface_INTERFACE_COMPONENT,   \
        ComponentData, atk_component, jniEnv, atk_component, def_ret)

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

    if (iface == NULL) {
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

    if (ac == NULL) {
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return NULL;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();
    JAW_CHECK_NULL(jniEnv, NULL);

    if (!jaw_component_init_jni_cache(jniEnv)) {
        g_warning("%s: Failed to initialize JNI cache", G_STRFUNC);
        return NULL;
    }

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject jatk_component = (*jniEnv)->CallStaticObjectMethod(
        jniEnv, cachedComponentAtkComponentClass,
        cachedComponentCreateAtkComponentMethod, ac);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jatk_component == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jatk_component using "
                  "create_atk_component method",
                  G_STRFUNC);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    ComponentData *data = g_new0(ComponentData, 1);
    data->atk_component = (*jniEnv)->NewGlobalRef(jniEnv, jatk_component);
    if (data->atk_component == NULL) {
        g_warning("%s: Failed to create global ref for atk_component",
                  G_STRFUNC);
        g_free(data);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    (*jniEnv)->PopLocalFrame(jniEnv, NULL);

    return data;
}

void jaw_component_data_finalize(gpointer p) {
    JAW_DEBUG_ALL("%p", p);

    if (p == NULL) {
        g_debug("%s: Null argument passed to function", G_STRFUNC);
        return;
    }

    ComponentData *data = (ComponentData *)p;
    if (data == NULL) {
        g_warning("%s: data is null after cast", G_STRFUNC);
        return;
    }

    JNIEnv *jniEnv = jaw_util_get_jni_env();

    if (jniEnv == NULL) {
        g_warning("%s: JNIEnv is NULL in finalize", G_STRFUNC);
    } else {
        if (data->atk_component != NULL) {
            (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_component);
            data->atk_component = NULL;
        }
    }

    g_free(data);
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

    if (component == NULL) {
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_COMPONENT(
        component,
        FALSE); // create local JNI reference `jobject atk_component`

    jboolean jcontains = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_component, cachedComponentContainsMethod, (jint)x, (jint)y,
        (jint)coord_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);

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

    if (component == NULL) {
        g_warning("%s: Null argument passed to function ", G_STRFUNC);
        return NULL;
    }

    JAW_GET_COMPONENT(
        component, NULL); // create local JNI reference `jobject atk_component`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(
            jniEnv,
            atk_component);
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return NULL;
    }

    jobject child_ac = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_component, cachedComponentGetAccessibleAtPointMethod,
        (jint)x, (jint)y, (jint)coord_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || child_ac == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to call get_accessible_at_point method",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return NULL;
    }

    JawImpl *jaw_impl = jaw_impl_find_instance(jniEnv, child_ac);

    // From the documentation of the `ref_accessible_at_point`:
    // "The caller of the method takes ownership of the returned data, and is
    // responsible for freeing it." (transfer full annotation), so
    // we have to ref the `jaw_impl`
    if (jaw_impl != NULL) {
        g_object_ref(G_OBJECT(jaw_impl));
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);
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

    if (component == NULL) {
        g_warning("%s: Null component passed to function", G_STRFUNC);
        return;
    }

    if (x != NULL)
        (*x) = -1;
    if (y != NULL)
        (*y) = -1;
    if (width != NULL)
        (*width) = -1;
    if (height != NULL)
        (*height) = -1;

    JAW_GET_COMPONENT(
        component, ); // create local JNI reference `jobject atk_component`

    if ((*jniEnv)->PushLocalFrame(jniEnv, 10) < 0) {
        (*jniEnv)->DeleteLocalRef(
            jniEnv,
            atk_component); // deleting ref that was created in
                            // JAW_GET_COMPONENT
        g_warning("%s: Failed to create a new local reference frame",
                  G_STRFUNC);
        return;
    }

    jobject jrectangle = (*jniEnv)->CallObjectMethod(
        jniEnv, atk_component, cachedComponentGetExtentsMethod,
        (jint)coord_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv) || jrectangle == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to create jrectangle using get_extents method",
                  G_STRFUNC);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);

    if (x != NULL) {
        (*x) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle,
                                            cachedComponentRectangleXField);
    }
    if (y != NULL) {
        (*y) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle,
                                            cachedComponentRectangleYField);
    }
    if (width != NULL) {
        (*width) = (gint)(*jniEnv)->GetIntField(
            jniEnv, jrectangle, cachedComponentRectangleWidthField);
    }
    if (height != NULL) {
        (*height) = (gint)(*jniEnv)->GetIntField(
            jniEnv, jrectangle, cachedComponentRectangleHeightField);
    }

    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->PopLocalFrame(jniEnv, NULL);
        return;
    }

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

    if (component == NULL) {
        g_warning("%s: Null argument passed to function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_COMPONENT(
        component,
        FALSE); // create local JNI reference `jobject atk_component`

    jboolean assigned = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_component, cachedComponentSetExtentsMethod, (jint)x,
        (jint)y, (jint)width, (jint)height, (jint)coord_type);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);

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

    if (component == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return FALSE;
    }

    JAW_GET_COMPONENT(
        component,
        FALSE); // create local JNI reference `jobject atk_component`

    jboolean jresult = (*jniEnv)->CallBooleanMethod(
        jniEnv, atk_component, cachedComponentGrabFocusMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);
        return FALSE;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);

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

    if (component == NULL) {
        g_warning("%s: Null argument passed to the function", G_STRFUNC);
        return 0;
    }

    JAW_GET_COMPONENT(component,
                      0); // create local JNI reference `jobject atk_component`

    jint jlayer = (*jniEnv)->CallIntMethod(jniEnv, atk_component,
                                           cachedComponentGetLayerMethod);
    if ((*jniEnv)->ExceptionCheck(jniEnv)) {
        jaw_jni_clear_exception(jniEnv);
        (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);
        return 0;
    }

    (*jniEnv)->DeleteLocalRef(jniEnv, atk_component);

    return (AtkLayer)jlayer;
}

static gboolean jaw_component_init_jni_cache(JNIEnv *jniEnv) {
    JAW_CHECK_NULL(jniEnv, FALSE);

    g_mutex_lock(&cache_mutex);

    if (cache_initialized) {
        g_mutex_unlock(&cache_mutex);
        return TRUE;
    }

    jclass localClass =
        (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localClass == NULL) {
        jaw_jni_clear_exception(jniEnv);
        g_warning("%s: Failed to find AtkComponent class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedComponentAtkComponentClass =
        (*jniEnv)->NewGlobalRef(jniEnv, localClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localClass);

    if (cachedComponentAtkComponentClass == NULL) {
        g_warning(
            "%s: Failed to create global reference for AtkComponent class",
            G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedComponentCreateAtkComponentMethod = (*jniEnv)->GetStaticMethodID(
        jniEnv, cachedComponentAtkComponentClass, "create_atk_component",
        "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/"
        "AtkComponent;");

    cachedComponentContainsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedComponentAtkComponentClass, "contains", "(III)Z");

    cachedComponentGetAccessibleAtPointMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedComponentAtkComponentClass, "get_accessible_at_point",
        "(III)Ljavax/accessibility/AccessibleContext;");

    cachedComponentGetExtentsMethod =
        (*jniEnv)->GetMethodID(jniEnv, cachedComponentAtkComponentClass,
                               "get_extents", "(I)Ljava/awt/Rectangle;");

    cachedComponentSetExtentsMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedComponentAtkComponentClass, "set_extents", "(IIIII)Z");

    cachedComponentGrabFocusMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedComponentAtkComponentClass, "grab_focus", "()Z");

    cachedComponentGetLayerMethod = (*jniEnv)->GetMethodID(
        jniEnv, cachedComponentAtkComponentClass, "get_layer", "()I");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedComponentCreateAtkComponentMethod == NULL ||
        cachedComponentContainsMethod == NULL ||
        cachedComponentGetAccessibleAtPointMethod == NULL ||
        cachedComponentGetExtentsMethod == NULL ||
        cachedComponentSetExtentsMethod == NULL ||
        cachedComponentGrabFocusMethod == NULL ||
        cachedComponentGetLayerMethod == NULL) {
        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more AtkComponent method IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    jclass localRectangleClass =
        (*jniEnv)->FindClass(jniEnv, "java/awt/Rectangle");
    if ((*jniEnv)->ExceptionCheck(jniEnv) || localRectangleClass == NULL) {
        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to find Rectangle class", G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedComponentRectangleClass =
        (*jniEnv)->NewGlobalRef(jniEnv, localRectangleClass);
    (*jniEnv)->DeleteLocalRef(jniEnv, localRectangleClass);

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedComponentRectangleClass == NULL) {
        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to create global reference for Rectangle class",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cachedComponentRectangleXField =
        (*jniEnv)->GetFieldID(jniEnv, cachedComponentRectangleClass, "x", "I");
    cachedComponentRectangleYField =
        (*jniEnv)->GetFieldID(jniEnv, cachedComponentRectangleClass, "y", "I");
    cachedComponentRectangleWidthField = (*jniEnv)->GetFieldID(
        jniEnv, cachedComponentRectangleClass, "width", "I");
    cachedComponentRectangleHeightField = (*jniEnv)->GetFieldID(
        jniEnv, cachedComponentRectangleClass, "height", "I");

    if ((*jniEnv)->ExceptionCheck(jniEnv) ||
        cachedComponentRectangleXField == NULL ||
        cachedComponentRectangleYField == NULL ||
        cachedComponentRectangleWidthField == NULL ||
        cachedComponentRectangleHeightField == NULL) {
        jaw_jni_clear_exception(jniEnv);

        g_warning("%s: Failed to cache one or more Rectangle field IDs",
                  G_STRFUNC);
        goto cleanup_and_fail;
    }

    cache_initialized = TRUE;
    g_mutex_unlock(&cache_mutex);

    g_debug("%s: classes and methods cached successfully", G_STRFUNC);

    return TRUE;

cleanup_and_fail:
    if (cachedComponentAtkComponentClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedComponentAtkComponentClass);
        cachedComponentAtkComponentClass = NULL;
    }
    if (cachedComponentRectangleClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedComponentRectangleClass);
        cachedComponentRectangleClass = NULL;
    }
    cachedComponentCreateAtkComponentMethod = NULL;
    cachedComponentContainsMethod = NULL;
    cachedComponentGetAccessibleAtPointMethod = NULL;
    cachedComponentGetExtentsMethod = NULL;
    cachedComponentSetExtentsMethod = NULL;
    cachedComponentGrabFocusMethod = NULL;
    cachedComponentGetLayerMethod = NULL;
    cachedComponentRectangleXField = NULL;
    cachedComponentRectangleYField = NULL;
    cachedComponentRectangleWidthField = NULL;
    cachedComponentRectangleHeightField = NULL;

    g_mutex_unlock(&cache_mutex);
    return FALSE;
}

void jaw_component_cache_cleanup(JNIEnv *jniEnv) {
    if (jniEnv == NULL) {
        return;
    }

    g_mutex_lock(&cache_mutex);

    if (cachedComponentAtkComponentClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedComponentAtkComponentClass);
        cachedComponentAtkComponentClass = NULL;
    }
    if (cachedComponentRectangleClass != NULL) {
        (*jniEnv)->DeleteGlobalRef(jniEnv, cachedComponentRectangleClass);
        cachedComponentRectangleClass = NULL;
    }
    cachedComponentCreateAtkComponentMethod = NULL;
    cachedComponentContainsMethod = NULL;
    cachedComponentGetAccessibleAtPointMethod = NULL;
    cachedComponentGetExtentsMethod = NULL;
    cachedComponentSetExtentsMethod = NULL;
    cachedComponentGrabFocusMethod = NULL;
    cachedComponentGetLayerMethod = NULL;
    cachedComponentRectangleXField = NULL;
    cachedComponentRectangleYField = NULL;
    cachedComponentRectangleWidthField = NULL;
    cachedComponentRectangleHeightField = NULL;
    cache_initialized = FALSE;

    g_mutex_unlock(&cache_mutex);
}
