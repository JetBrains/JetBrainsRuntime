/*
 * Copyright (c) 2003, 2014, Oracle and/or its affiliates. All rights reserved.
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


package sun.awt.X11;

import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.MouseEvent;

import sun.awt.IconInfo;
import sun.awt.SunToolkit;
import sun.util.logging.PlatformLogger;

final class XNETProtocol extends XProtocol implements XStateProtocol, XLayerProtocol
{
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.X11.XNETProtocol");
    private static final PlatformLogger iconLog = PlatformLogger.getLogger("sun.awt.X11.icon.XNETProtocol");
    private static PlatformLogger stateLog = PlatformLogger.getLogger("sun.awt.X11.states.XNETProtocol");

    /**
     * XStateProtocol
     */
    public boolean supportsState(int state) {
        return doStateProtocol() ; // TODO - check for Frame constants
    }

    public void setState(XWindowPeer window, int state) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Setting state of " + window + " to " + state);
        }
        if (window.isShowing()) {
            requestState(window, state);
        } else {
            setInitialState(window, state);
        }
    }

    private void setInitialState(XWindowPeer window, int state) {
        XAtomList old_state = window.getNETWMState();
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Current state of the window {0} is {1}", window, old_state);
        }
        if ((state & Frame.MAXIMIZED_VERT) != 0) {
            old_state.add(XA_NET_WM_STATE_MAXIMIZED_VERT);
        } else {
            old_state.remove(XA_NET_WM_STATE_MAXIMIZED_VERT);
        }
        if ((state & Frame.MAXIMIZED_HORIZ) != 0) {
            old_state.add(XA_NET_WM_STATE_MAXIMIZED_HORZ);
        } else {
            old_state.remove(XA_NET_WM_STATE_MAXIMIZED_HORZ);
        }
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Setting initial state of the window {0} to {1}", window, old_state);
        }
        window.setNETWMState(old_state);
    }

    private void requestState(XWindowPeer window, int state) {
        int old_net_state = getState(window);
        int max_changed = (state ^ old_net_state) & (Frame.MAXIMIZED_BOTH);

        XClientMessageEvent req = new XClientMessageEvent();
        int action = 0;
        try {
            switch(max_changed) {
              case 0:
                  return;
              case Frame.MAXIMIZED_HORIZ:
                  req.set_data(1, XA_NET_WM_STATE_MAXIMIZED_HORZ.getAtom());
                  req.set_data(2, 0);
                  action = ((state & Frame.MAXIMIZED_HORIZ) == Frame.MAXIMIZED_HORIZ)
                          ? _NET_WM_STATE_ADD
                          : _NET_WM_STATE_REMOVE;
                  break;
              case Frame.MAXIMIZED_VERT:
                  req.set_data(1, XA_NET_WM_STATE_MAXIMIZED_VERT.getAtom());
                  req.set_data(2, 0);
                  action = ((state & Frame.MAXIMIZED_VERT) == Frame.MAXIMIZED_VERT)
                          ? _NET_WM_STATE_ADD
                          : _NET_WM_STATE_REMOVE;
                  break;
              case Frame.MAXIMIZED_BOTH:
                  // Somehow this doesn't work when changing HORZ->VERT, but works VERT->HORZ
                  req.set_data(1, XA_NET_WM_STATE_MAXIMIZED_HORZ.getAtom());
                  req.set_data(2, XA_NET_WM_STATE_MAXIMIZED_VERT.getAtom());
                  action = _NET_WM_STATE_TOGGLE;
                  break;
              default:
                  return;
            }
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Requesting state on " + window + " for " + state);
            }
            req.set_type(XConstants.ClientMessage);
            req.set_window(window.getWindow());
            req.set_message_type(XA_NET_WM_STATE.getAtom());
            req.set_format(32);
            req.set_data(0, action);
            XToolkit.awtLock();
            try {
                XlibWrapper.XSendEvent(XToolkit.getDisplay(),
                        XlibWrapper.RootWindow(XToolkit.getDisplay(), window.getScreenNumber()),
                        false,
                        XConstants.SubstructureRedirectMask | XConstants.SubstructureNotifyMask,
                        req.pData);
            }
            finally {
                XToolkit.awtUnlock();
            }
        } finally {
            req.dispose();
        }
    }

    public int getState(XWindowPeer window) {
        return getStateImpl(window);
    }

    /*
     * New "NET" WM spec: _NET_WM_STATE/Atom[]
     */
    int getStateImpl(XWindowPeer window) {
        XAtomList net_wm_state = window.getNETWMState();
        if (net_wm_state.size() == 0) {
            return Frame.NORMAL;
        }
        int java_state = Frame.NORMAL;
        if (net_wm_state.contains(XA_NET_WM_STATE_MAXIMIZED_VERT)) {
            java_state |= Frame.MAXIMIZED_VERT;
        }
        if (net_wm_state.contains(XA_NET_WM_STATE_MAXIMIZED_HORZ)) {
            java_state |= Frame.MAXIMIZED_HORIZ;
        }
        return java_state;
    }

    public boolean isStateChange(XPropertyEvent e) {
        boolean res = doStateProtocol() && (e.get_atom() == XA_NET_WM_STATE.getAtom()) ;

        if (res) {
            // Since state change happened, reset our cached state.  It will be re-read by getState
            XWindowPeer wpeer = (XWindowPeer)XToolkit.windowToXWindow(e.get_window());
            wpeer.setNETWMState(null);
        }
        return res;
    }

    /*
     * Work around for 4775545.
     */
    public void unshadeKludge(XWindowPeer window) {
        XAtomList net_wm_state = window.getNETWMState();
        net_wm_state.remove(XA_NET_WM_STATE_SHADED);
        window.setNETWMState(net_wm_state);
    }

    /**
     * XLayerProtocol
     */
    public boolean supportsLayer(int layer) {
        return ((layer == LAYER_ALWAYS_ON_TOP) || (layer == LAYER_NORMAL)) && doLayerProtocol();
    }

    public void requestState(XWindow window, XAtom state, boolean isAdd) {
        XClientMessageEvent req = new XClientMessageEvent();
        try {
            req.set_type(XConstants.ClientMessage);
            req.set_window(window.getWindow());
            req.set_message_type(XA_NET_WM_STATE.getAtom());
            req.set_format(32);
            req.set_data(0, isAdd ? _NET_WM_STATE_ADD : _NET_WM_STATE_REMOVE);
            req.set_data(1, state.getAtom());
            // Fix for 6735584: req.data[2] must be set to 0 when only one property is changed
            req.set_data(2, 0);
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Setting _NET_STATE atom {0} on {1} for {2}", state, window, Boolean.valueOf(isAdd));
            }
            XToolkit.awtLock();
            try {
                XlibWrapper.XSendEvent(XToolkit.getDisplay(),
                        XlibWrapper.RootWindow(XToolkit.getDisplay(), window.getScreenNumber()),
                        false,
                        XConstants.SubstructureRedirectMask | XConstants.SubstructureNotifyMask,
                        req.pData);
            }
            finally {
                XToolkit.awtUnlock();
            }
        } finally {
            req.dispose();
        }
    }

    /**
     * Helper function to set/reset one state in NET_WM_STATE
     * If window is showing then it uses ClientMessage, otherwise adjusts NET_WM_STATE list
     * @param window Window which NET_WM_STATE property is being modified
     * @param state State atom to be set/reset
     * @param set Indicates operation, 'set' if false, 'reset' if true
     */
    private void setStateHelper(XWindowPeer window, XAtom state, boolean set) {
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("Window visibility is: withdrawn={0}, visible={1}, mapped={2} showing={3}",
                  Boolean.valueOf(window.isWithdrawn()), Boolean.valueOf(window.isVisible()),
                  Boolean.valueOf(window.isMapped()), Boolean.valueOf(window.isShowing()));
        }
        XAtomList net_wm_state = window.getNETWMState();
        if (window.isShowing()) {
            if (net_wm_state.contains(state) == set) {
                if (log.isLoggable(PlatformLogger.Level.FINER)) {
                    log.finer("Current state on {0} is {1}, {2} is already {3}",
                            window, net_wm_state, state, set ? "set" : "unset");
                }
                return;
            }
            if (!set) {
                net_wm_state.remove(state);
            } else {
                net_wm_state.add(state);
            }
            window.net_wm_state = net_wm_state;
            requestState(window, state, set);
        } else {
            if (log.isLoggable(PlatformLogger.Level.FINER)) {
                log.finer("Current state on {0} is {1}", window, net_wm_state);
            }
            if (!set) {
                net_wm_state.remove(state);
            } else {
                net_wm_state.add(state);
            }
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Setting states on {0} to {1}", window, net_wm_state);
            }
            window.setNETWMState(net_wm_state);
        }
        XToolkit.XSync();
    }

    public void setLayer(XWindowPeer window, int layer) {
        setStateHelper(window, XA_NET_WM_STATE_ABOVE, layer == LAYER_ALWAYS_ON_TOP);
    }

    /* New "netwm" spec from www.freedesktop.org */
    XAtom XA_UTF8_STRING = XAtom.get("UTF8_STRING");   /* like STRING but encoding is UTF-8 */
    XAtom XA_NET_SUPPORTING_WM_CHECK = XAtom.get("_NET_SUPPORTING_WM_CHECK");
    XAtom XA_NET_SUPPORTED = XAtom.get("_NET_SUPPORTED");      /* list of protocols (property of root) */
    XAtom XA_NET_ACTIVE_WINDOW = XAtom.get("_NET_ACTIVE_WINDOW");
    XAtom XA_NET_WM_NAME = XAtom.get("_NET_WM_NAME");  /* window property */
    XAtom XA_NET_WM_STATE = XAtom.get("_NET_WM_STATE");/* both window property and request */

/*
 * _NET_WM_STATE is a list of atoms.
 * NB: Standard spelling is "HORZ" (yes, without an 'I'), but KDE2
 * uses misspelled "HORIZ" (see KDE bug #20229).  This was fixed in
 * KDE 2.2.  Under earlier versions of KDE2 horizontal and full
 * maximization doesn't work .
 */
    XAtom XA_NET_WM_STATE_MAXIMIZED_HORZ = XAtom.get("_NET_WM_STATE_MAXIMIZED_HORZ");
    XAtom XA_NET_WM_STATE_MAXIMIZED_VERT = XAtom.get("_NET_WM_STATE_MAXIMIZED_VERT");
    XAtom XA_NET_WM_STATE_SHADED = XAtom.get("_NET_WM_STATE_SHADED");
    XAtom XA_NET_WM_STATE_ABOVE = XAtom.get("_NET_WM_STATE_ABOVE");
    XAtom XA_NET_WM_STATE_MODAL = XAtom.get("_NET_WM_STATE_MODAL");
    XAtom XA_NET_WM_STATE_FULLSCREEN = XAtom.get("_NET_WM_STATE_FULLSCREEN");
    XAtom XA_NET_WM_STATE_BELOW = XAtom.get("_NET_WM_STATE_BELOW");
    XAtom XA_NET_WM_STATE_HIDDEN = XAtom.get("_NET_WM_STATE_HIDDEN");
    XAtom XA_NET_WM_STATE_SKIP_TASKBAR = XAtom.get("_NET_WM_STATE_SKIP_TASKBAR");
    XAtom XA_NET_WM_STATE_SKIP_PAGER = XAtom.get("_NET_WM_STATE_SKIP_PAGER");

    public final XAtom XA_NET_WM_WINDOW_TYPE = XAtom.get("_NET_WM_WINDOW_TYPE");
    public final XAtom XA_NET_WM_WINDOW_TYPE_NORMAL = XAtom.get("_NET_WM_WINDOW_TYPE_NORMAL");
    public final XAtom XA_NET_WM_WINDOW_TYPE_DIALOG = XAtom.get("_NET_WM_WINDOW_TYPE_DIALOG");
    public final XAtom XA_NET_WM_WINDOW_TYPE_UTILITY = XAtom.get("_NET_WM_WINDOW_TYPE_UTILITY");
    public final XAtom XA_NET_WM_WINDOW_TYPE_POPUP_MENU = XAtom.get("_NET_WM_WINDOW_TYPE_POPUP_MENU");

    XAtom XA_NET_WM_WINDOW_OPACITY = XAtom.get("_NET_WM_WINDOW_OPACITY");

    XAtom XA_NET_WM_USER_TIME = XAtom.get("_NET_WM_USER_TIME");
    public final XAtom XA_NET_WM_MOVE_RESIZE = XAtom.get("_NET_WM_MOVERESIZE");

/* For _NET_WM_STATE ClientMessage requests */
    static final int _NET_WM_STATE_REMOVE      =0; /* remove/unset property */
    static final int _NET_WM_STATE_ADD         =1; /* add/set property      */
    static final int _NET_WM_STATE_TOGGLE      =2; /* toggle property       */

    boolean supportChecked = false;
    long NetWindow = 0;
    void detect() {
        if (supportChecked) {
            // TODO: How about detecting WM-restart or exit?
            return;
        }
        NetWindow = checkAnchor(XA_NET_SUPPORTING_WM_CHECK, XAtom.XA_WINDOW);
        supportChecked = true;
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("### " + this + " is active: " + (NetWindow != 0));
        }
    }

    boolean active() {
        detect();
        return NetWindow != 0;
    }

    boolean doStateProtocol() {
        boolean res = active() && checkProtocol(XA_NET_SUPPORTED, XA_NET_WM_STATE);
        if (stateLog.isLoggable(PlatformLogger.Level.FINER)) {
            stateLog.finer("doStateProtocol() returns " + res);
        }
        return res;
    }

    boolean doLayerProtocol() {
        boolean res = active() && checkProtocol(XA_NET_SUPPORTED, XA_NET_WM_STATE_ABOVE);
        return res;
    }

    boolean doModalityProtocol() {
        boolean res = active() && checkProtocol(XA_NET_SUPPORTED, XA_NET_WM_STATE_MODAL);
        return res;
    }

    boolean doOpacityProtocol() {
        boolean res = active() && checkProtocol(XA_NET_SUPPORTED, XA_NET_WM_WINDOW_OPACITY);
        return res;
    }

    boolean doWMMoveResizeProtocol() {
        return active() && checkProtocol(XA_NET_SUPPORTED, XA_NET_WM_MOVE_RESIZE);
    }

    public void setActiveWindow(long window) {
        if (!active() || !checkProtocol(XA_NET_SUPPORTED, XA_NET_ACTIVE_WINDOW)) {
            return;
        }

        XClientMessageEvent msg = new XClientMessageEvent();
        msg.zero();
        msg.set_type(XConstants.ClientMessage);
        msg.set_message_type(XA_NET_ACTIVE_WINDOW.getAtom());
        msg.set_display(XToolkit.getDisplay());
        msg.set_window(window);
        msg.set_format(32);
        msg.set_data(0, 1);
        msg.set_data(1, XToolkit.getCurrentServerTime());
        msg.set_data(2, 0);

        XToolkit.awtLock();
        try {
            XlibWrapper.XSendEvent(XToolkit.getDisplay(), XToolkit.getDefaultRootWindow(), false,
                    XConstants.SubstructureRedirectMask | XConstants.SubstructureNotifyMask, msg.getPData());
        } finally {
            XToolkit.awtUnlock();
            msg.dispose();
        }
    }

    boolean isWMName(String name) {
        if (!active()) {
            return false;
        }
        String net_wm_name_string = getWMName();
        if (net_wm_name_string == null) {
            return false;
        }
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("### WM_NAME = " + net_wm_name_string);
        }
        return net_wm_name_string.startsWith(name);
    }

    String net_wm_name_cache;
    public String getWMName() {
        if (!active()) {
            return null;
        }

        if (net_wm_name_cache != null) {
            return net_wm_name_cache;
        }

        /*
         * Check both UTF8_STRING and STRING.  We only call this function
         * with ASCII names and UTF8 preserves ASCII bit-wise.  wm-spec
         * mandates UTF8_STRING for _NET_WM_NAME but at least sawfish-1.0
         * still uses STRING.  (mmm, moving targets...).
         */
        String charSet = "UTF8";
        byte[] net_wm_name = XA_NET_WM_NAME.getByteArrayProperty(NetWindow, XA_UTF8_STRING.getAtom());
        if (net_wm_name == null) {
            net_wm_name = XA_NET_WM_NAME.getByteArrayProperty(NetWindow, XAtom.XA_STRING);
            charSet = "ASCII";
        }

        if (net_wm_name == null) {
            return null;
        }
        try {
            net_wm_name_cache = new String(net_wm_name, charSet);
            return net_wm_name_cache;
        } catch (java.io.UnsupportedEncodingException uex) {
            return null;
        }
    }

    /**
     * Sets _NET_WM_ICON property on the window using the List of IconInfo
     * If icons is null or empty list, removes _NET_WM_ICON property
     */
    public void setWMIcons(XWindowPeer window, java.util.List<IconInfo> icons) {
        if (window == null) return;

        XAtom iconsAtom = XAtom.get("_NET_WM_ICON");
        if (icons == null) {
            iconsAtom.DeleteProperty(window);
            return;
        }

        int length = 0;
        for (IconInfo ii : icons) {
            length += ii.getRawLength();
        }
        int cardinalSize = (XlibWrapper.dataModel == 32) ? 4 : 8;
        int bufferSize = length * cardinalSize;

        if (bufferSize != 0) {
            long buffer = XlibWrapper.unsafe.allocateMemory(bufferSize);
            try {
                long ptr = buffer;
                for (IconInfo ii : icons) {
                    int size = ii.getRawLength() * cardinalSize;
                    if (XlibWrapper.dataModel == 32) {
                        XlibWrapper.copyIntArray(ptr, ii.getIntData(), size);
                    } else {
                        XlibWrapper.copyLongArray(ptr, ii.getLongData(), size);
                    }
                    ptr += size;
                }
                iconsAtom.setAtomData(window.getWindow(), XAtom.XA_CARDINAL, buffer, bufferSize/Native.getCard32Size());
            } finally {
                XlibWrapper.unsafe.freeMemory(buffer);
            }
        } else {
            iconsAtom.DeleteProperty(window);
        }
    }

    public boolean isWMStateNetHidden(XWindowPeer window) {
        if (!doStateProtocol()) {
            return false;
        }
        XAtomList state = window.getNETWMState();
        return (state != null && state.size() != 0 && state.contains(XA_NET_WM_STATE_HIDDEN));
    }

    void setUserTime(XBaseWindow window, long time) {
        if (active()) {
            XA_NET_WM_USER_TIME.setCard32Property(window, time);
        }
    }

    private static int convertToXButton(int mouseButton) {
        return switch (mouseButton) {
            case MouseEvent.BUTTON1 -> 1;
            case MouseEvent.BUTTON2 -> 3;
            case MouseEvent.BUTTON3 -> 2;
            default -> -1;
        };
    }

    /**
     * Starts moving the window together with the mouse pointer by sending a _NET_WM_MOVERESIZE event with
     * the direction set to _NET_WM_MOVERESIZE_MOVE (move only), x_root/y_root obtained from the current
     * mouse pointer coordinates, and button set to {@code mouseButton}.
     * See <a href="https://specifications.freedesktop.org/wm-spec/wm-spec-1.3.html">Extended Window Manager Hints</a>
     * for more information.
     * Preconditions for calling this method:
     * <ul>
     * <li>{@code XWM.isWMMoveResizeSupported()} returned true.</li>
     * <li>Mouse pointer is within this window's bounds.</li>
     * <li>The mouse button specified by {@code mouseButton} is pressed.</li>
     * </ul>
     * Calling this method will make the window to start moving together with the mouse pointer until
     * the specified mouse button is released or Esc is pressed. The conditions for cancelling
     * the move may differ between WMs.
     *
     * @param lastButtonPressAbsLocation the location of the mouse pointer in absolute coordinates where the mouse
     *                                   button was pressed.
     * @param mouseButton                indicates the mouse button that was pressed to start moving the window;
     *                                   must be one of {@code MouseEvent.BUTTON1}, {@code MouseEvent.BUTTON2},
     *                                   or {@code MouseEvent.BUTTON3}.
     */
    void startMovingWindowTogetherWithMouse(long window, Point lastButtonPressAbsLocation, int mouseButton) {
        final int button = convertToXButton(mouseButton);
        if (button <= 0) return;

        if (lastButtonPressAbsLocation == null) return;

        SunToolkit.awtLock();
        final XClientMessageEvent msg = new XClientMessageEvent();
        try {
            msg.set_type(XConstants.ClientMessage);
            msg.set_window(window);
            msg.set_format(32);
            msg.set_message_type(XA_NET_WM_MOVE_RESIZE.getAtom());

            final int _NET_WM_MOVERESIZE_MOVE = 8;
            // See https://specifications.freedesktop.org/wm-spec/wm-spec-1.3.html
            msg.set_data(0, lastButtonPressAbsLocation.x);
            msg.set_data(1, lastButtonPressAbsLocation.y);
            msg.set_data(2, _NET_WM_MOVERESIZE_MOVE);
            msg.set_data(3, button);
            msg.set_data(4, 0);

            // Though we already did setGrab(false) before, this may sometimes be not enough.
            // Presumably pointer may be grabbed automatically by a button press,
            // so release grabs again here, otherwise it wouldn't work on Xorg.
            XlibWrapper.XUngrabPointer(XToolkit.getDisplay(), XConstants.CurrentTime);
            XlibWrapper.XUngrabKeyboard(XToolkit.getDisplay(), XConstants.CurrentTime);
            XlibWrapper.XSendEvent(XToolkit.getDisplay(),
                    XToolkit.getDefaultRootWindow(),
                    false,
                    XConstants.SubstructureRedirectMask | XConstants.SubstructureNotifyMask,
                    msg.pData);
        } finally {
            msg.dispose();
            SunToolkit.awtUnlock();
        }
    }
}
