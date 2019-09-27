/*
 * Copyright 2000-2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import javax.swing.*;
import java.awt.*;

public class JEditorPanePreferredSizeTest3 {
    public static void main(String[] args) throws Exception {
        Exception[] exception = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            JEditorPane editorPane = createEditor();
            Dimension initialSize = editorPane.getPreferredSize();
            JEditorPane anotherPane = createEditor();
            anotherPane.setSize(initialSize.width / 2, initialSize.height);
            Dimension newSize = anotherPane.getPreferredSize();
            if (newSize.height <= initialSize.height) {
                exception[0] = new RuntimeException("Unexpected size: " + newSize + ", initial size: " + initialSize);
            }
        });
        if (exception[0] != null) throw exception[0];
    }

    private static JEditorPane createEditor() {
        return new JEditorPane("text/html", "Very long line");
    }
}
