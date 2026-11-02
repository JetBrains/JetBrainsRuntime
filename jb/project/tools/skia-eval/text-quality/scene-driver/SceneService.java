package scenes;

import com.intellij.ide.DataManager;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.terminal.ui.TerminalWidget;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.jetbrains.ide.RestService;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

import javax.swing.JFrame;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The scene driver's way into the IDE (JBR-10654 text rendering catalog): GET
 * {@code http://127.0.0.1:<built-in server port>/api/scene?cmd=<command>&...} runs one step on the
 * EDT and answers "ok" or "error: ...". No keystrokes, no Search Everywhere typing, no guessing
 * where the focus is (2026-11-02; the Robot-driven driver before lost a scene to the terminal
 * keeping Escape and spent most of its time typing action names).
 * <ul>
 *   <li>{@code open&file=src/Hello.java} — opens the project file in the editor and focuses it</li>
 *   <li>{@code select&from=15:-1&to=18:@Comparable[&caret=from|to]} — a selection between line:column
 *       positions (1-based lines; column -1 = first non-blank, end = line end, @text = where the text
 *       occurs in the line) with the caret at one end;
 *       {@code select&clear=true} removes it</li>
 *   <li>{@code action&id=QuickJavaDoc} — invokes an action by id with the editor's (else the frame's) context</li>
 *   <li>{@code settings&id=<configurable id>} or {@code settings&class=<configurable class>} — opens Settings at that page (asynchronously: modal)</li>
 *   <li>{@code menu} — the main menu group as a popup under the frame's header, as the hamburger button shows it</li>
 *   <li>{@code search&tab=ClassSearchEverywhereContributor&text=Point} — Search Everywhere on a tab with a query</li>
 *   <li>{@code terminal} — opens a shell in the Terminal tool window; {@code terminalExec&line=ls} runs a command in it</li>
 *   <li>{@code toolwindow&id=Terminal&show=true|false} — shows or hides a tool window, the focus returns to the editor</li>
 *   <li>{@code key&code=ESCAPE} — posts a key press and release to the focus owner (closes popups and dialogs)</li>
 *   <li>{@code front} — brings the project frame to the front and focuses the editor; {@code title} — the frame title</li>
 * </ul>
 */
public final class SceneService extends RestService {
    private static TerminalWidget terminal;

    @Override protected String getServiceName() { return "scene"; }
    @Override protected boolean isMethodSupported(HttpMethod method) { return method == HttpMethod.GET; }
    @Override protected boolean isHostTrusted(FullHttpRequest request, QueryStringDecoder urlDecoder) { return true; }   // the server listens on loopback only

    @Override
    public String execute(QueryStringDecoder d, FullHttpRequest request, ChannelHandlerContext context) throws IOException {
        String cmd = String.valueOf(getStringParameter("cmd", d));
        AtomicReference<String> result = new AtomicReference<>("ok");
        try {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    String r = run(cmd, d);
                    if (r != null) result.set(r);
                } catch (Throwable t) {
                    result.set("error: " + t);
                }
            }, ModalityState.any());
        } catch (Throwable t) {
            result.set("error: " + t);
        }
        BufferExposingByteArrayOutputStream out = new BufferExposingByteArrayOutputStream();
        out.write(result.get().getBytes(StandardCharsets.UTF_8));
        send(out, request, context);
        return null;
    }

    private static Project project() {
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        if (open.length == 0) throw new IllegalStateException("no open project");
        return open[0];
    }

    private static Editor editor(Project project) {
        Editor e = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (e == null) throw new IllegalStateException("no text editor selected");
        return e;
    }

    private static JFrame frame(Project project) {
        return WindowManager.getInstance().getFrame(project);
    }

    private static String param(QueryStringDecoder d, String name) {
        String v = getStringParameter(name, d);
        if (v == null) throw new IllegalArgumentException("missing parameter " + name);
        return v;
    }

    private static String run(String cmd, QueryStringDecoder d) {
        Project project = project();
        switch (cmd) {
            case "front" -> {
                JFrame f = frame(project);
                f.toFront();
                Editor e = FileEditorManager.getInstance(project).getSelectedTextEditor();
                IdeFocusManager.getInstance(project).requestFocus(e != null ? e.getContentComponent() : f.getRootPane(), true);
            }
            case "title" -> { return frame(project).getTitle(); }
            case "open" -> {
                String file = param(d, "file");
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + "/" + file);
                if (vf == null) throw new IllegalArgumentException("no file " + file + " under " + project.getBasePath());
                new OpenFileDescriptor(project, vf, 0, 0).navigate(true);
                Editor e = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (e != null) IdeFocusManager.getInstance(project).requestFocus(e.getContentComponent(), true);
            }
            case "select" -> {
                Editor e = editor(project);
                if ("true".equals(getStringParameter("clear", d))) {
                    e.getSelectionModel().removeSelection();
                    return "ok";
                }
                int from = offset(e.getDocument(), param(d, "from")), to = offset(e.getDocument(), param(d, "to"));
                e.getSelectionModel().setSelection(Math.min(from, to), Math.max(from, to));
                e.getCaretModel().moveToOffset("to".equals(getStringParameter("caret", d)) ? to : from);
                e.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
            }
            case "action" -> {
                String id = param(d, "id");
                AnAction action = ActionManager.getInstance().getAction(id);
                if (action == null) throw new IllegalArgumentException("no action " + id);
                Editor e = FileEditorManager.getInstance(project).getSelectedTextEditor();
                Component c = e != null ? e.getContentComponent() : frame(project).getRootPane();
                ActionManager.getInstance().tryToExecute(action, null, c, ActionPlaces.KEYBOARD_SHORTCUT, true);
            }
            case "settings" -> {
                // by configurable id, or by the configurable's class (the ids are not all documented)
                String id = getStringParameter("id", d), cls = getStringParameter("class", d);
                if (cls != null) {
                    Class<?> c;
                    try {
                        c = Class.forName(cls, true, SceneService.class.getClassLoader());
                    } catch (ClassNotFoundException ex) {
                        throw new IllegalArgumentException("no configurable class " + cls);
                    }
                    @SuppressWarnings("unchecked") Class<? extends com.intellij.openapi.options.Configurable> cc = (Class<? extends com.intellij.openapi.options.Configurable>) c;
                    ApplicationManager.getApplication().invokeLater(() -> ShowSettingsUtil.getInstance().showSettingsDialog(project, cc), ModalityState.nonModal());
                } else {
                    if (id == null) throw new IllegalArgumentException("settings needs id or class");
                    ApplicationManager.getApplication().invokeLater(() -> ShowSettingsUtil.getInstance().showSettingsDialog(project, id), ModalityState.nonModal());
                }
            }
            case "menu" -> {
                // the main menu as a popup under the frame's header, as the hamburger button shows it (its own
                // action needs the button's context and does nothing from here)
                AnAction group = ActionManager.getInstance().getAction("MainMenu");
                if (!(group instanceof com.intellij.openapi.actionSystem.ActionGroup g)) throw new IllegalStateException("MainMenu is not a group");
                JFrame f = frame(project);
                DataContext dc = DataManager.getInstance().getDataContext(f.getRootPane());
                com.intellij.openapi.ui.popup.ListPopup popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                        .createActionGroupPopup(null, g, dc, com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
                popup.showInScreenCoordinates(f.getRootPane(), new java.awt.Point(f.getX() + 8, f.getY() + 44));
            }
            case "search" -> {
                String tab = param(d, "tab"), text = param(d, "text");
                Editor e = FileEditorManager.getInstance(project).getSelectedTextEditor();
                Component c = e != null ? e.getContentComponent() : frame(project).getRootPane();
                DataContext dc = DataManager.getInstance().getDataContext(c);
                SearchEverywhereManager.getInstance(project).show(tab, text, AnActionEvent.createFromDataContext(ActionPlaces.KEYBOARD_SHORTCUT, null, dc));
            }
            case "terminal" -> {
                terminal = TerminalToolWindowManager.getInstance(project).createShellWidget(project.getBasePath(), "Local", true, true);
            }
            case "terminalExec" -> {
                if (terminal == null) throw new IllegalStateException("no terminal yet: run cmd=terminal first");
                terminal.sendCommandToExecute(param(d, "line"));
            }
            case "toolwindow" -> {
                ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(param(d, "id"));
                if (tw == null) throw new IllegalArgumentException("no tool window " + getStringParameter("id", d));
                if ("false".equals(getStringParameter("show", d))) tw.hide(null); else tw.show(null);
                Editor e = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (e != null) IdeFocusManager.getInstance(project).requestFocus(e.getContentComponent(), true);
            }
            case "key" -> {
                int code;
                try {
                    code = KeyEvent.class.getField("VK_" + param(d, "code").toUpperCase()).getInt(null);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalArgumentException("unknown key " + getStringParameter("code", d));
                }
                Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (owner == null) owner = frame(project).getRootPane();
                long when = System.currentTimeMillis();
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(owner, KeyEvent.KEY_PRESSED, when, 0, code, KeyEvent.CHAR_UNDEFINED));
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(owner, KeyEvent.KEY_RELEASED, when + 1, 0, code, KeyEvent.CHAR_UNDEFINED));
            }
            default -> throw new IllegalArgumentException("unknown cmd " + cmd);
        }
        return null;
    }

    /**
     * "line:column" (1-based line; column -1 = first non-blank, "end" = the line's end, "@text" = where
     * that text first occurs in the line, else 0-based) to an offset.
     */
    private static int offset(Document doc, String pos) {
        String[] f = pos.split(":", 2);
        int line = Integer.parseInt(f[0]) - 1;
        int start = doc.getLineStartOffset(line), end = doc.getLineEndOffset(line);
        if (f.length < 2 || f[1].equals("end")) return end;
        if (f[1].startsWith("@")) {
            int at = doc.getCharsSequence().subSequence(start, end).toString().indexOf(f[1].substring(1));
            if (at < 0) throw new IllegalArgumentException("line " + (line + 1) + " has no '" + f[1].substring(1) + "'");
            return start + at;
        }
        int col = Integer.parseInt(f[1]);
        if (col < 0) {
            CharSequence text = doc.getCharsSequence();
            int o = start;
            while (o < end && Character.isWhitespace(text.charAt(o))) o++;
            return o;
        }
        return Math.min(start + col, end);
    }
}
