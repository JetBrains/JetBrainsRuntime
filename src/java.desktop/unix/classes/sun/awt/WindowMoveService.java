package sun.awt;

import com.jetbrains.exported.JBRApi;
import sun.awt.X11.WindowMoveServiceX11;
import sun.awt.wl.WLWindowMoveService;

import java.awt.*;

@JBRApi.Service
@JBRApi.Provides("WindowMove")
public interface WindowMoveService {

    private static WindowMoveService create() {
        JBRApi.ServiceNotAvailableException exception;
        try {
            return new WLWindowMoveService();
        } catch (JBRApi.ServiceNotAvailableException e) {
            exception = e;
        }
        try {
            return new WindowMoveServiceX11();
        } catch (JBRApi.ServiceNotAvailableException e) {
            exception.addSuppressed(e);
        }
        throw exception;
    }

    void startMovingTogetherWithMouse(Window window, int mouseButton);
}
