import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefAppHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @key headful
 * @summary Tests that JCEF starts and loads empty page with no crash
 * @author Anton Tarasov
 * @run main JCEFStartupTest
 */
public class JCEFStartupTest {
    static final CefApp CEF_APP;
    static final CefClient CEF_CLIENT;

    static final CountDownLatch LATCH = new CountDownLatch(1);
    static volatile boolean PASSED;

    static {
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = false;
        settings.background_color = settings.new ColorType(255, 0, 255, 0);
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_ERROR;
        CefApp.startup();

        String JCEF_FRAMEWORKS_PATH = System.getProperty("java.home") + "/Frameworks";
        CefApp.addAppHandler(new CefAppHandlerAdapter(new String[] {
                "--framework-dir-path=" + JCEF_FRAMEWORKS_PATH + "/Chromium Embedded Framework.framework",
                "--browser-subprocess-path=" + JCEF_FRAMEWORKS_PATH + "/jcef Helper.app/Contents/MacOS/jcef Helper",
                "--disable-in-process-stack-traces"
        }) {});

        CEF_APP = CefApp.getInstance(settings);
        CEF_CLIENT = CEF_APP.createClient();
    }

    JCEFStartupTest() {
        JFrame frame = new JFrame("JCEF");

        CEF_CLIENT.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser cefBrowser, CefFrame cefFrame, CefRequest.TransitionType transitionType) {
                System.out.println("onLoadStart");
            }
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame cefFrame, int i) {
                System.out.println("onLoadEnd");

//                cefBrowser.close(false); <-- todo: makes jtreg fail on exit code
                PASSED = true;
                LATCH.countDown();
            }
            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame cefFrame, ErrorCode errorCode, String s, String s1) {
                System.out.println("onLoadError");
            }
        });

        CefBrowser browser = CEF_CLIENT.createBrowser("about:blank", false, false);
        frame.add(browser.getUIComponent());

        frame.setSize(640, 480);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(JCEFStartupTest::new);

        try {
            LATCH.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        CEF_CLIENT.dispose();
        CEF_APP.dispose();

        if (!PASSED) {
            throw new RuntimeException("Test FAILED!");
        }
        System.out.println("Test PASSED");
    }
}
