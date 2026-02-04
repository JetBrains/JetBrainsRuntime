/*
 * TODO copyright
 */

package sun.lwawt;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

public class LWMouseEventDispatcher {
    private final LWWindowPeerAPI windowPeer;
    private final ToolkitAPI toolkitApi;

    // check that the mouse is over the window
    private volatile boolean isMouseOver;

    // A peer where the last mouse event came to. Used by cursor manager to
    // find the component under cursor
    private static volatile LWComponentPeerAPI lastCommonMouseEventPeer;

    // A peer where the last mouse event came to. Used to generate
    // MOUSE_ENTERED/EXITED notifications
    private volatile LWComponentPeerAPI lastMouseEventPeer;

    // Peers where all dragged/released events should come to,
    // depending on what mouse button is being dragged according to Cocoa
    private static final LWComponentPeerAPI[] mouseDownTarget = new LWComponentPeerAPI[3];

    // A bitmask that indicates what mouse buttons produce MOUSE_CLICKED events
    // on MOUSE_RELEASE. Click events are only generated if there were no drag
    // events between MOUSE_PRESSED and MOUSE_RELEASED for particular button
    private static int mouseClickButtons = 0;

    public LWMouseEventDispatcher(LWWindowPeerAPI windowPeer, ToolkitAPI toolkitApi) {
        this.windowPeer = windowPeer;
        this.toolkitApi = toolkitApi;
    }

    public static LWComponentPeerAPI getLastCommonMouseEventPeer() {
        return lastCommonMouseEventPeer;
    }

    public void notifyMouseEvent(int id, long when, int button,
                                 int x, int y, int absX, int absY,
                                 int modifiers, int clickCount, boolean popupTrigger) {
        // TODO: fill "bdata" member of AWTEvent
        Rectangle r = windowPeer.getBounds();
        // findPeerAt() expects parent coordinates
        LWComponentPeerAPI targetPeer = windowPeer.findPeerAt(r.x + x, r.y + y);

        if (id == MouseEvent.MOUSE_EXITED) {
            isMouseOver = false;
            if (lastMouseEventPeer != null) {
                if (lastMouseEventPeer.isEnabled()) {
                    Point lp = lastMouseEventPeer.windowToLocal(x, y,
                            windowPeer);
                    Component target = lastMouseEventPeer.getTarget();
                    postMouseExitedEvent(target, when, modifiers, lp,
                            absX, absY, clickCount, popupTrigger, button);
                }

                // Sometimes we may get MOUSE_EXITED after lastCommonMouseEventPeer is switched
                // to a peer from another window. So we must first check if this peer is
                // the same as lastWindowPeer
                if (lastCommonMouseEventPeer != null && lastCommonMouseEventPeer.getWindowPeerOrSelf() == windowPeer) {
                    lastCommonMouseEventPeer = null;
                }
                lastMouseEventPeer = null;
            }
        } else if (id == MouseEvent.MOUSE_ENTERED) {
            isMouseOver = true;
            if (targetPeer != null) {
                if (targetPeer.isEnabled()) {
                    Point lp = targetPeer.windowToLocal(x, y, windowPeer);
                    Component target = targetPeer.getTarget();
                    postMouseEnteredEvent(target, when, modifiers, lp,
                            absX, absY, clickCount, popupTrigger, button);
                }
                lastCommonMouseEventPeer = targetPeer;
                lastMouseEventPeer = targetPeer;
            }
        } else {
            PlatformWindow topmostPlatformWindow = toolkitApi.getPlatformWindowUnderMouse();
            LWWindowPeerAPI topmostWindowPeer =
                    topmostPlatformWindow != null ? topmostPlatformWindow.getPeer() : null;

            // topmostWindowPeer == null condition is added for the backward
            // compatibility. It can be removed when the
            // getTopmostPlatformWindowUnderMouse() method will be properly
            // implemented in CPlatformEmbeddedFrame class
            if (topmostWindowPeer == windowPeer || topmostWindowPeer == null) {
                generateMouseEnterExitEventsForComponents(when, button, x, y,
                        absX, absY, modifiers, clickCount, popupTrigger,
                        targetPeer);
            } else {
                LWComponentPeerAPI topmostTargetPeer = topmostWindowPeer.findPeerAt(r.x + x, r.y + y);
                topmostWindowPeer.getMouseEventDispatcher().generateMouseEnterExitEventsForComponents(when, button, x, y,
                        absX, absY, modifiers, clickCount, popupTrigger,
                        topmostTargetPeer);
            }

            // TODO: fill "bdata" member of AWTEvent

            int eventButtonMask = (button > 0) ? MouseEvent.getMaskForButton(button) : 0;
            int otherButtonsPressed = modifiers & ~eventButtonMask;

            // For pressed/dragged/released events OS X treats other
            // mouse buttons as if they were BUTTON2, so we do the same
            int targetIdx = (button > 3) ? MouseEvent.BUTTON2 - 1 : button - 1;

            // MOUSE_ENTERED/EXITED are generated for the components strictly under
            // mouse even when dragging. That's why we first update lastMouseEventPeer
            // based on initial targetPeer value and only then recalculate targetPeer
            // for MOUSE_DRAGGED/RELEASED events
            if (id == MouseEvent.MOUSE_PRESSED) {
                onMousePressed();
                if (otherButtonsPressed == 0) {
                    mouseClickButtons = eventButtonMask;
                } else {
                    mouseClickButtons |= eventButtonMask;
                }
                mouseDownTarget[targetIdx] = targetPeer;
            } else if (id == MouseEvent.MOUSE_DRAGGED) {
                // Cocoa dragged event has the information about which mouse
                // button is being dragged. Use it to determine the peer that
                // should receive the dragged event.
                targetPeer = mouseDownTarget[targetIdx];
                mouseClickButtons &= ~modifiers;
            } else if (id == MouseEvent.MOUSE_RELEASED) {
                // TODO: currently, mouse released event goes to the same component
                // that received corresponding mouse pressed event. For most cases,
                // it's OK, however, we need to make sure that our behavior is consistent
                // with 1.6 for cases where component in question have been
                // hidden/removed in between of mouse pressed/released events.
                targetPeer = mouseDownTarget[targetIdx];

                if ((modifiers & eventButtonMask) == 0) {
                    mouseDownTarget[targetIdx] = null;
                }

                // mouseClickButtons is updated below, after MOUSE_CLICK is sent
            }

            if (targetPeer == null) {
                //TODO This can happen if this window is invisible. this is correct behavior in this case?
                targetPeer = windowPeer;
            }

            Point lp = targetPeer.windowToLocal(x, y, windowPeer);
            if (targetPeer.isEnabled()) {
                MouseEvent event = new MouseEvent(targetPeer.getTarget(), id,
                        when, modifiers, lp.x, lp.y,
                        absX, absY, clickCount,
                        popupTrigger, button);
                windowPeer.postMouseEvent(event);
            }

            if (id == MouseEvent.MOUSE_RELEASED) {
                if ((mouseClickButtons & eventButtonMask) != 0
                        && targetPeer.isEnabled()) {
                    windowPeer.postMouseEvent(new MouseEvent(targetPeer.getTarget(),
                            MouseEvent.MOUSE_CLICKED,
                            when, modifiers,
                            lp.x, lp.y, absX, absY,
                            clickCount, popupTrigger, button));
                }
                mouseClickButtons &= ~eventButtonMask;
            }
        }
        toolkitApi.updateCursorLater((Window) windowPeer.getTarget());
    }

    void generateMouseEnterExitEventsForComponents(long when,
                                                   int button, int x, int y, int screenX, int screenY,
                                                   int modifiers, int clickCount, boolean popupTrigger,
                                                   final LWComponentPeerAPI targetPeer) {

        if (!isMouseOver || targetPeer == lastMouseEventPeer) {
            return;
        }

        // Generate Mouse Exit for components
        if (lastMouseEventPeer != null && lastMouseEventPeer.isEnabled()) {
            Point oldp = lastMouseEventPeer.windowToLocal(x, y, windowPeer);
            Component target = lastMouseEventPeer.getTarget();
            postMouseExitedEvent(target, when, modifiers, oldp, screenX, screenY,
                    clickCount, popupTrigger, button);
        }
        lastCommonMouseEventPeer = targetPeer;
        lastMouseEventPeer = targetPeer;

        // Generate Mouse Enter for components
        if (targetPeer != null && targetPeer.isEnabled()) {
            Point newp = targetPeer.windowToLocal(x, y, windowPeer);
            Component target = targetPeer.getTarget();
            postMouseEnteredEvent(target, when, modifiers, newp, screenX, screenY, clickCount, popupTrigger, button);
        }
    }

    private void postMouseEnteredEvent(Component target, long when, int modifiers,
                                       Point loc, int xAbs, int yAbs,
                                       int clickCount, boolean popupTrigger, int button) {

        windowPeer.postMouseEvent(new MouseEvent(target,
                MouseEvent.MOUSE_ENTERED,
                when, modifiers,
                loc.x, loc.y, xAbs, yAbs,
                clickCount, popupTrigger, button));
    }

    private void postMouseExitedEvent(Component target, long when, int modifiers,
                                      Point loc, int xAbs, int yAbs,
                                      int clickCount, boolean popupTrigger, int button) {

        windowPeer.postMouseEvent(new MouseEvent(target,
                MouseEvent.MOUSE_EXITED,
                when, modifiers,
                loc.x, loc.y, xAbs, yAbs,
                clickCount, popupTrigger, button));
    }

    public void notifyMouseWheelEvent(long when, int x, int y, int absX, int absY,
                                      int modifiers, int scrollType, int scrollAmount,
                                      int wheelRotation, double preciseWheelRotation) {
        // TODO: could we just use the last mouse event target here?
        Rectangle r = windowPeer.getBounds();
        // findPeerAt() expects parent coordinates
        final LWComponentPeerAPI targetPeer = windowPeer.findPeerAt(r.x + x, r.y + y);
        if (targetPeer == null || !targetPeer.isEnabled()) {
            return;
        }

        Point lp = targetPeer.windowToLocal(x, y, windowPeer);
        // TODO: fill "bdata" member of AWTEvent
        windowPeer.postMouseEvent(new MouseWheelEvent(targetPeer.getTarget(),
                MouseEvent.MOUSE_WHEEL,
                when, modifiers,
                lp.x, lp.y,
                absX, absY, /* absX, absY */
                0 /* clickCount */, false /* popupTrigger */,
                scrollType, scrollAmount,
                wheelRotation, preciseWheelRotation));

    }

    /**
     * Called when a mouse press event is being processed, before dispatching.
     */
    protected void onMousePressed() {
    }
}
