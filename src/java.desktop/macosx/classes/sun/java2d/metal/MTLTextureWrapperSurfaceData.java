package sun.java2d.metal;

import sun.java2d.SurfaceData;

import java.awt.*;
import java.awt.image.ColorModel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Surface data for an existing texture
 */
public final class MTLTextureWrapperSurfaceData extends MTLSurfaceData {
    private final Image myImage;

    public MTLTextureWrapperSurfaceData(MTLGraphicsConfig gc, Image image, long pTexture) throws IllegalArgumentException {
        super(null, gc, gc.getColorModel(TRANSLUCENT), RT_TEXTURE, /*width=*/ 0, /*height=*/ 0);
        myImage = image;

        MTLRenderQueue rq = MTLRenderQueue.getInstance();
        AtomicBoolean success = new AtomicBoolean(false);

        rq.lock();
        try {
            MTLContext.setScratchSurface(gc);
            rq.flushAndInvokeNow(() -> success.set(MTLSurfaceDataExt.initWithTexture(this, pTexture)));
        } finally {
            rq.unlock();
        }

        if (!success.get()) {
            throw new IllegalArgumentException("Failed to init the surface data");
        }
    }

    @Override
    public SurfaceData getReplacement() {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Object getDestination() {
        return myImage;
    }

    @Override
    public Rectangle getBounds() {
        return getNativeBounds();
    }
}
