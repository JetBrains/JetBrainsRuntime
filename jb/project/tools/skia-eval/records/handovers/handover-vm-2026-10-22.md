# Handover to the Windows VM — 2026-10-22, request: native text reference and the comparison viewer

Context: JBR-10654, Skia backend evaluation. On the Mac the text work now has (a) a comparison viewer
and (b) a native platform reference for the text sheets. Both are meant to be platform-neutral; the
Windows side needs its own native reference tool and a run of the Java arms.

## 1. The comparison viewer (ready, nothing to build)

`build/compare-viewer/` (git-ignored, copy it over): `index.html`, `viewer.js`, `make-viewer.py`,
`serve.sh`. It shows the arms of one comparison set with switch (keys 1–9), flip (hold Space), blend,
flicker, amplified difference and sub-pixel stripes, at integer zoom 1/2/4/8/16 device pixels per
image pixel, with an RGB readout under the cursor and the whole view state in the URL hash.

- Nested layout (scene matrices): `python3 make-viewer.py <root> --arms A,B,C` where
  `<root>/<arm>/<...>/<scene>.png`; every path below an arm directory becomes a set.
- Flat layout (sheets): `python3 make-viewer.py <dir> --flat --arms a,b,c` where `<dir>/text-<arm>.png`.
- It writes `<root>/viewer/` with a `manifest.js`; open with `bash <root>/viewer/serve.sh` (a Python
  http server; pixel access in the browser needs an http origin, `file://` only gives switch/blend).
  `NO_OPEN=1` serves without opening a browser.

Please build viewers for the existing Windows scene outputs (`out/<date>/scenes`) and the glyph sheets.

## 2. Native text reference on Windows (to build)

The Mac tool is `build/text-reference/ctref.swift` with its Java twin `TextReference.java` (copy both
for the exact row definition). Sheet geometry: rows = font × {1x, 2x} × {gray, gray-fm, lcd, lcd-fm,
aliased}; fonts Inter 13 and JetBrains Mono 13 from the JDK image's `lib/fonts`, plus two system fonts
(on Windows: Segoe UI 13 and Consolas 12 instead of Helvetica Neue 13 and Menlo 12 — change both
tools alike); cell 2·460 × 2·28 px per row, text at (4, 19) points scaled by the row's scale, ink
(30, 30, 40) on white; the same string
`Quick fox jumps 1234567890 {}[]();:,. ∑∂Δ → ≠ ≤ ≥ äöü`.

Needed: `dwref` (C++ or C#) drawing the same rows with
- DirectWrite into a GDI-compatible/WIC bitmap: gray = `DWRITE_RENDERING_MODE_NATURAL` (or
  `..._SYMMETRIC`) with grayscale antialiasing (`D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE` if drawn through
  Direct2D), lcd = ClearType (`D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE`, with the system's ClearType
  parameters), aliased = `DWRITE_RENDERING_MODE_ALIASED`; `-fm` rows with
  `DWRITE_MEASURING_MODE_NATURAL` (fractional advances), the others with `GDI_CLASSIC` (whole pixels);
- a second sheet `text-gdi.png` through GDI `ExtTextOut` with `ANTIALIASED_QUALITY` (gray) and
  `CLEARTYPE_QUALITY` (lcd), `NONANTIALIASED_QUALITY` (aliased), since the JDK's Windows LCD glyphs come
  from GDI.

Then the Java arms with `TextReference.java` on the Skia Windows image: `software`, `d3d-platform`
(if D3D is on) or `gdi-platform`, `vulkan-platform` if available, `skia-platform`
(`-Dsun.java2d.skia=true --volatile`), `skia-scaler-coverage/darkened/smoothed`; always with
`-Dsun.java2d.uiScale=1`. Finally `make-viewer.py <out> --flat --arms directwrite,gdi,software,...`.

Note from the Mac: `-Dsun.java2d.font.scaler=skia` without `-Dsun.java2d.skia=true` silently falls
back to the platform scaler (the library is not loaded), so a `metal-skia-scaler` arm is identical to
`metal-platform`; do not include such an arm.

## 3. What the Mac reference showed (for orientation)

CoreGraphics with font smoothing on an opaque bitmap produces no colour sub-pixels on this macOS: the
platform's "LCD" is dilated grayscale. The JDK's LCD glyphs on macOS are JDK-made (colour fringes at
1x and 2x) and have no native counterpart. Fractional-metrics rows lay out within about a pixel of
CoreText per 40 characters; whole-pixel rows are wider (Java rounds advances up more often).
