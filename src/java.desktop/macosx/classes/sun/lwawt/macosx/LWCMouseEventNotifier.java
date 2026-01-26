/*
 * TODO copyright
 */

package sun.lwawt.macosx;

interface LWCMouseEventNotifier {
    void doNotifyMouseEvent(int id, long when, int button,
                            int x, int y, int absX, int absY,
                            int modifiers, int clickCount, boolean popupTrigger,
                            byte[] bdata);
}
