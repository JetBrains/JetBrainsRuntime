// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import com.jetbrains.cef.JCefAppConfig;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.handler.CefAppHandlerAdapter;

import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * @author Anton Tarasov
 */
public class JBCefApp {
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
    private static Function<CefApp.CefAppState, Void> ourCefAppStateHandler;

    private final CefApp myCefApp;

    private static final AtomicBoolean ourInitialized = new AtomicBoolean(false);

    private JBCefApp(JCefAppConfig config) {
        if (!CefApp.startup(new String[]{})) {
            throw new RuntimeException("JCEF startup failed!");
        }
        CefSettings settings = config.getCefSettings();
        settings.windowless_rendering_enabled = false;
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_ERROR;
        CefApp.addAppHandler(new CefAppHandlerAdapter(config.getAppArgs()) {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                if (ourCefAppStateHandler != null) {
                    ourCefAppStateHandler.apply(state);
                    return;
                }
                super.stateHasChanged(state);
            }
        });
        myCefApp = CefApp.getInstance(settings);
    }

    public static JBCefApp getInstance() {
        if (!ourInitialized.getAndSet(true)) {
            JCefAppConfig config = null;
            try {
                config = JCefAppConfig.getInstance();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            INSTANCE = config != null ? new JBCefApp(config) : null;
        }
        return INSTANCE;
    }

    public CefClient createClient() {
        return myCefApp.createClient();
    }

    public CefApp getCefApp() {
        return myCefApp;
    }

    public static void setCefAppStateHandler(Function<CefApp.CefAppState, Void> stateHandler) {
        if (ourInitialized.get()) {
            throw new IllegalStateException("JBCefApp has already been init'ed");
        }
        ourCefAppStateHandler = stateHandler;
    }

    public static double sysScale() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform().getScaleX();
        } catch (NullPointerException ignore) {}
        return 1.0;
    }
}

