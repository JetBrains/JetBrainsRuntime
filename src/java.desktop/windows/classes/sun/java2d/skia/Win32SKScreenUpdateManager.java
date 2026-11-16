/*
 * Copyright 2026 JetBrains s.r.o.
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
package sun.java2d.skia;

import sun.awt.AWTAccessor;
import sun.awt.Win32GraphicsConfig;
import sun.awt.Win32GraphicsEnvironment;
import sun.awt.windows.WComponentPeer;
import sun.java2d.NullSurfaceData;
import sun.java2d.ScreenUpdateManager;
import sun.java2d.SurfaceData;

import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Screen surfaces of the Skia backend on Windows, after
 * {@code D3DScreenUpdateManager}: a replacement window surface (every bounds
 * change replaces the window surface) takes over the window's swap chain from
 * the surface the peer still holds, since DXGI allows one flip-model swap chain
 * per HWND; the component is repainted when the texture behind it is new.
 * <p>
 * A window Skia cannot render (flip chain requested, surface creation failed,
 * device given up) gets a GDI surface, but GDI can only take a window that has
 * not presented through Skia yet: once a flip-model swap chain has presented
 * into an HWND, nothing GDI draws into it is shown anymore, also after the swap
 * chain is released, and the window keeps showing its last frame, for as long
 * as the desktop composition that saw the chain lives. An adapter change (a
 * driver update) restarts the composition and the window's redirection surface
 * with it, so GDI shows again then; a device reset does not. Such a window is
 * reported ({@link #warnStale}) rather than handed over silently; only a new
 * device (a lost one's recovery) or a restarted composition can render it again.
 * <p>
 * A lost device (a driver reset, an adapter removal) is left behind the way
 * {@code D3DScreenUpdateManager} leaves a lost Direct3D device: the device's
 * display is told that it changed, so that its configurations are created anew
 * (with a new device) and the windows get Skia surfaces back on their next
 * surface replacement. Until then, what they draw is not shown (nothing through
 * a Skia surface of the lost device, nothing GDI draws into their windows) and
 * they show their last frame.
 */
public final class Win32SKScreenUpdateManager extends ScreenUpdateManager {

    /**
     * The windows that have had a Skia surface, which GDI cannot render anymore;
     * {@code true} once one has been reported since its last Skia surface
     * (weak: the peers are collectable).
     */
    private final Map<WComponentPeer, Boolean> presentedWindows = new WeakHashMap<>();

    /** Lost configurations whose recreation has been requested (weak: a lost configuration must be collectable). */
    private final Set<Win32SKGraphicsConfig> recovering = Collections.newSetFromMap(new WeakHashMap<>());

    // Recoveries, each within RECOVERY_HELD_NANOS of the one before; past
    // MAX_RECOVERY_ATTEMPTS the device is given up. A recovery counts as held only
    // after that time without another loss, not when a surface could be created on
    // the new device: a driver that keeps failing gives each new device a window
    // surface before it is lost again, and counting that as success recreated the
    // configuration every few frames forever.
    private int recoveryAttempts;
    private long lastRecoveryNanos;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final long RECOVERY_HELD_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** A recovery (displayChanged) is queued and has not run yet. */
    private boolean recoveryScheduled;

    /** {@code numBackBuffers} of a window without a flip chain. */
    private static final int NO_BACK_BUFFERS = 0;

    @Override
    public synchronized SurfaceData createScreenSurface(Win32GraphicsConfig graphicsConfig, WComponentPeer peer,
                                                        int numBackBuffers, boolean isResize) {
        // the peer still refers to the surface being replaced
        Win32SKSurfaceData previous = peer.getSurfaceData() instanceof Win32SKSurfaceData skiaSurface ? skiaSurface : null;
        Win32SKSurfaceData surfaceData = null;
        String failure;   // why the window is not on Skia; null while a recovery is under way
        if (!(graphicsConfig instanceof Win32SKGraphicsConfig skiaConfig)) {
            failure = "its screen has no Skia configuration";
        } else if (numBackBuffers != NO_BACK_BUFFERS) {
            failure = "a flip chain was requested";
        } else if (skiaConfig.isDeviceLost()) {
            failure = recover(skiaConfig) ? null : "the GPU device keeps getting lost";
        } else {
            surfaceData = Win32SKSurfaceData.createData(peer, skiaConfig, previous);
            failure = "Skia cannot create its surface";
        }
        if (surfaceData == null) {
            // the window's swap chain must not stay behind
            if (previous != null) {
                previous.flush();
            }
            if (failure != null) {
                warnStale(peer, failure);
            }
            return super.createScreenSurface(graphicsConfig, peer, numBackBuffers, isResize);
        }
        presentedWindows.put(peer, Boolean.FALSE);
        if (isResize) {
            // the new surface's texture is empty; the previous contents are gone
            // with the old one (as with a replaced D3D back buffer)
            Component target = (Component) peer.getTarget();
            Rectangle bounds = AWTAccessor.getComponentAccessor().getBounds(target);
            peer.handlePaint(0, 0, bounds.width, bounds.height);
        }
        return surfaceData;
    }

    /**
     * A window that has had a Skia surface and gets a GDI one is not updated anymore unless the
     * desktop composition restarted (see the class comment): reported once, until it is back on Skia.
     */
    private void warnStale(WComponentPeer peer, String failure) {
        if (presentedWindows.replace(peer, Boolean.FALSE, Boolean.TRUE)) {
            SKEnvironment.warn("Skia cannot render a " + peer.getTarget().getClass().getName() + " any more ("
                               + failure + "): GDI renders it, which a window that presented through a flip-model"
                               + " swap chain shows only once the desktop composition restarted (an adapter change"
                               + " does that, a device reset does not); until then it keeps its last frame");
        }
    }

    /**
     * A Skia window surface whose device is lost has the recovery started and is
     * replaced; until then its {@code Graphics} draw nothing: GDI could not show
     * it, and the lost device drops everything drawn into it.
     */
    @Override
    public synchronized Graphics2D createGraphics(SurfaceData surfaceData, WComponentPeer peer,
                                                  Color foregroundColor, Color backgroundColor, Font font) {
        if (surfaceData instanceof Win32SKSurfaceData skiaSurface && skiaSurface.isDeviceLost()) {
            recover((Win32SKGraphicsConfig) skiaSurface.getDeviceConfiguration());
            peer.replaceSurfaceDataLater();
            surfaceData = NullSurfaceData.theInstance;
        }
        return super.createGraphics(surfaceData, peer, foregroundColor, backgroundColor, font);
    }

    /**
     * Has the configurations of a lost device recreated, once per lost
     * configuration and a few times in a row at most (a recovery that holds for
     * {@link #RECOVERY_HELD_NANOS} resets the count). The environment's
     * {@code displayChanged} runs later on the event thread, as it does for a
     * real display change: it recreates the device objects, notifies the
     * top-level windows (new surfaces on the new device) and the volatile
     * images (moved to the new configuration). This manager is entered under
     * the peers' locks.
     *
     * @return false once the device is given up
     */
    private boolean recover(Win32SKGraphicsConfig graphicsConfig) {
        if (recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            return false;
        }
        if (graphicsConfig == null || !recovering.add(graphicsConfig) || recoveryScheduled) {
            return true;   // one recovery per loss, however many configurations the loss takes with it
        }
        long now = System.nanoTime();
        if (recoveryAttempts > 0 && recoveryAttempts <= MAX_RECOVERY_ATTEMPTS && now - lastRecoveryNanos > RECOVERY_HELD_NANOS) {
            recoveryAttempts = 0;   // the last recovery held
        }
        lastRecoveryNanos = now;
        if (++recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            // no other device to go to (the software adapter is not used: DawnSKPlatform's requestAdapter), and GDI
            // cannot render the windows Skia had
            SKEnvironment.warn("Skia gives up its GPU device after " + MAX_RECOVERY_ATTEMPTS
                               + " losses in a row; the windows it rendered are not updated any more");
            return false;
        }
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        if (environment instanceof Win32GraphicsEnvironment win32Environment) {
            recoveryScheduled = true;
            EventQueue.invokeLater(() -> {
                synchronized (this) {
                    recoveryScheduled = false;
                }
                win32Environment.displayChanged();
            });
        }
        return true;
    }
}
