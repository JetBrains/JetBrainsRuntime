# Handover to the Windows VM — 2026-10-26: ClearType with saturated colours, new text-cache properties

Shared branch `origin/manuel.unterhofer/JBR-10654-skia-backend`, tip `eda9990f9024` (pull first; five commits since
the integration of your windows8 round). Ledger entries: §12 2026-10-26 (two), §19b.1 A–G.

## 1. What changed on the shared branch that touches Windows

- The three Skia text-path entries are render-queue opcodes (`SKTextRenderer.DRAW_GLYPHS_BY_SKIA` 210,
  `DRAW_SHAPED_BY_SKIA` 211, `DRAW_PARAGRAPH_BY_SKIA` 212, dispatched in the shared `SKRenderQueue.cpp`), no
  longer a `flushAndInvokeNow` per string. Nothing platform-specific to do; `SKEnvironment.flushes()` counts
  every `SKBackend_EndOfFlush` if you want flush rates. `SkiaTextPathQueuedTest` is `@requires os.family == "mac"`
  because of its MTL configuration check; a Windows `@run` needs the D3D/Vulkan configuration name.
- New properties, all shared code: `sun.java2d.skia.strikeCache` (MB, default 64; Skia's own default of 2 MB
  thrashed under a few dozen colours), `sun.java2d.skia.glyphCache` (texels; 2048 under per-luminance strikes,
  1024 otherwise), `sun.java2d.skia.glyphAtlas` (MB, Skia's 8 MB unless set), `sun.java2d.skia.text.luminanceClasses`
  (1, 2, 4, … 256, default 16; 1 = the cg/platform route as before the classes existed, every mask at
  `cgLuminance`). Tests: `SkiaStrikeCacheBudgetTest`, `LuminanceClassCountTest` (both mac-gated by the MTL check or
  by `@requires`; port the same way as the others).
- macOS only: Metal's glyph caches default to 2048 texels under per-luminance strikes (`MetalGlyphCacheSizeTest`).
- Both pipes' glyph caches come in size buckets now (cells 16 / 32 / 64 / 128 px; `SKGlyphCache.h`, `MTLTextRenderer.m`); the Skia side is shared code, so Windows gets it on pull — `SkiaGlyphCacheBucketsTest` and `SkiaGlyphCacheStatsTest` are mac-gated by the MTL configuration check only, port them. The D3D/OpenGL pipelines' own caches (`AccelGlyphCache` with 32-px cells) still have the cliff at 2×; the same bucket design applies if anyone measures text there.

## 2. The request: ClearType with saturated colours against native

Our LCD masks on the platform route come from DirectWrite rendering a **gray of the text's luminance class**, so
the three sub-pixel corrections in the mask are that gray's. Native ClearType corrects each channel for the actual
colour. Identical for black, white and grays; unmeasured for colours.

- Sheet: the usual ClearType rows (Segoe UI, Consolas, JetBrains Mono, Inter; 1× and 2×) in saturated IDE colours —
  a keyword red, a blue, an orange, a green, a purple, plus black as the control — on white and on a dark
  background. Native reference drawn with the same colours (`dwref` with a colour argument).
- Report per colour and per channel: max and mean deltas, and crops of the worst glyphs. The question is whether
  the per-channel difference is visible, not whether it is nonzero.
- If visible, the plan (ledger §12 follow-ups) is an LCD-only key by a quantized colour triple: draw with the
  quantized colour, choose the paper per channel opposite to the ink, 2 bits per channel (64 classes) first, Skia's
  3 (512) as the comparison; grayscale stays at 16 luminance classes; the caches get their own sizing (the user's
  rule: any extension that needs larger text caches gets them). The alternative to compare against is Graphite's
  destination-copy fallback for a dst-reading LCD blender (exact, one mask; its per-draw cost on D3D11 is the
  number we lack).

## 3. Still pending from before

The T3/T4 scene re-run after the synthetic-style fix (needs the user's on-screen go-ahead), the whole-pixel
advance decision, and the fractional-ppem rounding decision (your hand-back of 2026-10-23, "Open on the VM side").

## 4. Answered on the Mac (2026-10-26, afternoon)

- Both commits (`e84a2e20873b`, `06f7570769a2`) are on the shared branch, pushed to the fork too (`manu-unter/JetBrainsRuntime`, the tip you pull). Pull before continuing; your `…-windows8` is consumed.
- `SKPlatform_SyntheticStyle` on macOS: measured — CoreText and AppKit synthesize nothing; the JDK's own convention there is a 0.2 shear of the strike transform for every font (bundled files are CFonts on macOS) and no fake bold, and the scaler is never asked for a synthetic style. The stub returns (0.2, 0), documented; `SkiaSyntheticStyleMacTest` (mac-gated) locks it. Your Windows values and tests are unchanged.
- Your ClearType colour sheets (`out/2026-10-26/`) go into the shared catalog as a fifth entry (`catalog/make-catalog.py` now rebuilds the catalog from the source manifests, re-rooting image paths and filtering each page's dimension values to its own arms — the macOS pages had shown Windows-only values).
