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
 * <p>
 * Java-side utility class used by the GNOME Accessibility Bridge.
 * <p>
 * That class is used to wrap AccessibleContext Java object
 * to avoid the concurrency of AWT objects.
 *
 * @autor Giuseppe Capaldo
 */
public class AtkObject {

    // need to fix [missing-explicit-ctor]
    private AtkObject() {
    }

    /**
     * Returns the JMenuItem accelerator. Similar implementation is used on
     * macOS, see CAccessibility.getAcceleratorText(AccessibleContext) in OpenJDK, and
     * on Windows, see AccessBridge.getAccelerator(AccessibleContext) in OpenJDK.
     */
    private static String getAcceleratorText(AccessibleContext ac) {
        assert (EventQueue.isDispatchThread());

        String acceleratorText = "";
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

                    acceleratorText += modifiersText;
                    if (!modifiersText.isEmpty() && !keyCodeText.isEmpty()) {
                        acceleratorText += "+";
                    }
                    acceleratorText += keyCodeText;
                }
            }
        }
        return acceleratorText;
    }

    // JNI upcalls section

    /**
     * Gets the ATK interface flags for the given accessible object.
     * Called from native code via JNI.
     *
     * @param o the accessible object (AccessibleContext or Accessible)
     * @return bitwise OR of ATK interface flags from {@link AtkInterface}
     */
    private static int get_tflag_from_obj(Object o) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            int flags = 0;
            AccessibleContext ac;

            if (o instanceof AccessibleContext accessibleContext) {
                ac = accessibleContext;
            } else if (o instanceof Accessible accessible) {
                ac = accessible.getAccessibleContext();
            } else {
                return flags;
            }

            if (ac.getAccessibleAction() != null) {
                flags |= AtkInterface.INTERFACE_ACTION;
            }
            if (ac.getAccessibleComponent() != null) {
                flags |= AtkInterface.INTERFACE_COMPONENT;
            }
            AccessibleText text = ac.getAccessibleText();
            if (text != null) {
                flags |= AtkInterface.INTERFACE_TEXT;
                if (text instanceof AccessibleHypertext) {
                    flags |= AtkInterface.INTERFACE_HYPERTEXT;
                }
                if (ac.getAccessibleEditableText() != null) {
                    flags |= AtkInterface.INTERFACE_EDITABLE_TEXT;
                }
            }
            if (ac.getAccessibleIcon() != null) {
                flags |= AtkInterface.INTERFACE_IMAGE;
            }
            if (ac.getAccessibleSelection() != null) {
                flags |= AtkInterface.INTERFACE_SELECTION;
            }
            AccessibleTable table = ac.getAccessibleTable();
            if (table != null) {
                flags |= AtkInterface.INTERFACE_TABLE;
            }
            Accessible parent = ac.getAccessibleParent();
            if (parent != null) {
                AccessibleContext parentAccessibleContext = parent.getAccessibleContext();
                if (parentAccessibleContext != null) {
                    table = parentAccessibleContext.getAccessibleTable();
                    if (table != null && table instanceof AccessibleExtendedTable) {
                        flags |= AtkInterface.INTERFACE_TABLE_CELL;
                    }
                }
            }
            if (ac.getAccessibleValue() != null) {
                flags |= AtkInterface.INTERFACE_VALUE;
            }
            return flags;
        }, 0);
    }

    /**
     * Gets the parent AccessibleContext of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return the parent accessible context, or null if no parent exists
     */
    private static AccessibleContext get_accessible_parent(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible accessibleParent = ac.getAccessibleParent();
            if (accessibleParent == null) {
                return null;
            }
            AccessibleContext accessibleContext = accessibleParent.getAccessibleContext();
            if (accessibleContext != null) {
                AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
            }
            return accessibleContext;
        }, null);
    }

    /**
     * Sets the parent of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac                      the accessible context whose parent should be set
     * @param parentAccessibleContext the new parent accessible context (must be Accessible)
     */
    private static void set_accessible_parent(AccessibleContext ac, AccessibleContext parentAccessibleContext) {
        AtkUtil.invokeInSwing(() -> {
            if (parentAccessibleContext instanceof Accessible parentAccessible) {
                ac.setAccessibleParent(parentAccessible);
            }
        });
    }

    /**
     * Gets the accessible name of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return the accessible name, with accelerator text appended, or null if no name is set
     */
    private static String get_accessible_name(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            String accessibleName = ac.getAccessibleName();
            if (accessibleName == null) {
                return null;
            }
            String acceleratorText = getAcceleratorText(ac);
            if (!acceleratorText.isEmpty()) {
                return accessibleName + " " + acceleratorText;
            }
            return accessibleName;
        }, null);
    }

    /**
     * Sets the accessible name of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac   the accessible context
     * @param name the new accessible name
     */
    private static void set_accessible_name(AccessibleContext ac, String name) {
        AtkUtil.invokeInSwing(() -> {
            ac.setAccessibleName(name);
        });
    }

    /**
     * Gets the accessible description of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return the accessible description, or empty string if no description is set
     */
    private static String get_accessible_description(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return ac.getAccessibleDescription();
        }, null);
    }

    /**
     * Sets the accessible description of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac          the accessible context
     * @param description the new accessible description
     */
    private static void set_accessible_description(AccessibleContext ac, String description) {
        AtkUtil.invokeInSwing(() -> {
            ac.setAccessibleDescription(description);
        });
    }

    /**
     * Gets the number of accessible children of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return the number of accessible children, or 0 if there are no children
     */
    private static int get_accessible_children_count(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return ac.getAccessibleChildrenCount();
        }, 0);
    }

    /**
     * Gets the index of this accessible context within its parent's children.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return the zero-based index in parent, or -1 if no parent exists or index cannot be determined
     */
    private static int get_accessible_index_in_parent(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return ac.getAccessibleIndexInParent();
        }, -1);
    }

    /**
     * Gets the accessible role of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return the accessible role, or null if the role cannot be determined
     */
    private static AccessibleRole get_accessible_role(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            return ac.getAccessibleRole();
        }, null);
    }

    /**
     * Gets an array of accessible states for the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return an array of accessible states, or null if no state set exists
     */
    private static AccessibleState[] get_array_accessible_state(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleStateSet stateSet = ac.getAccessibleStateSet();
            if (stateSet == null) {
                return null;
            } else {
                return stateSet.toArray();
            }
        }, null);
    }

    /**
     * Gets the locale of the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return the locale string in the format "language_country@script@variant", or null if locale cannot be determined
     */
    private static String get_locale(AccessibleContext ac) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            Locale l = ac.getLocale();
            String locale = l.getLanguage();
            String country = l.getCountry();
            String script = l.getScript();
            String variant = l.getVariant();
            if (!country.isEmpty()) {
                locale += "_" + country;
            }
            if (!script.isEmpty()) {
                locale += "@" + script;
            }
            if (!variant.isEmpty()) {
                locale += "@" + variant;
            }
            return locale;
        }, null);
    }

    /**
     * Gets an array of accessible relations for the given accessible context.
     * Called from native code via JNI.
     *
     * @param ac the accessible context
     * @return an array of WrapKeyAndTarget records containing relation keys and targets,
     * or an empty array if no relations exist
     */
    private static WrapKeyAndTarget[] get_array_accessible_relation(AccessibleContext ac) {
        WrapKeyAndTarget[] d = new WrapKeyAndTarget[0];
        return AtkUtil.invokeInSwingAndWait(() -> {
            AccessibleRelationSet relationSet = ac.getAccessibleRelationSet();
            if (relationSet == null) {
                return d;
            } else {
                AccessibleRelation[] array = relationSet.toArray();
                WrapKeyAndTarget[] result = new WrapKeyAndTarget[array.length];
                for (int i = 0; i < array.length; i++) {
                    String key = array[i].getKey();
                    Object[] objs = array[i].getTarget();
                    AccessibleContext[] contexts = new AccessibleContext[objs.length];
                    for (int j = 0; j < objs.length; j++) {
                        if (objs[j] instanceof Accessible accessible) {
                            contexts[j] = accessible.getAccessibleContext();
                        } else {
                            contexts[j] = null;
                        }
                    }
                    result[i] = new WrapKeyAndTarget(key, contexts);
                }
                return result;
            }
        }, d);
    }

    /**
     * Gets the accessible child at the specified index.
     * Called from native code via JNI.
     *
     * @param ac    the parent accessible context
     * @param index the zero-based index of the child
     * @return the child accessible context at the given index, or null if no child exists at that index
     */
    private static AccessibleContext get_accessible_child(AccessibleContext ac, int index) {
        return AtkUtil.invokeInSwingAndWait(() -> {
            Accessible child = ac.getAccessibleChild(index);
            if (child == null) {
                return null;
            }
            AccessibleContext accessibleContext = child.getAccessibleContext();
            if (accessibleContext != null) {
                AtkWrapperDisposer.getInstance().addRecord(accessibleContext);
            }
            return accessibleContext;
        }, null);
    }

    /**
     * A record that wraps an accessible relation key with its target accessible contexts.
     * Used to pass relation information from Java to native code.
     */
    private record WrapKeyAndTarget(String key, AccessibleContext[] relations) {
    }
}
