/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 */

import java.awt.*;
import java.awt.event.*;

/**
 * Interactive demo exercising all AWT component peers enabled in WLToolkit:
 * Button, Checkbox, Choice, Label, List, Panel, ScrollPane, Scrollbar,
 * TextArea, TextField.
 *
 * Run with the built JDK:
 *   build/linux-x86_64-server-release/images/jdk/bin/java \
 *       test/jdk/java/awt/wl/AWTComponentsDemo.java
 */
public class AWTComponentsDemo {

    private static TextArea logArea;

    private static void log(String message) {
        logArea.append(message + "\n");
    }

    public static void main(String[] args) {
        Frame frame = new Frame("AWT Components Demo (Wayland)");
        frame.setSize(720, 520);
        frame.setLayout(new BorderLayout(6, 6));

        // --- Top panel: Label + TextField + Button ---
        Panel topPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        Label inputLabel = new Label("Input:");
        TextField textField = new TextField(30);
        Button echoButton = new Button("Echo");

        echoButton.addActionListener(e -> log("Echo: " + textField.getText()));
        textField.addActionListener(e -> log("TextField enter: " + textField.getText()));

        topPanel.add(inputLabel);
        topPanel.add(textField);
        topPanel.add(echoButton);
        frame.add(topPanel, BorderLayout.NORTH);

        // --- Left panel: Checkbox, Choice, List ---
        Panel leftPanel = new Panel(new GridLayout(0, 1, 4, 4));

        Checkbox checkbox1 = new Checkbox("Option A");
        Checkbox checkbox2 = new Checkbox("Option B");
        checkbox1.addItemListener(e ->
                log("Checkbox A: " + (e.getStateChange() == ItemEvent.SELECTED ? "on" : "off")));
        checkbox2.addItemListener(e ->
                log("Checkbox B: " + (e.getStateChange() == ItemEvent.SELECTED ? "on" : "off")));
        leftPanel.add(checkbox1);
        leftPanel.add(checkbox2);

        Choice choice = new Choice();
        choice.add("Red");
        choice.add("Green");
        choice.add("Blue");
        choice.addItemListener(e -> log("Choice: " + choice.getSelectedItem()));
        leftPanel.add(choice);

        List list = new List(4, false);
        list.add("Apple");
        list.add("Banana");
        list.add("Cherry");
        list.add("Date");
        list.add("Elderberry");
        list.addActionListener(e -> log("List double-click: " + list.getSelectedItem()));
        list.addItemListener(e -> log("List select: " + list.getSelectedItem()));
        leftPanel.add(list);

        frame.add(leftPanel, BorderLayout.WEST);

        // --- Center: TextArea inside ScrollPane (event log) ---
        logArea = new TextArea("=== Event Log ===\n", 10, 50, TextArea.SCROLLBARS_VERTICAL_ONLY);
        logArea.setEditable(false);
        ScrollPane scrollPane = new ScrollPane(ScrollPane.SCROLLBARS_AS_NEEDED);
        scrollPane.add(logArea);
        frame.add(scrollPane, BorderLayout.CENTER);

        // --- Bottom panel: Scrollbar + status Label ---
        Panel bottomPanel = new Panel(new BorderLayout(6, 0));
        Label statusLabel = new Label("Scrollbar value: 0");
        Scrollbar scrollbar = new Scrollbar(Scrollbar.HORIZONTAL, 0, 10, 0, 110);
        scrollbar.addAdjustmentListener(e -> {
            int val = e.getValue();
            statusLabel.setText("Scrollbar value: " + val);
            log("Scrollbar: " + val);
        });
        bottomPanel.add(scrollbar, BorderLayout.CENTER);
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        // --- Window close ---
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                frame.dispose();
            }
        });

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        log("All components created. Interact to test.");
    }
}
