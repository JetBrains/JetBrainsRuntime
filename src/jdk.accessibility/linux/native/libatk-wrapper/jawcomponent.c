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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

#include <atk/atk.h>
#include <glib.h>
#include <glib-object.h>
#include "jawimpl.h"
#include "jawutil.h"

static gboolean jaw_component_contains(AtkComponent *component,
                                       gint         x,
                                       gint         y,
                                       AtkCoordType coord_type);

static AtkObject* jaw_component_ref_accessible_at_point(AtkComponent *component,
                                                        gint         x,
                                                        gint         y,
                                                        AtkCoordType coord_type);

static void jaw_component_get_extents(AtkComponent *component,
                                      gint         *x,
                                      gint         *y,
                                      gint         *width,
                                      gint         *height,
                                      AtkCoordType coord_type);

static gboolean jaw_component_set_extents(AtkComponent *component,
                                          gint         x,
                                          gint         y,
                                          gint         width,
                                          gint         height,
                                          AtkCoordType coord_type);

static gboolean jaw_component_grab_focus(AtkComponent *component);
static AtkLayer jaw_component_get_layer(AtkComponent *component);
/*static gin jaw_component_get_mdi_zorder(AtkComponent		*component); */

typedef struct _ComponentData {
  jobject atk_component;
} ComponentData;

#define JAW_GET_COMPONENT(component, def_ret) \
  JAW_GET_OBJ_IFACE(component, INTERFACE_COMPONENT, ComponentData, atk_component, jniEnv, atk_component, def_ret)

void
jaw_component_interface_init (AtkComponentIface *iface, gpointer data)
{
  JAW_DEBUG_ALL("%p,%p", iface, data);
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

gpointer
jaw_component_data_init (jobject ac)
{
  JAW_DEBUG_ALL("%p", ac);
  ComponentData *data = g_new0(ComponentData, 1);

  JNIEnv *jniEnv = jaw_util_get_jni_env();
  jclass classComponent = (*jniEnv)->FindClass(jniEnv,
                                               "org/GNOME/Accessibility/AtkComponent");
  jmethodID jmid = (*jniEnv)->GetStaticMethodID(jniEnv,
                                          classComponent,
                                          "createAtkComponent",
                                          "(Ljavax/accessibility/AccessibleContext;)Lorg/GNOME/Accessibility/AtkComponent;");

  jobject jatk_component = (*jniEnv)->CallStaticObjectMethod(jniEnv, classComponent, jmid, ac);
  data->atk_component = (*jniEnv)->NewGlobalRef(jniEnv, jatk_component);

  return data;
}

void
jaw_component_data_finalize (gpointer p)
{
  JAW_DEBUG_ALL("%p", p);
  ComponentData *data = (ComponentData*)p;
  JNIEnv *jniEnv = jaw_util_get_jni_env();

  if (data && data->atk_component)
  {
    (*jniEnv)->DeleteGlobalRef(jniEnv, data->atk_component);
    data->atk_component = NULL;
  }
}

static gboolean
jaw_component_contains (AtkComponent *component, gint x, gint y, AtkCoordType coord_type)
{
  JAW_DEBUG_C("%p, %d, %d, %d", component, x, y, coord_type);
  JAW_GET_COMPONENT(component, FALSE);

  jclass classAtkComponent = (*jniEnv)->FindClass(jniEnv,
                                                  "org/GNOME/Accessibility/AtkComponent");

  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkComponent,
                                          "contains",
                                          "(III)Z");

  jboolean jcontains = (*jniEnv)->CallBooleanMethod(jniEnv,
                                                    atk_component,
                                                    jmid,
                                                    (jint)x,
                                                    (jint)y,
                                                    (jint)coord_type);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);

  return jcontains;
}

static AtkObject*
jaw_component_ref_accessible_at_point (AtkComponent *component, gint x, gint y, AtkCoordType coord_type)
{
  JAW_DEBUG_C("%p, %d, %d, %d", component, x, y, coord_type);
  JAW_GET_COMPONENT(component, NULL);

  jclass classAtkComponent = (*jniEnv)->FindClass(jniEnv,
                                                  "org/GNOME/Accessibility/AtkComponent");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkComponent,
                                          "get_accessible_at_point",
                                          "(III)Ljavax/accessibility/AccessibleContext;");
  jobject child_ac = (*jniEnv)->CallObjectMethod(jniEnv,
                                                 atk_component,
                                                 jmid,
                                                 (jint)x,
                                                 (jint)y,
                                                 (jint)coord_type);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);

  JawImpl* jaw_impl = jaw_impl_get_instance_from_jaw( jniEnv, child_ac );

  if (jaw_impl)
    g_object_ref( G_OBJECT(jaw_impl) );

  return ATK_OBJECT(jaw_impl);
}

static void
jaw_component_get_extents (AtkComponent *component,
                           gint         *x,
                           gint         *y,
                           gint         *width,
                           gint         *height,
                           AtkCoordType coord_type)
{
  JAW_DEBUG_C("%p, %p, %p, %p, %p, %d", component, x, y, width, height, coord_type);
  if (x == NULL || y == NULL || width == NULL || height == NULL)
    return;

  (*x) = -1;
  (*y) = -1;
  (*width) = -1;
  (*height) = -1;

  if (component == NULL)
    return;

  JAW_GET_COMPONENT(component, );

  jclass classAtkComponent = (*jniEnv)->FindClass(jniEnv,
                                                  "org/GNOME/Accessibility/AtkComponent");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkComponent,
                                          "get_extents",
                                          "(I)Ljava/awt/Rectangle;");

  jobject jrectangle = (*jniEnv)->CallObjectMethod(jniEnv, atk_component, jmid, (jint) coord_type);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);

  if (jrectangle == NULL)
  {
    JAW_DEBUG_I("jrectangle == NULL");
    return;
  }

  jclass classRectangle = (*jniEnv)->FindClass(jniEnv, "java/awt/Rectangle");
  jfieldID jfidX = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "x", "I");
  jfieldID jfidY = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "y", "I");
  jfieldID jfidW = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "width", "I");
  jfieldID jfidH = (*jniEnv)->GetFieldID(jniEnv, classRectangle, "height", "I");
  (*x)      = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidX);
  (*y)      = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidY);
  (*width)  = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidW);
  (*height) = (gint)(*jniEnv)->GetIntField(jniEnv, jrectangle, jfidH);
}

static gboolean
jaw_component_set_extents (AtkComponent *component,
                           gint         x,
                           gint         y,
                           gint         width,
                           gint         height,
                           AtkCoordType coord_type)
{
  JAW_DEBUG_C("%p, %d, %d, %d, %d, %d", component, x, y, width, height, coord_type);
  JAW_GET_COMPONENT(component, FALSE);

  jclass classAtkComponent = (*jniEnv)->FindClass(jniEnv, "org/GNOME/Accessibility/AtkComponent");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv, classAtkComponent, "set_extents", "(IIIII)Z");
  jboolean assigned = (*jniEnv)->CallBooleanMethod(jniEnv, atk_component, jmid, (jint)x, (jint)y, (jint)width, (jint)height, (jint)coord_type);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
  return assigned;
}

static gboolean
jaw_component_grab_focus (AtkComponent *component)
{
  JAW_DEBUG_C("%p", component);
  JAW_GET_COMPONENT(component, FALSE);

  jclass classAtkComponent = (*jniEnv)->FindClass(jniEnv,
                                                  "org/GNOME/Accessibility/AtkComponent");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkComponent,
                                          "grab_focus",
                                          "()Z");
  jboolean jresult = (*jniEnv)->CallBooleanMethod(jniEnv, atk_component, jmid);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);
  return jresult;
}

static AtkLayer
jaw_component_get_layer (AtkComponent *component)
{
  JAW_DEBUG_C("%p", component);
  JAW_GET_COMPONENT(component, 0);

  jclass classAtkComponent = (*jniEnv)->FindClass(jniEnv,
                                                  "org/GNOME/Accessibility/AtkComponent");
  jmethodID jmid = (*jniEnv)->GetMethodID(jniEnv,
                                          classAtkComponent,
                                          "get_layer",
                                          "()I");

  jint jlayer = (*jniEnv)->CallIntMethod(jniEnv, atk_component, jmid);
  (*jniEnv)->DeleteGlobalRef(jniEnv, atk_component);

  return (AtkLayer)jlayer;
}
