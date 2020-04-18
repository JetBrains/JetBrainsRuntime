import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefLifeSpanHandlerAdapter;

import javax.swing.*;
import java.awt.Component;

/**
 * @author tav
 */
public class JBCefBrowser {
    private final CefBrowser myCefBrowser;
    private final CefClient myCefClient;

    private volatile boolean myIsCefBrowserCreated;
    private volatile LoadDeferrer myLoadDeferrer;

    private static class LoadDeferrer {
        protected final String myHtml;
        protected final String myUrl;

        private LoadDeferrer(String html, String url) {
            myHtml = html;
            myUrl = url;
        }

        public static LoadDeferrer urlDeferrer(String url) {
            return new LoadDeferrer(null, url);
        }

        public static LoadDeferrer htmlDeferrer(String html, String url) {
            return new LoadDeferrer(html, url);
        }

        public void load(CefBrowser browser) {
            // JCEF demands async loading.
            SwingUtilities.invokeLater(
                    myHtml == null ?
                            () -> browser.loadURL(myUrl) :
                            () -> browser.loadString(myHtml, myUrl));
        }
    }

    public JBCefBrowser() {
        myCefClient = JBCefApp.getInstance().createClient();
        myCefBrowser = myCefClient.createBrowser("about:blank", false, false);

        myCefClient.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public void onAfterCreated(CefBrowser browser) {
                myIsCefBrowserCreated = true;
                LoadDeferrer loader = myLoadDeferrer;
                if (loader != null) {
                    loader.load(browser);
                    myLoadDeferrer = null;
                }
            }
        });

    }

    public Component getComponent() {
        return myCefBrowser.getUIComponent();
    }

    public void loadURL(String url) {
        if (myIsCefBrowserCreated) {
            myCefBrowser.loadURL(url);
        }
        else {
            myLoadDeferrer = LoadDeferrer.urlDeferrer(url);
        }
    }

    public void loadHTML(String html, String url) {
        if (myIsCefBrowserCreated) {
            myCefBrowser.loadString(html, url);
        }
        else {
            myLoadDeferrer = LoadDeferrer.htmlDeferrer(html, url);
        }
    }

    public void loadHTML(String html) {
        loadHTML(html, "about:blank");
    }

    public CefClient getCefClient() {
        return myCefClient;
    }

    public CefBrowser getCefBrowser() {
        return myCefBrowser;
    }

    public void dispose() {
//        myCefBrowser.close(false);
        myCefClient.dispose();
    }
}
