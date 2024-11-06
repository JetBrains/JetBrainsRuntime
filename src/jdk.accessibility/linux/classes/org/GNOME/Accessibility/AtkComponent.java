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

package org.GNOME.Accessibility;

import javax.accessibility.*;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.ref.WeakReference;

public class AtkComponent {

  WeakReference<AccessibleContext> _ac;
  WeakReference<AccessibleComponent> _acc_component;

  public AtkComponent (AccessibleContext ac) {
    super();
    this._ac = new WeakReference<AccessibleContext>(ac);
    this._acc_component = new WeakReference<AccessibleComponent>(ac.getAccessibleComponent());
  }

  public static AtkComponent createAtkComponent(AccessibleContext ac){
      return AtkUtil.invokeInSwing ( () -> { return new AtkComponent(ac); }, null);
  }

  static public Point getWindowLocation(AccessibleContext ac) {
      while (ac != null) {
          AccessibleRole role = ac.getAccessibleRole();
          if (role == AccessibleRole.DIALOG ||
              role == AccessibleRole.FRAME ||
              role == AccessibleRole.WINDOW) {
              AccessibleComponent acc_comp = ac.getAccessibleComponent();
              if (acc_comp == null)
                  return null;
              return acc_comp.getLocationOnScreen();
          }
          Accessible parent = ac.getAccessibleParent();
          if (parent == null)
              return null;
          ac = parent.getAccessibleContext();
      }
      return null;
  }

  // Return the position of the object relative to the coordinate type
  public static Point getComponentOrigin(AccessibleContext ac, AccessibleComponent acc_component, int coord_type) {
      if (coord_type == AtkCoordType.SCREEN)
          return acc_component.getLocationOnScreen();

      if (coord_type == AtkCoordType.WINDOW)
      {
          Point win_p = getWindowLocation(ac);
          if (win_p == null)
              return null;
          Point p = acc_component.getLocationOnScreen();
          if (p == null)
              return null;
          p.translate(-win_p.x, -win_p.y);
          return p;
      }

      if (coord_type == AtkCoordType.PARENT)
          return acc_component.getLocation();

      return null;
  }

  // Return the position of the parent relative to the coordinate type
  public static Point getParentOrigin(AccessibleContext ac, AccessibleComponent acc_component, int coord_type) {
      if (coord_type == AtkCoordType.PARENT)
          return new Point(0, 0);

      Accessible parent = ac.getAccessibleParent();
      if (parent == null)
          return null;
      AccessibleContext parent_ac = parent.getAccessibleContext();
      if (parent_ac == null)
          return null;
      AccessibleComponent parent_component = parent_ac.getAccessibleComponent();
      if (parent_component == null)
          return null;

      if (coord_type == AtkCoordType.SCREEN) {
          return parent_component.getLocationOnScreen();
      }

      if (coord_type == AtkCoordType.WINDOW) {
          Point window_origin = getWindowLocation(ac);
          if (window_origin == null)
              return null;
          Point parent_origin = parent_component.getLocationOnScreen();
          if (parent_origin == null)
              return null;
          parent_origin.translate(-window_origin.x, -window_origin.y);
          return parent_origin;
      }
      return null;
  }

  public boolean contains (int x, int y, int coord_type) {
      AccessibleContext ac = _ac.get();
      if (ac == null)
          return false;
      AccessibleComponent acc_component = _acc_component.get();
      if (acc_component == null)
          return false;

      return AtkUtil.invokeInSwing ( () -> {
          if(acc_component.isVisible()){
              Point p = getComponentOrigin(ac, acc_component, coord_type);
              if (p == null)
                  return false;

              return acc_component.contains(new Point(x - p.x, y - p.y));
          }
          return false;
      }, false);
  }

  public AccessibleContext get_accessible_at_point (int x, int y, int coord_type) {
      AccessibleContext ac = _ac.get();
      if (ac == null)
          return null;
      AccessibleComponent acc_component = _acc_component.get();
      if (acc_component == null)
          return null;

      return AtkUtil.invokeInSwing ( () -> {
          if(acc_component.isVisible()){
              Point p = getComponentOrigin(ac, acc_component, coord_type);
              if (p == null)
                  return null;

              Accessible accessible = acc_component.getAccessibleAt(new Point(x - p.x, y - p.y));
              if (accessible == null)
                  return null;
              return accessible.getAccessibleContext();
          }
          return null;
      }, null);
  }

    public boolean grab_focus () {
        AccessibleComponent acc_component = _acc_component.get();
        if (acc_component == null)
            return false;

        return AtkUtil.invokeInSwing ( () -> {
            if (!acc_component.isFocusTraversable())
                return false;
            acc_component.requestFocus();
            return true;
        }, false);
    }

    public boolean set_extents(int x, int y, int width, int height, int coord_type) {
        AccessibleContext ac = _ac.get();
        if (ac == null)
            return false;
        AccessibleComponent acc_component = _acc_component.get();
        if (acc_component == null)
            return false;

        return AtkUtil.invokeInSwing( () -> {
            if(acc_component.isVisible()){
                Point p = getParentOrigin(ac, acc_component, coord_type);
                if (p == null)
                    return false;

                acc_component.setBounds(new Rectangle(x - p.x, y - p.y, width, height));
                return true;
            }
            return false;
        }, false);
    }

    public Rectangle get_extents(int coord_type) {
        AccessibleContext ac = _ac.get();
        if (ac == null)
            return null;
        AccessibleComponent acc_component = _acc_component.get();
        if (acc_component == null)
            return null;

        return AtkUtil.invokeInSwing ( () -> {
            if(acc_component.isVisible()){
                Rectangle rect = acc_component.getBounds();
                if (rect == null)
                    return null;
                Point p = getParentOrigin(ac, acc_component, coord_type);
                if (p == null)
                    return null;

                rect.x += p.x;
                rect.y += p.y;
                return rect;
            }
            return null;
        },null);
    }

    public int get_layer () {
        AccessibleContext ac = _ac.get();
        if (ac == null)
            return AtkLayer.INVALID;

        return AtkUtil.invokeInSwing ( () -> {
            AccessibleRole role = ac.getAccessibleRole();
            if (role == AccessibleRole.MENU ||
            role == AccessibleRole.MENU_ITEM ||
            role == AccessibleRole.POPUP_MENU ) {
                return AtkLayer.POPUP;
            }
            if (role == AccessibleRole.INTERNAL_FRAME) {
                return AtkLayer.MDI;
            }
            if (role == AccessibleRole.GLASS_PANE) {
                return AtkLayer.OVERLAY;
            }
            if (role == AccessibleRole.CANVAS ||
            role == AccessibleRole.ROOT_PANE ||
            role == AccessibleRole.LAYERED_PANE ) {
                return AtkLayer.CANVAS;
            }
            if (role == AccessibleRole.WINDOW) {
                return AtkLayer.WINDOW;
            }
            return AtkLayer.WIDGET;
        }, AtkLayer.INVALID);
    }

}
