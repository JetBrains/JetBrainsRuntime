package sun.java2d.opengl;

import sun.java2d.SurfaceData;

import java.awt.*;
import java.awt.image.ColorModel;
import java.util.concurrent.atomic.AtomicBoolean;

public class CGLTextureWrapperSurfaceData extends CGLSurfaceData {

    public CGLTextureWrapperSurfaceData(CGLGraphicsConfig gc, Image image, long textureId) {
        super(null, gc, ColorModel.getRGBdefault(), RT_TEXTURE, 0, 0);

        OGLRenderQueue rq = OGLRenderQueue.getInstance();
        AtomicBoolean success = new AtomicBoolean(false);
        rq.lock();
        try {
            OGLContext.setScratchSurface(gc);
            rq.flushAndInvokeNow(() -> success.set(OGLSurfaceDataExt.initWithTexture(this, textureId)));
        } finally {
            rq.unlock();
        }

        if (!success.get()) {
            throw new IllegalArgumentException("Failed to init the surface data");
        }
    }

    @Override
    public SurfaceData getReplacement() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Rectangle getBounds() {
        return getNativeBounds();
    }

    @Override
    public Object getDestination() {
        return null;
    }

    @Override
    public void flush() {
        // reset the texture id first to avoid the texture deallocation
        OGLSurfaceDataExt.resetTextureId(this);
        super.flush();
    }
}
