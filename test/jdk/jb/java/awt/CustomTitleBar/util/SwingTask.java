package util;

import com.jetbrains.WindowDecorations;

import test.jb.testhelpers.TitleBar.Task;
import test.jb.testhelpers.TitleBar.TaskResult;
import test.jb.testhelpers.TitleBar.TestUtils;
import javax.swing.*;
import java.awt.*;
import java.util.function.Function;

abstract public class SwingTask extends Task {
    public SwingTask(String name) {
        super(name);
    }

    @Override
    public TaskResult run(Function<WindowDecorations.CustomTitleBar, Window> windowCreator) {
        System.out.printf("RUN TEST CASE: %s%n", name);
        passed = true;
        error = "";
        try {
            robot = new Robot();
        } catch (AWTException e) {
            e.printStackTrace();
            return new TaskResult(false, "ERROR: unable to init robot");
        }

        try {
            runInternal(windowCreator);
        } catch (Exception e) {
            e.printStackTrace();
            return new TaskResult(false, "ERROR: An error occurred during test execution");
        }

        if (!TestUtils.isBasicTestCase()) {
            boolean isMetConditions = TestUtils.checkScreenSizeConditions(window);
            if (!isMetConditions) {
                error += "SKIPPED: environment don't match screen size conditions";
                return new TaskResult(false, true, error);
            }
        }

        return new TaskResult(passed, error);
    }

    private void runInternal(Function<WindowDecorations.CustomTitleBar, Window> windowCreator) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                prepareTitleBar();
                window = windowCreator.apply(titleBar);
                customizeWindow();
                window.setVisible(true);
            });

            test();
        } finally {
            SwingUtilities.invokeAndWait(this::disposeUI);
        }
    }
}
