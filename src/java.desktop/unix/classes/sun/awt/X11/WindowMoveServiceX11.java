package sun.awt.X11;

import com.jetbrains.exported.JBRApi;
import sun.awt.AWTAccessor;
import sun.awt.WindowMoveService;

import java.awt.*;
import java.awt.peer.ComponentPeer;
import java.util.Objects;

public class WindowMoveServiceX11 implements WindowMoveService {

    public WindowMoveServiceX11() {
        final var toolkit = Toolkit.getDefaultToolkit();
        final var ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        if (toolkit == null || ge == null
                || !toolkit.getClass().getName().equals("sun.awt.X11.XToolkit")
                || !ge.getClass().getName().equals("sun.awt.X11GraphicsEnvironment")) {
            throw new JBRApi.ServiceNotAvailableException("Supported only with XToolkit and X11GraphicsEnvironment");
        }

        if (!((XToolkit)Toolkit.getDefaultToolkit()).isWindowMoveSupported()) {
            throw new JBRApi.ServiceNotAvailableException("Window manager does not support _NET_WM_MOVE_RESIZE");
        }
    }

    @Override
    public void startMovingTogetherWithMouse(Window window, int mouseButton) {
        Objects.requireNonNull(window);

        final AWTAccessor.ComponentAccessor acc = AWTAccessor.getComponentAccessor();
        ComponentPeer peer = acc.getPeer(window);
        if (peer instanceof XWindowPeer xWindowPeer) {
            xWindowPeer.startMovingTogetherWithMouse(mouseButton);
        } else {
            throw new IllegalArgumentException("AWT window must have XWindowPeer as its peer");
        }
    }
}
