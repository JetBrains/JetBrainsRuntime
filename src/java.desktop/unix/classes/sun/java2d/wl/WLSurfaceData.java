/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, 2022, JetBrains s.r.o.. All rights reserved.
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

package sun.java2d.wl;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import sun.awt.image.SunVolatileImage;
import sun.awt.wl.WLComponentPeer;
import sun.awt.wl.WLGraphicsConfig;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.util.logging.PlatformLogger;

public class WLSurfaceData extends SurfaceData {

  private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.wl.WLSurfaceData");
  private final WLComponentPeer peer;
  private final WLGraphicsConfig graphicsConfig;
  private final int depth;


  public native void initSurface(WLComponentPeer peer, int rgb, int width, int height);
  protected native void initOps(WLComponentPeer peer, WLGraphicsConfig gc, int depth);

  protected WLSurfaceData(WLComponentPeer peer,
                          WLGraphicsConfig gc,
                          SurfaceType sType,
                          ColorModel cm)
  {
    super(sType, cm);
    this.peer = peer;
    this.graphicsConfig = gc;
    this.depth = cm.getPixelSize();
    initOps(peer, graphicsConfig, depth);
  }

  /**
   * Method for instantiating a Window SurfaceData
   */
  public static WLSurfaceData createData(WLComponentPeer peer) {
    WLGraphicsConfig gc = getGC(peer);
    return new WLSurfaceData(peer, gc, gc.getSurfaceType(), peer.getColorModel());
  }


  public static WLGraphicsConfig getGC(WLComponentPeer peer) {
    if (peer != null) {
      return (WLGraphicsConfig) peer.getGraphicsConfiguration();
    } else {
      GraphicsEnvironment env =
          GraphicsEnvironment.getLocalGraphicsEnvironment();
      GraphicsDevice gd = env.getDefaultScreenDevice();
      return (WLGraphicsConfig)gd.getDefaultConfiguration();
    }
  }
  @Override
  public SurfaceData getReplacement() {
    return null;
  }

  @Override
  public GraphicsConfiguration getDeviceConfiguration() {
    return null;
  }

  @Override
  public Raster getRaster(int x, int y, int w, int h) {
    return null;
  }

  @Override
  public Rectangle getBounds() {
    Rectangle r = peer.getTarget().getBounds();
    r.x = r.y = 0;
    return r;
  }

  @Override
  public Object getDestination() {
    return peer.getTarget();
  }

  public static boolean isAccelerationEnabled() {
    return false;
  }

  public static SurfaceData createData(WLGraphicsConfig gc, int width, int height, ColorModel cm,
                                       SunVolatileImage vImg, long drawable, int opaque,
                                       boolean b) {
    log.info("Not implemented: WLSurfaceData.createData(WLGraphicsConfig,int,int,ColorModel," +
                                                       "SunVolatileImage,long,int,boolean)");
    return null;
  }

}
