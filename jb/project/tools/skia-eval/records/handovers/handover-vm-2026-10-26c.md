# Handover to the Windows VM — 2026-10-26 (night): black on white, white on black, colors as a dropdown, viewer wording

Shared branch `origin/manuel.unterhofer/JBR-10654-skia-backend`, tip `7fd87d72737b` (unchanged since hand-over b). Everything below
is in the git-ignored `build/` tree, which you copy from the Mac as before (`compare-viewer/`, `text-reference/`, `notes/`).
Hand-over b (`handover-vm-2026-10-26b.md`) still stands; this one changes its section 2 and adds the sheet layout for colors.

## 1. The papers are plain black on white and white on black now (user, 2026-10-26)

- `paper` values: **`black-on-white`** (ink 0,0,0) and **`white-on-black`** (ink 255,255,255), in `text-reference/TextReference.java`,
  `ctref.swift` and the Chrome arm (`compare-viewer/chrome-ref/index.html`, `render.sh`). `on-white` (ink 30,30,40) and `on-dark`
  (188,190,196 on 30,31,34) are gone from the tools; the viewer still labels them "older sheets" so your current pages stay readable
  until you recapture. Hand-over b asked for white on black only; black on white is new, so **the whole Windows sheet tree needs a
  recapture** with the new `TextReference.java` (copy it; your `dwref` needs the same two pairings).
- Why: the sheets are now one pairing on every platform, and the extremes are where the cg / platform route is bit-identical to the
  OS with exact luminance classes; the IDE's editor colors are in the scene captures anyway.

## 2. Colored text lives in the sheet tree, as a dropdown under "color on black" / "color on white"

The separate color trees (`out/2026-10-26-colours` on the Mac, your `text-colours` page) are superseded. Layout, by `run-colours.sh`
(called from `run.sh` into the same OUT):

    <out>/<arm>/<aa>/<positioning>/<scale>/<paper>.png                  paper = black-on-white | white-on-black
    <out>/<arm>/<aa>/<positioning>/<scale>/color-on-<paper>/<color>.png  color = red | blue | orange | green | purple  (IDE dark-theme keyword colors)

`make-viewer.py … --set-dims aa,positioning,scale,paper,color` reads both depths. In the viewer the Colors column shows the four
radios (black on white, white on black, color on black, color on white) and a dropdown for the color on the last two — the `color`
dimension in `descriptions.json` carries `"under": "paper"`, a declared attachment (the data-driven rule would have made it a column
because both color papers share the same color set). **The color radios are enabled only where sets exist:** the Mac files them under
`gray` only (CoreGraphics' luminance classes are the question there), so under LCD and None they are disabled; **on Windows file them
under `lcd` only** (ClearType's per-channel correction is the question there). Your `lcd-colour/<colour>/<scale>/<paper>` sheets map to
`<arm>/lcd/<positioning>/<scale>/color-on-<paper>/<color>.png`; your `-colour2` / `-colour3` variants stay arms. Black and white as
"colors" are the plain papers now — only arms `run.sh` lacks (on the Mac the 256- and 1-class arms) get them from the color script.
Please merge your color sheets into the text tree this way and retire the `text-colours` catalog entry for Windows when done
(the Mac's is gone with this hand-over).

## 3. Wording (descriptions.json; the catalog rebuild applies it to every page)

- Antialiasing mode values are labelled **None / Grayscale / LCD (Subpixel)** (`dimensions[aa].labels`).
- American spelling throughout the viewer copy: **Colors**, color, colored (the user's request; the `text-colours` kind id and file
  names are untouched). Keep new descriptions American.
- `dimensions[].labels` also apply to set dimensions now (`make-viewer.py` merges `"*"` + platform; `catalog/make-catalog.py`
  refreshes them on rebuild), so a set-dimension label change needs no recapture — rebuild the catalog with
  `python3 compare-viewer/catalog/make-catalog.py` (no arguments refreshes the current entries by title).

## 4. Viewer performance (if you profile your pages)

A Zen/Firefox profile showed the full control rebuild (`syncControls()`) at 35 % of the viewer's main-thread time on wheel events and
the canvas at under 1 %. Pan/zoom use `syncView()`, the comparison widgets `syncModes()`, the rebuild only runs on arm/set/page changes;
the column header measures in one layout pass. `#bench=1` in the address bar prints each handler's cost (`BENCH …` on the console;
headless Chrome with `--enable-logging=stderr --virtual-time-budget=40000` shows them), `#perf=1` the draw times. Ledger §12, 2026-10-26.

## 5. What to deliver

1. Windows sheets recaptured with the two plain papers, all arms, all scales you have (1×, 1.25×, 1.5×, 2×), Chrome arm included
   where Chrome exists on the VM; the color sheets filed into the same tree under `lcd`.
2. The catalog rebuilt (`Windows=text=<tree>/viewer/manifest.js`), `text-colours` for Windows retired.
3. Everything from hand-over b sections 3 and 4 that is still open (arms, scales, scene arms, the tests' Windows `@run`).
