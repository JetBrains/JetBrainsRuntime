# Hand-over to the x64 machine: LCD text on the Skia scaler, measured against ClearType (2026-11-10)

This is a side task beside the renderer branch. It belongs to the evaluation branch
`manuel.unterhofer/JBR-10654-skia-backend`, at or after the commit that adds this note. Work on
`manuel.unterhofer/JBR-10654-skia-backend-x64hwN` on the fork. Record everything in `records/raw/raw-x64hw-<date>.md`
and tell the Mac what to integrate. The usual rules apply:
- every finding becomes a jtreg test that is red before the fix and green after, folded into the guarding commit;
- JBR-style commit messages without trailers;
- n ≥ 5 interleaved runs, with median and spread.

## Why on Windows (user, 2026-11-10)

**Correction (same night):** the paragraph below holds for CoreText-rasterized fonts only. A `Font.createFont` font on the JDK's FreeType scaler (the IDE's bundled JetBrains Mono) gets real sub-pixel rendering on macOS too (1 699 coloured pixels on a 13-pt line); macOS keeps Java's LCD behaviour, and real LCD on the Skia routes there is an open item of the Mac (ledger §13.1). The Windows task is unchanged.

macOS has no LCD text of its own. CoreGraphics has produced no colour sub-pixels since Mojave: both scalers' LCD
masks there have equal channels. The colour seen with fractional metrics is the JDK pipes' third-pixel channel shift
(`DrawGlyphList.c`, `rowBytesOffset`) applied to gray masks. So on macOS, LCD correctness means matching a JDK
artefact, not the platform. Whether an LCD request on macOS should simply render grayscale, as the platform does,
is filed as a question for the user (ledger §13.1). Real sub-pixel masks exist on Windows (DirectWrite / ClearType)
and on Linux (FreeType's LCD filter). The LCD questions that remain belong there.

## Done on the Mac (for context)

- **Stray LCD pixels, fixed** (`LcdGlyphSlackTest`). The pipes place an LCD glyph without variants at a third of a
  pixel by reading its rows one or two bytes in: "every glyph image row has 2 bytes padding on the right". The Skia
  scaler's records ended exactly at the image, so the last row read heap garbage: a coloured pixel or three at
  glyphs' bottom-right corners, different in every process. Its LCD records now end in 3 zeroed bytes (only when
  `subpixelResolutionX == 1`). The JDK's CoreText scaler zeroes its 3 extra bytes, and FreeType pads every row.
  - Please run `LcdGlyphSlackTest`'s idea on Windows: the same sheet in fresh JVMs, the Skia scaler with the
    DirectWrite host, LCD with and without fractional metrics. DirectWrite LCD records carry variants (the
    6-position grid), so the shift should not apply there. Check that.
- **"LCD without fractional metrics comes out grayscale"** is the platform on macOS (the JDK scaler too: 0 coloured
  pixels of 25k). On Windows your earlier round found the opposite: the DirectWrite host emits fringes regardless of
  positioning (23.0k fringe pixels of 37.4k ink at 1x). Item 1 below is what decides whether that is right.
- **The tone curve on macOS:** after undoing the loops' blend (black on white,
  out = 255 · (1 − m)^(1/γ), γ = 1.4), the Skia scaler's masks relate to the JDK's as mJ ≈ mS^0.96 (rms 0.06). The
  ledger had expected x² against x^0.56. The real difference is that the JDK lifts faint coverage (Skia's 0–0.1
  band reads 0.108 in the JDK). That looks like a filter or offset difference, not a gamma convention. Tools:
  `text-reference/probes/lcd/` (`LcdRepro` renders a sheet, `Fringe` counts coloured pixels, `Curve` compares two
  renderings pixel by pixel, `Masks` inverts the blend and fits mJ against mS, `Diff` lists differing pixels).

## The task

1. **Fringes against ClearType.** For the Skia scaler's LCD sheets, with and without fractional metrics, at 1x,
   1.25x, 1.5x and 2x, compare the fringe statistics with native DirectWrite ClearType (`text-reference/win`,
   `dwref`):
   - the coloured pixel count;
   - the per-channel spread;
   - the fringe width at stems.

   Decide by the platform: does ClearType colour whole-pixel-positioned text? (It does; then the host is right and
   only the amounts need checking.)
2. **The tone curve against ClearType's.** Recover the masks behind each rendering with the `Masks` method. Take the
   blend each pipe applies: the loops' `lcdContrast` gamma, the D3D / Skia GPU pipes' LUTs. Then fit the Skia
   scaler's masks against native ClearType's coverage and against the JDK's own Windows LCD path. Name the mapping
   if one is needed, and where it belongs (scaler or pipe).
3. **The same on Linux** (FreeType's LCD filter, the desktop's settings) when a Linux machine is available. The
   reference there is FreeType as the Linux desktop uses it, not the JDK's FreeType scaler.

Write tests from what the platform measures, as on the Mac: the expected values come from the platform's
rendering, never from Java's.
