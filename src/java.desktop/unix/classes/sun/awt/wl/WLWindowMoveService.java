package sun.awt.wl;

import com.jetbrains.exported.JBRApi;
import sun.awt.AWTAccessor;
import sun.awt.WindowMoveService;

import java.awt.*;
import java.awt.peer.ComponentPeer;

public class WLWindowMoveService implements WindowMoveService {

    public WLWindowMoveService() {
        final var toolkit = Toolkit.getDefaultToolkit();
        final var ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        if (toolkit == null || ge == null
                || !toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")
                || !ge.getClass().getName().equals("sun.awt.wl.WLGraphicsEnvironment")) {
            throw new JBRApi.ServiceNotAvailableException("Supported only with WLToolkit and WLGraphicsEnvironment");
        }
    }

    @Override
    public void startMovingTogetherWithMouse(Window window, int mouseButton) {
        if (WLComponentPeer.isWlPopup(window)) return; // xdg_popup's do not support the necessary interface

        final AWTAccessor.ComponentAccessor acc = AWTAccessor.getComponentAccessor();
        ComponentPeer peer = acc.getPeer(window);
        if (peer instanceof WLComponentPeer wlPeer) {
            wlPeer.startDrag();
        } else {
            throw new IllegalArgumentException("AWT window must have WLComponentPeer as its peer");
        }
    }
}
