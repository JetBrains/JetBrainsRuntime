/*
TODO copyright
 */
package sun.lwawt;

import java.awt.*;
import java.awt.dnd.DropTarget;

public class LWToolkitAPI implements ToolkitAPI {

    private static final LWToolkitAPI INSTANCE = new LWToolkitAPI();

    private LWToolkitAPI() {
    }

    public static LWToolkitAPI getInstance() {
        return INSTANCE;
    }

    @Override
    public Object targetToPeer(Object target) {
        return LWToolkit.targetToPeer(target);
    }

    @Override
    public void targetDisposedPeer(Object target, Object peer) {
        LWToolkit.targetDisposedPeer(target, peer);
    }

    @Override
    public void postEvent(AWTEvent event) {
        LWToolkit.postEvent(event);
    }

    @Override
    public void flushOnscreenGraphics() {
        LWToolkit.flushOnscreenGraphics();
    }

    @Override
    public boolean needUpdateWindowAfterPaint() {
        return LWToolkit.getLWToolkit().needUpdateWindowAfterPaint();
    }

    @Override
    public void updateCursorImmediately() {
        LWToolkit.getLWToolkit().getCursorManager().updateCursor();
    }

    @Override
    public PlatformDropTarget createDropTarget(DropTarget dropTarget, Component component, LWComponentPeer<?, ?> peer) {
        return LWToolkit.getLWToolkit().createDropTarget(dropTarget, component, peer);
    }
}
