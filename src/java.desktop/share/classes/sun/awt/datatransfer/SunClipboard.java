/*
 * Copyright (c) 1999, 2015, Oracle and/or its affiliates. All rights reserved.
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

package sun.awt.datatransfer;

import java.awt.EventQueue;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.FlavorTable;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorListener;
import java.awt.datatransfer.FlavorEvent;
import java.awt.datatransfer.UnsupportedFlavorException;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;

import java.io.IOException;

import sun.awt.AppContext;
import sun.awt.PeerEvent;
import sun.awt.SunToolkit;


/**
 * Serves as a common, helper superclass for the Win32 and X11 system
 * Clipboards.
 *
 * @author Danila Sinopalnikov
 * @author Alexander Gerasimov
 *
 * @since 1.3
 */
public abstract class SunClipboard extends Clipboard
    implements PropertyChangeListener {

    private AppContext contentsContext = null;

    private final Object CLIPBOARD_FLAVOR_LISTENER_KEY;

    /**
     * A number of {@code FlavorListener}s currently registered
     * on this clipboard across all {@code AppContext}s.
     */
    private volatile int numberOfFlavorListeners = 0;

    /**
     * A set of {@code DataFlavor}s that is available on this clipboard. It is
     * used for tracking changes of {@code DataFlavor}s available on this
     * clipboard. Can be {@code null}.
     */
    private volatile long[] currentFormats;

    public SunClipboard(String name) {
        super(name);
        CLIPBOARD_FLAVOR_LISTENER_KEY = new StringBuffer(name + "_CLIPBOARD_FLAVOR_LISTENER_KEY");
    }

    public synchronized void setContents(Transferable contents,
                                         ClipboardOwner owner) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#setContents(contents={0}, owner={1})...", contents, owner);

        try {
            // 4378007 : Toolkit.getSystemClipboard().setContents(null, null)
            // should throw NPE
            if (contents == null) {
                throw new NullPointerException("contents");
            }

            initContext();

            final ClipboardOwner oldOwner = this.owner;
            final Transferable oldContents = this.contents;

            logInfo("     oldOwner={0} ; oldContents={1}.", oldOwner, oldContents);

            try {
                this.owner = owner;
                this.contents = new TransferableProxy(contents, true);

                setContentsNative(contents);
            } finally {
                if (oldOwner != null && oldOwner != owner) {
                    logInfo("     oldOwner != null && oldOwner != owner.");
                    EventQueue.invokeLater(() -> oldOwner.lostOwnership(SunClipboard.this, oldContents));
                }
            }

            logInfo("<- sun.awt.datatransfer.SunClipboard#setContents(contents={0}, owner={1}).", contents, owner);
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#setContents(contents=%s, owner=%s): an exception occurred.".formatted(contents, owner),
                err
            );
            throw err;
        }
    }

    private synchronized void initContext() {
        logInfo("-> sun.awt.datatransfer.SunClipboard#initContext()...");

        try {
            final AppContext context = AppContext.getAppContext();

            logInfo("     this.contentsContext={0}.", this.contentsContext);
            logInfo("     context={0}.", context);

            if (contentsContext != context) {
                logInfo("     contentsContext != context.");

                // Need to synchronize on the AppContext to guarantee that it cannot
                // be disposed after the check, but before the listener is added.
                synchronized (context) {
                    if (context.isDisposed()) {
                        throw new IllegalStateException("Can't set contents from disposed AppContext");
                    }
                    context.addPropertyChangeListener
                            (AppContext.DISPOSED_PROPERTY_NAME, this);
                }
                if (contentsContext != null) {
                    contentsContext.removePropertyChangeListener
                            (AppContext.DISPOSED_PROPERTY_NAME, this);
                }
                contentsContext = context;
            }

            logInfo("<- sun.awt.datatransfer.SunClipboard#initContext().");
        } catch (Throwable err) {
            logSevere("<- sun.awt.datatransfer.SunClipboard#initContext(): an exception occurred.", err);
            throw err;
        }
    }

    public synchronized Transferable getContents(Object requestor) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#getContents(requestor={0})...", requestor);

        try {
            logInfo("     this.contents={0}.", this.contents);

            if (contents != null) {
                logInfo("<- sun.awt.datatransfer.SunClipboard#getContents(requestor={0}): returning {1}.",
                        requestor, contents);
                return contents;
            }

            final var result = new ClipboardTransferable(this);

            logInfo("<- sun.awt.datatransfer.SunClipboard#getContents(requestor={0}): returning {1}.",
                    requestor, result);

            return result;
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#getContents(requestor=%s): an exception occurred.".formatted(requestor),
                err
            );
            throw err;
        }
    }


    /**
     * @return the contents of this clipboard if it has been set from the same
     *         AppContext as it is currently retrieved or null otherwise
     * @since 1.5
     */
    protected synchronized Transferable getContextContents() {
        logInfo("-> sun.awt.datatransfer.SunClipboard#getContextContents()...");

        try {
            AppContext context = AppContext.getAppContext();
            logInfo("     context={0}.", context);

            final var result = (context == contentsContext) ? contents : null;

            logInfo("<- sun.awt.datatransfer.SunClipboard#getContextContents(): returning {0}.", result);
            return result;
        } catch (Throwable err) {
            logSevere("<- sun.awt.datatransfer.SunClipboard#getContextContents(): an exception occurred.", err);
            throw err;
        }
    }


    /**
     * @see java.awt.datatransfer.Clipboard#getAvailableDataFlavors
     * @since 1.5
     */
    public DataFlavor[] getAvailableDataFlavors() {
        logInfo("-> sun.awt.datatransfer.SunClipboard#getAvailableDataFlavors()...");

        try {
            Transferable cntnts = getContextContents();
            logInfo("     cntnts={0}.", cntnts);

            if (cntnts != null) {
                final DataFlavor[] result = cntnts.getTransferDataFlavors();

                logInfo("<- sun.awt.datatransfer.SunClipboard#getAvailableDataFlavors(): returning {0}.",
                        logFormatArray(result));
                return result;
            }

            long[] formats = getClipboardFormatsOpenClose();
            logInfo("     formats={0}.", logFormatArray(formats));

            final var result = DataTransferer.getInstance().getFlavorsForFormatsAsArray(formats, getDefaultFlavorTable());

            logInfo("<- sun.awt.datatransfer.SunClipboard#getAvailableDataFlavors(): returning {0}.",
                    logFormatArray(result));
            return result;
        } catch (Throwable err) {
            logSevere("<- sun.awt.datatransfer.SunClipboard#getAvailableDataFlavors(): an exception occurred.", err);
            throw err;
        }
    }

    /**
     * @see java.awt.datatransfer.Clipboard#isDataFlavorAvailable
     * @since 1.5
     */
    public boolean isDataFlavorAvailable(DataFlavor flavor) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#isDataFlavorAvailable(flavor={0})...", flavor);

        try {
            if (flavor == null) {
                throw new NullPointerException("flavor");
            }

            Transferable cntnts = getContextContents();
            logInfo("     cntnts={0}.", cntnts);

            if (cntnts != null) {
                final var result = cntnts.isDataFlavorSupported(flavor);

                logInfo("<- sun.awt.datatransfer.SunClipboard#isDataFlavorAvailable(flavor={0}): returning {1}.",
                        flavor, result);
                return result;
            }

            long[] formats = getClipboardFormatsOpenClose();
            logInfo("     formats={0}.", logFormatArray(formats));

            final var result = formatArrayAsDataFlavorSet(formats).contains(flavor);

            logInfo("<- sun.awt.datatransfer.SunClipboard#isDataFlavorAvailable(flavor={0}): returning {1}.",
                    flavor, result);
            return result;
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#isDataFlavorAvailable(flavor=%s): an exception occurred.".formatted(flavor),
                err
            );
            throw err;
        }
    }

    /**
     * @see java.awt.datatransfer.Clipboard#getData
     * @since 1.5
     */
    public Object getData(DataFlavor flavor)
        throws UnsupportedFlavorException, IOException {

        logInfo("-> sun.awt.datatransfer.SunClipboard#getData(flavor={0})...", flavor);

        try {
            if (flavor == null) {
                throw new NullPointerException("flavor");
            }

            Transferable cntnts = getContextContents();
            logInfo("     cntnts={0}.", cntnts);

            if (cntnts != null) {
                final var result = cntnts.getTransferData(flavor);
                logInfo("<- sun.awt.datatransfer.SunClipboard#getData(flavor={0}): returning {1}.", flavor, result);
                return result;
            }

            long format = 0;
            byte[] data = null;
            Transferable localeTransferable = null;

            try {
                openClipboard(null);

                long[] formats = getClipboardFormats();
                logInfo("     formats={0}.", logFormatArray(formats));

                Long lFormat = DataTransferer.getInstance().
                        getFlavorsForFormats(formats, getDefaultFlavorTable()).get(flavor);
                logInfo("     lFormat={0}.", lFormat);

                if (lFormat == null) {
                    throw new UnsupportedFlavorException(flavor);
                }

                format = lFormat.longValue();
                logInfo("     format={0}.", format);

                data = getClipboardData(format);
                logInfo("     data={0}.", logFormatArray(data));

                if (DataTransferer.getInstance().isLocaleDependentTextFormat(format)) {
                    localeTransferable = createLocaleTransferable(formats);
                }
                logInfo("     localeTransferable={0}.", localeTransferable);
            } finally {
                closeClipboard();
            }

            final var result = DataTransferer.getInstance().translateBytes(data, flavor, format, localeTransferable);

            logInfo("<- sun.awt.datatransfer.SunClipboard#getData(flavor={0}): returning {1}.", flavor, result);
            return result;
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#getData(flavor=%s): an exception occurred.".formatted(flavor),
                err
            );
            throw err;
        }
    }

    /**
     * The clipboard must be opened.
     *
     * @since 1.5
     */
    protected Transferable createLocaleTransferable(long[] formats) throws IOException {
        logWarning("-> sun.awt.datatransfer.SunClipboard#createLocaleTransferable(formats={0})...",
                   logFormatArray(formats));

        logWarning("     This method does nothing, has it been called by mistake?");

        logInfo("<- sun.awt.datatransfer.SunClipboard#createLocaleTransferable(formats={0}): returning null.",
                logFormatArray(formats));
        return null;
    }

    /**
     * @throws IllegalStateException if the clipboard has not been opened
     */
    public void openClipboard(SunClipboard newOwner) {
        logWarning("-> sun.awt.datatransfer.SunClipboard#openClipboard(newOwner={0})...",
                    newOwner);

        logWarning("     This method does nothing, has it been called by mistake?");

        logInfo("<- sun.awt.datatransfer.SunClipboard#openClipboard(newOwner={0}).", newOwner);
    }
    public void closeClipboard() {
        logWarning("-> sun.awt.datatransfer.SunClipboard#closeClipboard()...");

        logWarning("     This method does nothing, has it been called by mistake?");

        logInfo("<- sun.awt.datatransfer.SunClipboard#closeClipboard().");
    }

    public abstract long getID();

    public void propertyChange(PropertyChangeEvent evt) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#propertyChange(evt={0})...", evt);

        try {
            if (AppContext.DISPOSED_PROPERTY_NAME.equals(evt.getPropertyName()) &&
                    Boolean.TRUE.equals(evt.getNewValue())) {
                logInfo("     AppContext.DISPOSED_PROPERTY_NAME.equals(evt.getPropertyName()) && Boolean.TRUE.equals(evt.getNewValue()).");

                final AppContext disposedContext = (AppContext) evt.getSource();
                logInfo("     disposedContext={0}.", disposedContext);

                lostOwnershipLater(disposedContext);
            }

            logInfo("<- sun.awt.datatransfer.SunClipboard#propertyChange(evt={0}).", evt);
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#propertyChange(evt=%s): an exception occurred.".formatted(evt),
                err
            );
            throw err;
        }
    }

    protected void lostOwnershipImpl() {
        logInfo("-> sun.awt.datatransfer.SunClipboard#lostOwnershipImpl()...");

        try {
            lostOwnershipLater(null);
            logInfo("<- sun.awt.datatransfer.SunClipboard#lostOwnershipImpl().");
        } catch (Throwable err) {
            logSevere("<- sun.awt.datatransfer.SunClipboard#lostOwnershipImpl(): an exception occurred.", err);
            throw err;
        }
    }

    /**
     * Clears the clipboard state (contents, owner and contents context) and
     * notifies the current owner that ownership is lost. Does nothing if the
     * argument is not {@code null} and is not equal to the current
     * contents context.
     *
     * @param disposedContext the AppContext that is disposed or
     *        {@code null} if the ownership is lost because another
     *        application acquired ownership.
     */
    protected void lostOwnershipLater(final AppContext disposedContext) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#lostOwnershipLater(disposedContext={0})...", disposedContext);

        try {
            final AppContext context = this.contentsContext;
            logInfo("     context={0}.", context);

            if (context == null) {
                logInfo("<- sun.awt.datatransfer.SunClipboard#lostOwnershipLater(disposedContext={0}).", disposedContext);
                return;
            }

            SunToolkit.postEvent(context, new PeerEvent(this, () -> lostOwnershipNow(disposedContext),
                    PeerEvent.PRIORITY_EVENT));

            logInfo("<- sun.awt.datatransfer.SunClipboard#lostOwnershipLater(disposedContext={0}).", disposedContext);
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#lostOwnershipLater(disposedContext=%s): an exception occurred.".formatted(disposedContext),
                err
            );
            throw err;
        }
    }

    protected void lostOwnershipNow(final AppContext disposedContext) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#lostOwnershipNow(disposedContext={0})...", disposedContext);

        try {
            final SunClipboard sunClipboard = SunClipboard.this;
            logInfo("     sunClipboard={0}.", sunClipboard);

            ClipboardOwner owner = null;
            Transferable contents = null;

            synchronized (sunClipboard) {
                final AppContext context = sunClipboard.contentsContext;
                logInfo("     context={0}.", context);

                if (context == null) {
                    logInfo("     context == null.");
                    logInfo("<- sun.awt.datatransfer.SunClipboard#lostOwnershipNow(disposedContext={0}).", disposedContext);
                    return;
                }

                if (disposedContext == null || context == disposedContext) {
                    logInfo("     disposedContext == null || context == disposedContext.");

                    owner = sunClipboard.owner;
                    logInfo("     owner={0}.", owner);

                    contents = sunClipboard.contents;
                    logInfo("     contents={0}.", contents);

                    sunClipboard.contentsContext = null;
                    sunClipboard.owner = null;
                    sunClipboard.contents = null;
                    sunClipboard.clearNativeContext();
                    context.removePropertyChangeListener
                            (AppContext.DISPOSED_PROPERTY_NAME, sunClipboard);
                } else {
                    logInfo("     disposedContext != null && context != disposedContext.");
                    logInfo("<- sun.awt.datatransfer.SunClipboard#lostOwnershipNow(disposedContext={0}).", disposedContext);
                    return;
                }
            }
            if (owner != null) {
                logInfo("     owner != null.");
                owner.lostOwnership(sunClipboard, contents);
            }

            logInfo("<- sun.awt.datatransfer.SunClipboard#lostOwnershipNow(disposedContext={0}).", disposedContext);
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#lostOwnershipNow(disposedContext=%s): an exception occurred.".formatted(disposedContext),
                err
            );
            throw err;
        }
    }


    protected abstract void clearNativeContext();

    protected abstract void setContentsNative(Transferable contents);

    /**
     * @since 1.5
     */
    protected long[] getClipboardFormatsOpenClose() {
        logInfo("-> sun.awt.datatransfer.SunClipboard#getClipboardFormatsOpenClose()...");

        try {
            try {
                openClipboard(null);

                final var result = getClipboardFormats();

                logInfo("<- sun.awt.datatransfer.SunClipboard#getClipboardFormatsOpenClose(): returning {0}.",
                        logFormatArray(result));
                return result;
            } finally {
                closeClipboard();
            }
        } catch (Throwable err) {
            logSevere("<- sun.awt.datatransfer.SunClipboard#getClipboardFormatsOpenClose(): an exception occurred.", err);
            throw err;
        }
    }

    /**
     * Returns zero-length array (not null) if the number of available formats is zero.
     *
     * @throws IllegalStateException if formats could not be retrieved
     */
    protected abstract long[] getClipboardFormats();

    protected abstract byte[] getClipboardData(long format) throws IOException;


    private static Set<DataFlavor> formatArrayAsDataFlavorSet(long[] formats) {
        return (formats == null) ? null :
                DataTransferer.getInstance().
                getFlavorsForFormatsAsSet(formats, getDefaultFlavorTable());
    }


    public synchronized void addFlavorListener(FlavorListener listener) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#addFlavorListener(listener={0})...", listener);

        try {
            if (listener == null) {
                logInfo("     listener=null.");
                logInfo("<- sun.awt.datatransfer.SunClipboard#addFlavorListener(listener=null)...");
                return;
            }
            AppContext appContext = AppContext.getAppContext();
            logInfo("     appContext={0}.", appContext);

            Set<FlavorListener> flavorListeners = getFlavorListeners(appContext);
            if (flavorListeners == null) {
                logInfo("     flavorListeners=null.");
                flavorListeners = new HashSet<>();
                appContext.put(CLIPBOARD_FLAVOR_LISTENER_KEY, flavorListeners);
            }
            flavorListeners.add(listener);

            logInfo("     flavorListeners={0}.", flavorListeners);

            if (numberOfFlavorListeners++ == 0) {
                logInfo("     numberOfFlavorListeners++ == 0.");
                long[] currentFormats = null;
                try {
                    openClipboard(null);
                    currentFormats = getClipboardFormats();
                } catch (final IllegalStateException ignored) {
                } finally {
                    closeClipboard();
                }
                this.currentFormats = currentFormats;
                logInfo("     this.currentFormats={0}.", logFormatArray(this.currentFormats));

                registerClipboardViewerChecked();
            }

            logInfo("<- sun.awt.datatransfer.SunClipboard#addFlavorListener(listener={0}).", listener);
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#addFlavorListener(listener=%s): an exception occurred.".formatted(listener),
                err
            );
            throw err;
        }
    }

    public synchronized void removeFlavorListener(FlavorListener listener) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#removeFlavorListener(listener={0})...", listener);

        try {
            if (listener == null) {
                logInfo("<- sun.awt.datatransfer.SunClipboard#removeFlavorListener(listener=null).");
                return;
            }

            Set<FlavorListener> flavorListeners = getFlavorListeners(AppContext.getAppContext());
            logInfo("     flavorListeners={0}.", flavorListeners);
            if (flavorListeners == null) {
                //else we throw NullPointerException, but it is forbidden
                logInfo("<- sun.awt.datatransfer.SunClipboard#removeFlavorListener(listener={0}).", listener);
                return;
            }
            if (flavorListeners.remove(listener) && --numberOfFlavorListeners == 0) {
                logInfo("     flavorListeners.remove(listener) && --numberOfFlavorListeners == 0.");
                unregisterClipboardViewerChecked();
                currentFormats = null;
            }

            logInfo("<- sun.awt.datatransfer.SunClipboard#removeFlavorListener(listener={0}).", listener);
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#removeFlavorListener(listener=%s): an exception occurred.".formatted(listener),
                err
            );
            throw err;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<FlavorListener> getFlavorListeners(AppContext appContext) {
        logInfo("-> sun.awt.datatransfer.SunClipboard#getFlavorListeners(appContext={0})...", appContext);

        try {
            final var result = (Set<FlavorListener>) appContext.get(CLIPBOARD_FLAVOR_LISTENER_KEY);

            logInfo("<- sun.awt.datatransfer.SunClipboard#getFlavorListeners(appContext={0}): returning {1}.",
                    appContext, result);
            return result;
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#getFlavorListeners(appContext=%s): an exception occurred.".formatted(appContext),
                err
            );
            throw err;
        }
    }

    public synchronized FlavorListener[] getFlavorListeners() {
        logInfo("-> sun.awt.datatransfer.SunClipboard#getFlavorListeners()...");

        try {
            Set<FlavorListener> flavorListeners = getFlavorListeners(AppContext.getAppContext());
            logInfo("     flavorListeners={0}.", flavorListeners);

            final var result = flavorListeners == null ? new FlavorListener[0]
                               : flavorListeners.toArray(new FlavorListener[flavorListeners.size()]);

            logInfo("<- sun.awt.datatransfer.SunClipboard#getFlavorListeners(): returning {0}.",
                    logFormatArray(result));
            return result;
        } catch (Throwable err) {
            logSevere("<- sun.awt.datatransfer.SunClipboard#getFlavorListeners(): an exception occurred.", err);
            throw err;
        }
    }

    public boolean areFlavorListenersRegistered() {
        logInfo("-> sun.awt.datatransfer.SunClipboard#areFlavorListenersRegistered()...");

        logInfo("     this.numberOfFlavorListeners={0}.", this.numberOfFlavorListeners);

        final var result = (numberOfFlavorListeners > 0);

        logInfo("<- sun.awt.datatransfer.SunClipboard#areFlavorListenersRegistered(): returning {0}.", result);
        return result;
    }

    protected abstract void registerClipboardViewerChecked();

    protected abstract void unregisterClipboardViewerChecked();

    /**
     * Checks change of the {@code DataFlavor}s and, if necessary,
     * posts notifications on {@code FlavorEvent}s to the
     * AppContexts' EDTs.
     * The parameter {@code formats} is null iff we have just
     * failed to get formats available on the clipboard.
     *
     * @param formats data formats that have just been retrieved from
     *        this clipboard
     */
    protected final void checkChange(final long[] formats) {
        final var formatsStr = logFormatArray(formats);
        logInfo("-> sun.awt.datatransfer.SunClipboard#checkChange(formats={0})...", formatsStr);

        try {
            logInfo("     this.currentFormats={0}.", logFormatArray(this.currentFormats));

            if (Arrays.equals(formats, currentFormats)) {
                // we've been able to successfully get available on the clipboard
                // DataFlavors this and previous time and they are coincident;
                // don't notify
                logInfo("     Arrays.equals(formats, currentFormats)");
                logInfo("<- sun.awt.datatransfer.SunClipboard#checkChange(formats={0}).", formatsStr);
                return;
            }
            currentFormats = formats;

            final var appContexts = AppContext.getAppContexts();
            logInfo("     appContexts={0}.", appContexts);

            for (final AppContext appContext : appContexts) {
                if (appContext == null || appContext.isDisposed()) {
                    continue;
                }
                Set<FlavorListener> flavorListeners = getFlavorListeners(appContext);
                if (flavorListeners != null) {
                    for (FlavorListener listener : flavorListeners) {
                        if (listener != null) {
                            PeerEvent peerEvent = new PeerEvent(this,
                                    () -> listener.flavorsChanged(new FlavorEvent(SunClipboard.this)),
                                    PeerEvent.PRIORITY_EVENT);
                            SunToolkit.postEvent(appContext, peerEvent);
                        }
                    }
                }
            }

            logInfo("<- sun.awt.datatransfer.SunClipboard#checkChange(formats={0}).", formatsStr);
        } catch (Throwable err) {
            logSevere(
                "<- sun.awt.datatransfer.SunClipboard#checkChange(formats=%s): an exception occurred.".formatted(formatsStr),
                err
            );
            throw err;
        }
    }

    public static FlavorTable getDefaultFlavorTable() {
        return (FlavorTable) SystemFlavorMap.getDefaultFlavorMap();
    }
}
