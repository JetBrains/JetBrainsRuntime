/*
 * Copyright (c) 2018, Red Hat, Inc.
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

package org.GNOME.Accessibility;

import javax.accessibility.*;
import java.util.Locale;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.EventQueue;

/**
 * AtkObject:
 * That class is used to wrap AccessibleContext Java object
 * to avoid the concurrency of AWT objects.
 *
 * @autor Giuseppe Capaldo
 */
public class AtkObject {

    public static final int INTERFACE_ACTION = 0x00000001;
    public static final int INTERFACE_COMPONENT = 0x00000002;
    public static final int INTERFACE_DOCUMENT = 0x00000004;
    public static final int INTERFACE_EDITABLE_TEXT = 0x00000008;
    public static final int INTERFACE_HYPERLINK = 0x00000010;
    public static final int INTERFACE_HYPERTEXT = 0x00000020;
    public static final int INTERFACE_IMAGE = 0x00000040;
    public static final int INTERFACE_SELECTION = 0x00000080;
    public static final int INTERFACE_STREAMABLE_CONTENT = 0x00000100;
    public static final int INTERFACE_TABLE = 0x00000200;
    public static final int INTERFACE_TABLE_CELL = 0x00000400;
    public static final int INTERFACE_TEXT = 0x00000800;
    public static final int INTERFACE_VALUE = 0x00001000;

    private AtkObject() {}

    /**
     * Returns the JMenuItem accelerator. Similar implementation is used on
     * macOS, see CAccessibility.getAcceleratorText(AccessibleContext) in OpenJDK, and
     * on Windows, see AccessBridge.getAccelerator(AccessibleContext) in OpenJDK.
     */
    private static String getAcceleratorText(AccessibleContext ac) {
        assert(EventQueue.isDispatchThread());

        String accText = "";
        Accessible parent = ac.getAccessibleParent();
        if (parent != null) {
            int indexInParent = ac.getAccessibleIndexInParent();
            Accessible child = parent.getAccessibleContext()
                    .getAccessibleChild(indexInParent);
            if (child instanceof JMenuItem menuItem) {
                KeyStroke keyStroke = menuItem.getAccelerator();
                if (keyStroke != null) {
                    int modifiers = keyStroke.getModifiers();
                    String modifiersText = modifiers > 0 ? InputEvent.getModifiersExText(modifiers) : "";

                    int keyCode = keyStroke.getKeyCode();
                    String keyCodeText = keyCode != 0 ? KeyEvent.getKeyText(keyCode) : String.valueOf(keyStroke.getKeyChar());

                    accText += modifiersText;
                    if (!modifiersText.isEmpty() && !keyCodeText.isEmpty()) {
                        accText += "+";
                    }
                    accText += keyCodeText;
                }
            }
        }
        return accText;
    }

    // JNI upcalls section

    private static int get_tflag_from_obj(Object o) {
        return AtkUtil.invokeInSwing(() -> {
            int flags = 0;
            AccessibleContext ac;

            if (o instanceof AccessibleContext accessibleContext)
                ac = accessibleContext;
            else if (o instanceof Accessible accessible)
                ac = accessible.getAccessibleContext();
            else
                return flags;

            if (ac.getAccessibleAction() != null)
                flags |= AtkObject.INTERFACE_ACTION;
            if (ac.getAccessibleComponent() != null)
                flags |= AtkObject.INTERFACE_COMPONENT;
            AccessibleText text = ac.getAccessibleText();
            if (text != null) {
                flags |= AtkObject.INTERFACE_TEXT;
                if (text instanceof AccessibleHypertext)
                    flags |= AtkObject.INTERFACE_HYPERTEXT;
                if (ac.getAccessibleEditableText() != null)
                    flags |= AtkObject.INTERFACE_EDITABLE_TEXT;
            }
            if (ac.getAccessibleIcon() != null)
                flags |= AtkObject.INTERFACE_IMAGE;
            if (ac.getAccessibleSelection() != null)
                flags |= AtkObject.INTERFACE_SELECTION;
            AccessibleTable table = ac.getAccessibleTable();
            if (table != null) {
                flags |= AtkObject.INTERFACE_TABLE;
            }
            Accessible parent = ac.getAccessibleParent();
            if (parent != null) {
                AccessibleContext pc = parent.getAccessibleContext();
                if (pc != null) {
                    table = pc.getAccessibleTable();
                    // Unfortunately without the AccessibleExtendedTable interface
                    // we can't determine the column/row of this accessible in the
                    // table
                    if (table != null && table instanceof AccessibleExtendedTable) {
                        flags |= AtkObject.INTERFACE_TABLE_CELL;
                    }
                }
            }
            if (ac.getAccessibleValue() != null)
                flags |= AtkObject.INTERFACE_VALUE;
            return flags;
        }, 0);
    }

    private static AccessibleContext get_accessible_parent(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            Accessible father = ac.getAccessibleParent();
            if (father != null)
                return father.getAccessibleContext();
            else
                return null;
        }, null);
    }

    private static void set_accessible_parent(AccessibleContext ac, AccessibleContext pa) {
        AtkUtil.invokeInSwing(() -> {
            if (pa instanceof Accessible father) {
                ac.setAccessibleParent(father);
            }
        });
    }

    public static String get_accessible_name(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            String accessibleName = ac.getAccessibleName();
            if (accessibleName == null) {
                return null;
            }
            final String acceleratorText = getAcceleratorText(ac);
            if (!acceleratorText.isEmpty()) {
                return accessibleName + " " + acceleratorText;
            }
            return accessibleName;
        }, "");
    }

    private static void set_accessible_name(AccessibleContext ac, String name) {
        AtkUtil.invokeInSwing(() -> {
            ac.setAccessibleName(name);
        });
    }

    private static String get_accessible_description(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            return ac.getAccessibleDescription();
        }, "");
    }

    private static void set_accessible_description(AccessibleContext ac, String description) {
        AtkUtil.invokeInSwing(() -> {
            ac.setAccessibleDescription(description);
        });
    }

    private static int get_accessible_children_count(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            return ac.getAccessibleChildrenCount();
        }, 0);
    }

    private static int get_accessible_index_in_parent(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            return ac.getAccessibleIndexInParent();
        }, -1);
    }

    private static AccessibleRole get_accessible_role(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            return ac.getAccessibleRole();
        }, AccessibleRole.UNKNOWN);
    }

    private static boolean equals_ignore_case_locale_with_role(AccessibleRole role) {
        String displayString = role.toDisplayString(Locale.US);
        return displayString.equalsIgnoreCase("paragraph");
    }

    private static AccessibleState[] get_array_accessible_state(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            AccessibleStateSet stateSet = ac.getAccessibleStateSet();
            if (stateSet == null)
                return null;
            else
                return stateSet.toArray();
        }, null);
    }

    private static String get_locale(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            Locale l = ac.getLocale();
            String locale = l.getLanguage();
            String country = l.getCountry();
            String script = l.getScript();
            String variant = l.getVariant();
            if (country.length() != 0) {
                locale += "_" + country;
            }
            if (script.length() != 0) {
                locale += "@" + script;
            }
            if (variant.length() != 0) {
                locale += "@" + variant;
            }
            return locale;
        }, null);
    }

    private record WrapKeyAndTarget(String key, AccessibleContext[] relations) {}

    private static WrapKeyAndTarget[] get_array_accessible_relation(AccessibleContext ac) {
        WrapKeyAndTarget[] d = new WrapKeyAndTarget[0];
        return AtkUtil.invokeInSwing(() -> {
            AccessibleRelationSet relationSet = ac.getAccessibleRelationSet();
            if (relationSet == null)
                return d;
            else {
                AccessibleRelation[] array = relationSet.toArray();
                WrapKeyAndTarget[] result = new WrapKeyAndTarget[array.length];
                for (int i = 0; i < array.length; i++) {
                    String key = array[i].getKey();
                    Object[] objs = array[i].getTarget();
                    AccessibleContext[] contexts = new AccessibleContext[objs.length];
                    for (int j = 0; j < objs.length; j++) {
                        if (objs[j] instanceof Accessible accessible)
                            contexts[j] = accessible.getAccessibleContext();
                        else
                            contexts[j] = null;
                    }
                    result[i] = new WrapKeyAndTarget(key, contexts);
                }
                return result;
            }
        }, d);
    }

    private static AccessibleContext get_accessible_child(AccessibleContext ac, int i) {
        return AtkUtil.invokeInSwing(() -> {
            Accessible child = ac.getAccessibleChild(i);
            if (child == null)
                return null;
            else
                return child.getAccessibleContext();
        }, null);
    }

    private static int hash_code(AccessibleContext ac) {
        return AtkUtil.invokeInSwing(() -> {
            return ac.hashCode();
        }, 0);
    }

}
