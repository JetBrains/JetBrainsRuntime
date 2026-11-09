/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The UI scenes of the IDE performance run (bench/ide-perf), after the ledger's
 * GeometryScenesPerformanceTest (§11.16, 2026-10-15): tab switching, tool windows shown and hidden,
 * all tool windows hidden and restored, distraction-free mode on and off, Search Everywhere, the
 * Settings dialog, the main menu and a theme switch. The commands go to the scene-driver plugin in
 * the isolated IDE over its built-in server (each runs on the EDT and returns when it has); the
 * pacing is fixed, so the run measures the IDE's work per scene (JFR method timing, J2D_SKIA_STATS),
 * not a frame rate. Toggles come in even numbers: the IDE ends in the state it started in.
 * <pre>
 *   java IdePerfScenes --port <built-in server port> --log <idea.log> [--rounds N]
 * </pre>
 * Waits until the script of ui.ijperf logs its mark (the files are open, the project is smart),
 * prints one line per scene ("scene<TAB>ms"), then asks the IDE to exit.
 */
public class IdePerfScenes {
    static int port;
    static final HttpClient http = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        Path log = null;
        int rounds = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--log" -> log = Path.of(args[++i]);
                case "--rounds" -> rounds = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        awaitMark(log, "%mark ide-perf-ready", 900_000);
        for (int r = 0; r < rounds; r++) {
            scene("tabs", () -> { for (int i = 0; i < 64; i++) { ide("cmd=action&id=NextTab"); sleep(120); } });
            scene("toolwindows", () -> {
                for (int i = 0; i < 12; i++) {
                    ide("cmd=toolwindow&id=Project&show=false"); sleep(250);
                    ide("cmd=toolwindow&id=Project"); sleep(250);
                    ide("cmd=toolwindow&id=Structure"); sleep(250);
                    ide("cmd=toolwindow&id=Structure&show=false"); sleep(250);
                }
            });
            scene("hideall", () -> { for (int i = 0; i < 10; i++) { ide("cmd=action&id=HideAllWindows"); sleep(400); } });
            scene("distraction", () -> { for (int i = 0; i < 8; i++) { ide("cmd=action&id=ToggleDistractionFreeMode"); sleep(900); } });
            scene("search", () -> {
                for (int i = 0; i < 8; i++) {
                    ide("cmd=search&tab=ClassSearchEverywhereContributor&text=Factory"); sleep(900);
                    ide("cmd=key&code=ESCAPE"); sleep(300);
                }
            });
            scene("settings", () -> {
                for (int i = 0; i < 5; i++) {
                    ide("cmd=settings&class=com.intellij.application.options.editor.fonts.AppEditorFontConfigurable"); sleep(1800);
                    ide("cmd=key&code=ESCAPE"); sleep(600);
                }
            });
            scene("menu", () -> { for (int i = 0; i < 8; i++) { ide("cmd=menu"); sleep(500); ide("cmd=key&code=ESCAPE"); sleep(250); } });
            scene("theme", () -> {
                for (int i = 0; i < 4; i++) {
                    boolean dark = i % 2 == 0;
                    ide("cmd=theme&laf=" + (dark ? "ExperimentalDark" : "ExperimentalLight") + "&scheme=" + (dark ? "Dark" : "Light"));
                    sleep(2500);
                }
            });
        }
        ide("cmd=exit");
    }

    interface Body { void run() throws Exception; }

    static void scene(String name, Body body) throws Exception {
        long t = System.nanoTime();
        body.run();
        System.out.printf("%s\t%.0f%n", name, (System.nanoTime() - t) / 1e6);
    }

    static void awaitMark(Path log, String mark, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(log) && Files.readString(log, java.nio.charset.StandardCharsets.ISO_8859_1).contains(mark)) {
                return;
            }
            sleep(1000);
        }
        throw new IllegalStateException("no " + mark + " in " + log + " within " + timeoutMs + " ms");
    }

    static String ide(String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/scene?" + query))
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body().trim();
        if (response.statusCode() != 200 || body.startsWith("error")) {
            throw new IllegalStateException(query + " -> HTTP " + response.statusCode() + " " + body);
        }
        return body;
    }

    static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
