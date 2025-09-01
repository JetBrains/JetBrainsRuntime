/*
 * Copyright (c) 2004, 2025, Oracle and/or its affiliates. All rights reserved.
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
package sun.awt;

import java.awt.RenderingHints;

import static java.awt.RenderingHints.KEY_TEXT_ANTIALIASING;
import static java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT;
import static java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR;
import static java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB;
import static java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VBGR;
import static java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB;
import static java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.awt.color.ColorSpace;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.BufferedReader;
import java.io.IOException;

import jdk.internal.misc.InnocuousThread;
import sun.awt.X11.XBaseWindow;
import com.sun.java.swing.plaf.gtk.GTKConstants.TextDirection;
import sun.java2d.opengl.OGLRenderQueue;
import sun.util.logging.PlatformLogger;

public abstract class UNIXToolkit extends SunToolkit
{
    /** All calls into GTK should be synchronized on this lock */
    public static final Object GTK_LOCK = new Object();

    private static final int[] BAND_OFFSETS = { 0, 1, 2 };
    private static final int[] BAND_OFFSETS_ALPHA = { 0, 1, 2, 3 };
    private static final int DEFAULT_DATATRANSFER_TIMEOUT = 10000;


    // Allowed GTK versions
    public enum GtkVersions {
        ANY(0),
        GTK3(Constants.GTK3_MAJOR_NUMBER);

        static final class Constants {
            static final int GTK3_MAJOR_NUMBER = 3;
        }

        final int number;

        GtkVersions(int number) {
            this.number = number;
        }

        public static GtkVersions getVersion(int number) {
            switch (number) {
                case Constants.GTK3_MAJOR_NUMBER:
                    return GTK3;
                default:
                    return ANY;
            }
        }

        // major GTK version number
        public int getNumber() {
            return number;
        }
    }

    private Boolean nativeGTKAvailable;
    private Boolean nativeGTKLoaded;
    private BufferedImage tmpImage = null;
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.UNIXToolkit");

    private static void printError(String str) {
        log.fine(str);
    }

    @Override
    protected void initializeDesktopProperties() {
        initSystemPropertyWatcher();
    }

    public static int getDatatransferTimeout() {
        Integer dt = Integer.getInteger("sun.awt.datatransfer.timeout");
        if (dt == null || dt <= 0) {
            return DEFAULT_DATATRANSFER_TIMEOUT;
        } else {
            return dt;
        }
    }

    @Override
    public String getDesktop() {
        String gnome = "gnome";
        String gsi = System.getenv("GNOME_DESKTOP_SESSION_ID");
        if (gsi != null) {
            return gnome;
        }

        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        return (desktop != null && desktop.toLowerCase().contains(gnome))
                ? gnome : null;
    }

    /**
     * Returns true if the native GTK libraries are capable of being
     * loaded and are expected to work properly, false otherwise.  Note
     * that this method will not leave the native GTK libraries loaded if
     * they haven't already been loaded.  This allows, for example, Swing's
     * GTK L&F to test for the presence of native GTK support without
     * leaving the native libraries loaded.  To attempt long-term loading
     * of the native GTK libraries, use the loadGTK() method instead.
     */
    @Override
    public boolean isNativeGTKAvailable() {
        synchronized (GTK_LOCK) {
            if (nativeGTKLoaded != null) {
                // We've already attempted to load GTK, so just return the
                // status of that attempt.
                return nativeGTKLoaded;

            } else if (nativeGTKAvailable != null) {
                // We've already checked the availability of the native GTK
                // libraries, so just return the status of that attempt.
                return nativeGTKAvailable;

            } else {
                boolean success = check_gtk(getEnabledGtkVersion().getNumber());
                nativeGTKAvailable = success;
                return success;
            }
        }
    }

    /**
     * Loads the GTK libraries, if necessary.  The first time this method
     * is called, it will attempt to load the native GTK library.  If
     * successful, it leaves the library open and returns true; otherwise,
     * the library is left closed and returns false.  On future calls to
     * this method, the status of the first attempt is returned (a simple
     * lightweight boolean check, no native calls required).
     */
    public boolean loadGTK() {
        synchronized (GTK_LOCK) {
            if (nativeGTKLoaded == null) {
                nativeGTKLoaded = load_gtk(getEnabledGtkVersion().getNumber(),
                                                                isGtkVerbose());
            }
        }
        return nativeGTKLoaded;
    }

    /**
     * Overridden to handle GTK icon loading
     */
    @Override
    protected Object lazilyLoadDesktopProperty(String name) {
        if (name.startsWith("gtk.icon.")) {
            return lazilyLoadGTKIcon(name);
        }
        return super.lazilyLoadDesktopProperty(name);
    }

    /**
     * Load a native Gtk stock icon.
     *
     * @param longname a desktop property name. This contains icon name, size
     *        and orientation, e.g. {@code "gtk.icon.gtk-add.4.rtl"}
     * @return an {@code Image} for the icon, or {@code null} if the
     *         icon could not be loaded
     */
    protected Object lazilyLoadGTKIcon(String longname) {
        // Check if we have already loaded it.
        Object result = desktopProperties.get(longname);
        if (result != null) {
            return result;
        }

        // We need to have at least gtk.icon.<stock_id>.<size>.<orientation>
        String[] str = longname.split("\\.");
        if (str.length != 5) {
            return null;
        }

        // Parse out the stock icon size we are looking for.
        int size = 0;
        try {
            size = Integer.parseInt(str[3]);
        } catch (NumberFormatException nfe) {
            return null;
        }

        // Direction.
        TextDirection dir = ("ltr".equals(str[4]) ? TextDirection.LTR :
                                                    TextDirection.RTL);

        // Load the stock icon.
        BufferedImage img = getStockIcon(-1, str[2], size, dir.ordinal(), null);
        if (img != null) {
            // Create the desktop property for the icon.
            setDesktopProperty(longname, img);
        }
        return img;
    }

    private static volatile Boolean shouldDisableSystemTray = null;

    /**
     * There is an issue displaying the xembed icons in appIndicators
     * area with certain Gnome Shell versions.
     * To avoid any loss of quality of service, we are disabling
     * SystemTray support in such cases.
     *
     * @return true if system tray should be disabled
     */
    public boolean shouldDisableSystemTray() {
        Boolean result = shouldDisableSystemTray;
        if (result == null) {
            synchronized (GTK_LOCK) {
                result = shouldDisableSystemTray;
                if (result == null) {
                    if ("gnome".equals(getDesktop())) {
                        Integer gnomeShellMajorVersion = getGnomeShellMajorVersion();

                        if (gnomeShellMajorVersion == null
                                || gnomeShellMajorVersion < 45) {

                            return shouldDisableSystemTray = true;
                        }
                    }
                    shouldDisableSystemTray = result = false;
                }
            }
        }
        return result;
    }

    public Integer getGnomeShellMajorVersion() {
        try {
            Process process =
                new ProcessBuilder("/usr/bin/gnome-shell", "--version")
                        .start();
            try (BufferedReader reader = process.inputReader()) {
                if (process.waitFor(2, SECONDS) &&  process.exitValue() == 0) {
                    String line = reader.readLine();
                    if (line != null) {
                        String[] versionComponents = line
                                .replaceAll("[^\\d.]", "")
                                .split("\\.");

                        if (versionComponents.length >= 1) {
                            return Integer.parseInt(versionComponents[0]);
                        }
                    }
                }
            }
        } catch (IOException
                 | InterruptedException
                 | IllegalThreadStateException
                 | NumberFormatException ignored) {
        }

        return null;
    }

    /**
     * Returns a BufferedImage which contains the Gtk icon requested.  If no
     * such icon exists or an error occurs loading the icon the result will
     * be null.
     *
     * @param filename
     * @return The icon or null if it was not found or loaded.
     */
    public BufferedImage getGTKIcon(final String filename) {
        if (!loadGTK()) {
            return null;

        } else {
            // Call the native method to load the icon.
            synchronized (GTK_LOCK) {
                if (!load_gtk_icon(filename)) {
                    tmpImage = null;
                }
            }
        }
        // Return local image the callback loaded the icon into.
        return tmpImage;
    }

    /**
     * Returns a BufferedImage which contains the Gtk stock icon requested.
     * If no such stock icon exists the result will be null.
     *
     * @param widgetType one of WidgetType values defined in GTKNativeEngine or
     * -1 for system default stock icon.
     * @param stockId String which defines the stock id of the gtk item.
     * For a complete list reference the API at www.gtk.org for StockItems.
     * @param iconSize One of the GtkIconSize values defined in GTKConstants
     * @param direction One of the TextDirection values defined in
     * GTKConstants
     * @param detail Render detail that is passed to the native engine (feel
     * free to pass null)
     * @return The stock icon or null if it was not found or loaded.
     */
    public BufferedImage getStockIcon(final int widgetType, final String stockId,
                                final int iconSize, final int direction,
                                final String detail) {
        if (!loadGTK()) {
            return null;

        } else {
            // Call the native method to load the icon.
            synchronized (GTK_LOCK) {
                if (!load_stock_icon(widgetType, stockId, iconSize, direction, detail)) {
                    tmpImage = null;
                }
            }
        }
        // Return local image the callback loaded the icon into.
        return tmpImage;  // set by loadIconCallback
    }

    /**
     * This method is used by JNI as a callback from load_stock_icon.
     * Image data is passed back to us via this method and loaded into the
     * local BufferedImage and then returned via getStockIcon.
     *
     * Do NOT call this method directly.
     */
    public void loadIconCallback(byte[] data, int width, int height,
            int rowStride, int bps, int channels, boolean alpha) {
        // Reset the stock image to null.
        tmpImage = null;

        // Create a new BufferedImage based on the data returned from the
        // JNI call.
        DataBuffer dataBuf = new DataBufferByte(data, (rowStride * height));
        // Maybe test # channels to determine band offsets?
        WritableRaster raster = Raster.createInterleavedRaster(dataBuf,
                width, height, rowStride, channels,
                (alpha ? BAND_OFFSETS_ALPHA : BAND_OFFSETS), null);
        ColorModel colorModel = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB), alpha, false,
                ColorModel.TRANSLUCENT, DataBuffer.TYPE_BYTE);

        // Set the local image so we can return it later from
        // getStockIcon().
        tmpImage = new BufferedImage(colorModel, raster, false, null);
    }

    private static native boolean check_gtk(int version);
    private static native boolean load_gtk(int version, boolean verbose);
    private static native boolean unload_gtk();
    private native boolean load_gtk_icon(String filename);
    private native boolean load_stock_icon(int widget_type, String stock_id,
            int iconSize, int textDirection, String detail);

    private native void nativeSync();
    private static native int get_gtk_version();

    @Override
    public void sync() {
        // flush the X11 buffer
        nativeSync();
        // now flush the OGL pipeline (this is a no-op if OGL is not enabled)
        OGLRenderQueue.sync();
    }

    /*
     * This returns the value for the desktop property "awt.font.desktophints"
     * It builds this by querying the Gnome desktop properties to return
     * them as platform independent hints.
     * This requires that the Gnome properties have already been gathered.
     */
    public static final String FONTCONFIGAAHINT = "fontconfig/Antialias";

    @Override
    protected RenderingHints getDesktopAAHints() {

        Object aaValue = getDesktopProperty("gnome.Xft/Antialias");

        if (aaValue == null) {
            /* On a KDE desktop running KWin the rendering hint will
             * have been set as property "fontconfig/Antialias".
             * No need to parse further in this case.
             */
            aaValue = getDesktopProperty(FONTCONFIGAAHINT);
            if (aaValue != null) {
               return new RenderingHints(KEY_TEXT_ANTIALIASING, aaValue);
            } else {
                 return null; // no Gnome or KDE Desktop properties available.
            }
        }

        /* 0 means off, 1 means some ON. What would any other value mean?
         * If we require "1" to enable AA then some new value would cause
         * us to default to "OFF". I don't think that's the best guess.
         * So if its !=0 then lets assume AA.
         */
        boolean aa = ((aaValue instanceof Number)
                        && ((Number) aaValue).intValue() != 0);
        Object aaHint;
        if (aa) {
            String subpixOrder =
                (String)getDesktopProperty("gnome.Xft/RGBA");

            if (subpixOrder == null || subpixOrder.equals("none")) {
                aaHint = VALUE_TEXT_ANTIALIAS_ON;
            } else if (subpixOrder.equals("rgb")) {
                aaHint = VALUE_TEXT_ANTIALIAS_LCD_HRGB;
            } else if (subpixOrder.equals("bgr")) {
                aaHint = VALUE_TEXT_ANTIALIAS_LCD_HBGR;
            } else if (subpixOrder.equals("vrgb")) {
                aaHint = VALUE_TEXT_ANTIALIAS_LCD_VRGB;
            } else if (subpixOrder.equals("vbgr")) {
                aaHint = VALUE_TEXT_ANTIALIAS_LCD_VBGR;
            } else {
                /* didn't recognise the string, but AA is requested */
                aaHint = VALUE_TEXT_ANTIALIAS_ON;
            }
        } else {
            aaHint = VALUE_TEXT_ANTIALIAS_DEFAULT;
        }
        return new RenderingHints(KEY_TEXT_ANTIALIASING, aaHint);
    }

    private native boolean gtkCheckVersionImpl(int major, int minor,
        int micro);

    /**
     * Returns {@code true} if the GTK+ library is compatible with the given
     * version.
     *
     * @param major
     *            The required major version.
     * @param minor
     *            The required minor version.
     * @param micro
     *            The required micro version.
     * @return {@code true} if the GTK+ library is compatible with the given
     *         version.
     */
    public boolean checkGtkVersion(int major, int minor, int micro) {
        if (loadGTK()) {
            return gtkCheckVersionImpl(major, minor, micro);
        }
        return false;
    }

    public static GtkVersions getEnabledGtkVersion() {
        String version = System.getProperty("jdk.gtk.version");
        if ("3".equals(version)) {
            return GtkVersions.GTK3;
        }
        return GtkVersions.ANY;
    }

    public static GtkVersions getGtkVersion() {
        return GtkVersions.getVersion(get_gtk_version());
    }

    public static boolean isGtkVerbose() {
        return Boolean.getBoolean("jdk.gtk.verbose");
    }

    private static volatile Boolean isOnXWayland = null;

    private static boolean isOnXWayland() {
        Boolean result = isOnXWayland;
        if (result == null) {
            synchronized (GTK_LOCK) {
                result = isOnXWayland;
                if (result == null) {
                    final String display = System.getenv("WAYLAND_DISPLAY");
                    isOnXWayland = result = (display != null && !display.trim().isEmpty());
                }
            }
        }
        return result;
    }

    public static boolean isOnWayland() {
        return isOnXWayland;
    }

    private static native int isSystemDarkColorScheme();

    // Java-side fallback/theme detector for platforms where native detection is unavailable or incomplete
    private static int isSystemDarkColorSchemeJava() {
        // Try native first
        int nativeRes;
        try {
            nativeRes = isSystemDarkColorScheme();
            if (nativeRes >= 0) return nativeRes;
        } catch (Throwable ignored) {
            nativeRes = -1;
        }
        // Try KDE kdeglobals-based detection
        Boolean kdeDark = KDEThemeDetector.detectDarkFromKDEGlobals();
        if (kdeDark != null) {
            return kdeDark ? 1 : 0;
        }
        return nativeRes; // keep original result (-1) if nothing else worked
    }

    @Override
    public boolean isRunningOnXWayland() {
        return isOnXWayland();
    }

    private static final String OS_THEME_IS_DARK = "awt.os.theme.isDark";

    private static Thread systemPropertyWatcher = null;

    private static native boolean dbusInit();

    private void initSystemPropertyWatcher() {

        // Initialize OS_THEME_IS_DARK using Java-side detector as well
        try {
            int initial = isSystemDarkColorSchemeJava();
            if (initial >= 0) {
                setDesktopProperty(OS_THEME_IS_DARK, initial != 0);
            }
        } catch (Throwable t) {
            // ignore
        }
        @SuppressWarnings("removal")
        String dbusEnabled = System.getProperty("jbr.dbus.enabled", "true").toLowerCase();
        if (!"true".equals(dbusEnabled) || !dbusInit()) {
            return;
        }

        int initialSystemDarkColorScheme = isSystemDarkColorSchemeJava();
        if (initialSystemDarkColorScheme >= 0) {
            setDesktopProperty(OS_THEME_IS_DARK, initialSystemDarkColorScheme != 0);

            systemPropertyWatcher = InnocuousThread.newThread("SystemPropertyWatcher",
                    () -> {
                        while (true) {
                            try {
                                int isSystemDarkColorScheme = isSystemDarkColorSchemeJava();
                                if (isSystemDarkColorScheme >= 0) {
                                    setDesktopProperty(OS_THEME_IS_DARK, isSystemDarkColorScheme != 0);
                                }

                                Thread.sleep(1000);
                            } catch (Exception ignored) {
                            }
                        }
                    });
            systemPropertyWatcher.setDaemon(true);
            systemPropertyWatcher.start();
        }
    }

    // We rely on the X11 input grab mechanism, but for the Wayland session
    // it only works inside the XWayland server, so mouse clicks outside of it
    // will not be detected.
    // (window decorations, pure Wayland applications, desktop, etc.)
    //
    // As a workaround, we can dismiss menus when the window loses focus.
    //
    // However, there are "blind spots" though, which, when clicked, don't
    // transfer the focus away and don't dismiss the menu
    // (e.g. the window's own title or the area in the side dock without
    // application icons).
    private static final WindowFocusListener waylandWindowFocusListener;

    private static boolean containsWaylandWindowFocusListener(Window window) {
        if (window == null) {
            return false;
        }

        for (WindowFocusListener focusListener : window.getWindowFocusListeners()) {
            if (focusListener == waylandWindowFocusListener) {
                return true;
            }
        }

        return false;
    }

    static {
        if (isOnXWayland()) {
            waylandWindowFocusListener = new WindowAdapter() {
                @Override
                public void windowLostFocus(WindowEvent e) {
                    Window window = e.getWindow();
                    Window oppositeWindow = e.getOppositeWindow();

                    // The focus can move between the window calling the popup,
                    // and the popup window itself or its children.
                    // We only dismiss the popup in other cases.
                    if (oppositeWindow != null) {
                        if (containsWaylandWindowFocusListener(oppositeWindow.getOwner())) {
                            addWaylandWindowFocusListenerToWindow(oppositeWindow);
                            return;
                        }

                        Window owner = window.getOwner();
                        while (owner != null) {
                            if (owner == oppositeWindow) {
                                return;
                            }
                            owner = owner.getOwner();
                        }

                        if (window.getParent() == oppositeWindow) {
                            return;
                        }
                    }

                    window.removeWindowFocusListener(this);

                    // AWT
                    XBaseWindow.ungrabInput();

                    // Swing
                    window.dispatchEvent(new UngrabEvent(window));
                }
            };
        } else {
            waylandWindowFocusListener = null;
        }
    }

    private static void addWaylandWindowFocusListenerToWindow(Window window) {
        if (!containsWaylandWindowFocusListener(window)) {
            window.addWindowFocusListener(waylandWindowFocusListener);
            for (Window ownedWindow : window.getOwnedWindows()) {
                addWaylandWindowFocusListenerToWindow(ownedWindow);
            }
        }
    }

    @Override
    public void dismissPopupOnFocusLostIfNeeded(Window invoker) {
        if (!isRunningOnXWayland() || invoker == null) {
            return;
        }

        addWaylandWindowFocusListenerToWindow(invoker);
    }

    @Override
    public void dismissPopupOnFocusLostIfNeededCleanUp(Window invoker) {
        if (!isRunningOnXWayland() || invoker == null) {
            return;
        }

        invoker.removeWindowFocusListener(waylandWindowFocusListener);
        for (Window ownedWindow : invoker.getOwnedWindows()) {
            ownedWindow.removeWindowFocusListener(waylandWindowFocusListener);
        }
    }

    // Modular KDE theme detector reading kdeglobals using XDG directories
    private static final class KDEThemeDetector {
        private static final String ENV_XDG_CONFIG_HOME = "XDG_CONFIG_HOME";
        private static final String ENV_XDG_CONFIG_DIRS = "XDG_CONFIG_DIRS";
        private static final String DEFAULT_XDG_CONFIG_HOME_SUFFIX = "/.config";
        private static final String DEFAULT_XDG_CONFIG_DIRS = "/etc/xdg";

        static Boolean detectDarkFromKDEGlobals() {
            try {
                String currentDesktop = System.getenv("XDG_CURRENT_DESKTOP");
                if (currentDesktop == null || !currentDesktop.toLowerCase().contains("kde")) {
                    return null;
                }

                // 1) Check user-specific config first: $XDG_CONFIG_HOME or $HOME/.config
                for (java.nio.file.Path base : getCandidateConfigDirs(true)) {
                    Boolean v = detectInConfigBase(base);
                    if (v != null) return v;
                }
                // 2) Check system config dirs from XDG_CONFIG_DIRS
                for (java.nio.file.Path base : getCandidateConfigDirs(false)) {
                    Boolean v = detectInConfigBase(base);
                    if (v != null) return v;
                }
            } catch (Throwable t) {
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("KDEThemeDetector failed: " + t);
                }
            }
            return null;
        }

        private static Boolean detectInConfigBase(java.nio.file.Path base) throws IOException {
            if (base == null) return null;
            java.nio.file.Path kdeglobals = base.resolve("kdeglobals");
            if (java.nio.file.Files.isRegularFile(kdeglobals)) {
                // Parse kdeglobals for [General] ColorScheme
                String schemeName = readIniKey(kdeglobals, "General", "ColorScheme");
                if (schemeName != null && !schemeName.isEmpty()) {
                    Boolean byName = isSchemeNameDark(schemeName);
                    if (byName != null) return byName;
                    // Try to find scheme file under color-schemes
                    java.nio.file.Path schemeFile = base.resolve("color-schemes").resolve(schemeName + ".colors");
                    if (java.nio.file.Files.isRegularFile(schemeFile)) {
                        Boolean byColors = detectDarkFromSchemeFile(schemeFile);
                        if (byColors != null) return byColors;
                    }
                }
                // Fallback: try to infer from window background color keys in kdeglobals
                Boolean byColors = detectDarkFromSchemeFile(kdeglobals);
                if (byColors != null) return byColors;
            }
            return null;
        }

        private static java.util.List<java.nio.file.Path> getCandidateConfigDirs(boolean user) {
            java.util.List<java.nio.file.Path> res = new java.util.ArrayList<>();
            if (user) {
                String xdgHome = System.getenv(ENV_XDG_CONFIG_HOME);
                if (xdgHome != null && !xdgHome.trim().isEmpty()) {
                    res.add(java.nio.file.Paths.get(xdgHome));
                } else {
                    String home = System.getProperty("user.home");
                    if (home != null && !home.trim().isEmpty()) {
                        res.add(java.nio.file.Paths.get(home + DEFAULT_XDG_CONFIG_HOME_SUFFIX));
                    }
                }
            } else {
                String dirs = System.getenv(ENV_XDG_CONFIG_DIRS);
                if (dirs == null || dirs.trim().isEmpty()) {
                    dirs = DEFAULT_XDG_CONFIG_DIRS;
                }
                for (String part : dirs.split(":")) {
                    if (!part.isEmpty()) res.add(java.nio.file.Paths.get(part));
                }
            }
            return res;
        }

        private static String readIniKey(java.nio.file.Path file, String section, String key) throws IOException {
            String current = null;
            try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(file)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                    if (line.startsWith("[") && line.endsWith("]")) {
                        current = line.substring(1, line.length() - 1).trim();
                        continue;
                    }
                    if (current != null && current.equals(section)) {
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            String k = line.substring(0, eq).trim();
                            if (k.equals(key)) {
                                return line.substring(eq + 1).trim();
                            }
                        }
                    }
                }
            }
            return null;
        }

        private static Boolean isSchemeNameDark(String name) {
            String n = name.toLowerCase();
            if (n.contains("dark")) return Boolean.TRUE;
            if (n.contains("light")) return Boolean.FALSE;
            return null;
        }

        private static Boolean detectDarkFromSchemeFile(java.nio.file.Path file) throws IOException {
            // Look into standard color groups for background colors to approximate luminance
            String[] sections = new String[]{
                    "Colors:Window", "Colors:View", "Colors:Button"
            };
            String[] keys = new String[]{
                    "BackgroundNormal", "BackgroundAlternate"
            };
            for (String sec : sections) {
                for (String key : keys) {
                    String val = readIniKey(file, sec, key);
                    if (val != null) {
                        int[] rgb = parseKDEColor(val);
                        if (rgb != null) {
                            double luminance = (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255.0;
                            if (luminance < 0.5) return Boolean.TRUE; // dark
                            else return Boolean.FALSE; // light
                        }
                    }
                }
            }
            return null;
        }

        private static int[] parseKDEColor(String val) {
            // Formats like: R,G,B or R,G,B,A
            try {
                String[] parts = val.split(",");
                if (parts.length < 3) return null;
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                r = clamp(r); g = clamp(g); b = clamp(b);
                return new int[]{r, g, b};
            } catch (Exception e) {
                return null;
            }
        }

        private static int clamp(int v) {
            if (v < 0) return 0; if (v > 255) return 255; return v;
        }
    }
}
