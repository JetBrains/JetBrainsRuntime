// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.handler.CefAppHandlerAdapter;

import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Anton Tarasov
 */
public abstract class JBCefApp {
    public static class OS {
        public static final boolean WINDOWS;
        public static final boolean LINUX;
        public static final boolean MAC;
        public static final boolean UNKNOWN;

        static {
            String name = System.getProperty("os.name").toLowerCase();
            WINDOWS = name.contains("windows");
            LINUX = name.contains("linux");
            MAC = name.contains("mac") || name.contains("darwin");
            UNKNOWN = !(WINDOWS || LINUX || MAC);
        }
    }

    private static JBCefApp INSTANCE;
    private final CefApp myCefApp;

    private static final AtomicBoolean ourInitialized = new AtomicBoolean(false);

    private JBCefApp() {
        CefApp.startup();
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = false;
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_ERROR;
        String[] appArgs = applyPlatformSettings(settings);
        CefApp.addAppHandler(new CefAppHandlerAdapter(appArgs) {});
        myCefApp = CefApp.getInstance(settings);
    }

    public static JBCefApp getInstance() {
        if (!ourInitialized.getAndSet(true)) {
            if (OS.MAC) {
                INSTANCE = new JBCefAppMac();
            }
            else if (OS.LINUX) {
                INSTANCE = new JBCefAppLinux();
            }
            else if (OS.WINDOWS) {
                INSTANCE = new JBCefAppWindows();
            }
            else {
                INSTANCE = null;
                assert false : "JBCefApp platform initialization failed";
            }
        }
        return INSTANCE;
    }

    protected abstract String[] applyPlatformSettings(CefSettings settings);

    private static class JBCefAppMac extends JBCefApp {
        @Override
        protected String[] applyPlatformSettings(CefSettings settings) {
            String JCEF_FRAMEWORKS_PATH = System.getProperty("java.home") + "/Frameworks";
            return new String[] {
                "--framework-dir-path=" + JCEF_FRAMEWORKS_PATH + "/Chromium Embedded Framework.framework",
                "--browser-subprocess-path=" + JCEF_FRAMEWORKS_PATH + "/jcef Helper.app/Contents/MacOS/jcef Helper",
                "--disable-in-process-stack-traces"
            };
        }
    }

    private static class JBCefAppWindows extends JBCefApp {
        @Override
        protected String[] applyPlatformSettings(CefSettings settings) {
            String JCEF_PATH = System.getProperty("java.home") + "/bin";
            settings.resources_dir_path = JCEF_PATH;
            settings.locales_dir_path = JCEF_PATH + "/locales";
            settings.browser_subprocess_path = JCEF_PATH + "/jcef_helper";
            return null;
        }
    }

    private static class JBCefAppLinux extends JBCefApp {
        @Override
        protected String[] applyPlatformSettings(CefSettings settings) {
            String JCEF_PATH = System.getProperty("java.home") + "/lib";
            settings.resources_dir_path = JCEF_PATH;
            settings.locales_dir_path = JCEF_PATH + "/locales";
            settings.browser_subprocess_path = JCEF_PATH + "/jcef_helper";
            return new String[] {
                "--force-device-scale-factor=" + sysScale()
            };
        }
    }

    public CefClient createClient() {
        return myCefApp.createClient();
    }

    public CefApp getCefApp() {
        return myCefApp;
    }

    public static double sysScale() {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform().getScaleX();
        } catch (NullPointerException ignore) {}
        return 1.0;
    }
}

