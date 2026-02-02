/*
 * TODO copyright
 */
package sun.awt.wl;

import sun.java2d.SurfaceData;
import sun.lwawt.LWWindowPeerAPI;
import sun.lwawt.PlatformWindow;

import java.awt.*;
import java.awt.event.FocusEvent;

public class WLPlatformWindow implements PlatformWindow {

    private WLComponentPeer peer;

    WLPlatformWindow() {
    }

    void setPeer(WLComponentPeer peer) {
        this.peer = peer;
    }

    @Override
    public void initialize(Window target, LWWindowPeerAPI peer, PlatformWindow owner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dispose() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setVisible(boolean visible) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTitle(String title) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        throw new UnsupportedOperationException();
    }

    @Override
    public GraphicsDevice getGraphicsDevice() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Point getLocationOnScreen() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Insets getInsets() {
        throw new UnsupportedOperationException();
    }

    @Override
    public FontMetrics getFontMetrics(Font f) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SurfaceData getScreenSurface() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SurfaceData replaceSurfaceData() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setModalBlocked(boolean blocked) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toFront() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toBack() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setMenuBar(MenuBar mb) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAlwaysOnTop(boolean value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateFocusableWindowState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean rejectFocusRequest(FocusEvent.Cause cause) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean requestWindowFocus() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isActive() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setResizable(boolean resizable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSizeConstraints(int minW, int minH, int maxW, int maxH) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateIconImages() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setOpacity(float opacity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setOpaque(boolean isOpaque) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void enterFullScreenMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void exitFullScreenMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFullScreenMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setWindowState(int windowState) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getLayerPtr() {
        throw new UnsupportedOperationException();
    }

    @Override
    public LWWindowPeerAPI getPeer() {
        return peer;
    }

    @Override
    public boolean isUnderMouse() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getWindowHandle() {
        throw new UnsupportedOperationException();
    }
}
