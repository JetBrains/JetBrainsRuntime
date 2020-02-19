// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.handler.CefAppHandlerAdapter;

import java.awt.*;
import java.io.File;
import java.io.IOException;
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
        if (!CefApp.startup()) {
            throw new RuntimeException("JCEF startup failed!");
        }
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
            String ALT_CEF_FRAMEWORK_DIR = System.getenv("ALT_CEF_FRAMEWORK_DIR");
            String ALT_CEF_BROWSER_SUBPROCESS = System.getenv("ALT_CEF_BROWSER_SUBPROCESS");
            if (ALT_CEF_FRAMEWORK_DIR == null || ALT_CEF_BROWSER_SUBPROCESS == null) {
                String CONTENTS_PATH = System.getProperty("java.home") + "/..";
                if (ALT_CEF_FRAMEWORK_DIR == null) {
                    ALT_CEF_FRAMEWORK_DIR = CONTENTS_PATH + "/Frameworks/Chromium Embedded Framework.framework";
                }
                if (ALT_CEF_BROWSER_SUBPROCESS == null) {
                    ALT_CEF_BROWSER_SUBPROCESS = CONTENTS_PATH + "/Helpers/jcef Helper.app/Contents/MacOS/jcef Helper";
                }
            }
            return new String[] {
                "--framework-dir-path=" + normalize(ALT_CEF_FRAMEWORK_DIR),
                "--browser-subprocess-path=" + normalize(ALT_CEF_BROWSER_SUBPROCESS),
                "--disable-in-process-stack-traces"
            };
        }

        // CEF does not accept ".." in path
        static String normalize(String path) {
            try {
                return new File(path).getCanonicalPath();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
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

