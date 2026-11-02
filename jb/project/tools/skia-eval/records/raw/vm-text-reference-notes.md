# Windows text reference, 2026-10-22 (VM, Parallels Windows 11 ARM64, 200 % display; all tools at 1x device pixels)

## The luminance probe (hand-over 2026-10-23, section 3)

`dwref --probe`: "Hamburgefonts" in Segoe UI 13, sum of |pixel - paper| over the line, per unit of
contrast |fg - bg| so that rows of different contrast compare directly. System rendering parameters
on this machine: gamma 1.80, enhanced contrast 0.50, ClearType level 1.00, pixel geometry RGB,
rendering mode default. `dw-plain` is DirectWrite grayscale with custom parameters gamma 1.0,
enhanced contrast 0, ClearType level 0 (the closest thing to plain coverage DirectWrite offers;
it is itself not symmetric, so the aliased GDI render is the symmetric control).

| text on paper | dw-plain | dw-gray | dw-ClearType | gdi-gray | gdi-ClearType | gdi-aliased |
|---|---|---|---|---|---|---|
| black on white | 71499 | 62774 | 57981 | 63398 | 62259 | 61965 |
| black on mid-gray | 71704 | 62908 | 58248 | 63726 | 62486 | 62208 |
| dark gray (0.2) on light gray (0.8) | 71548 | 66023 | 61990 | 63515 | 64175 | 61965 |
| mid-gray on white | 71294 | 70812 | 68096 | 63210 | 64660 | 61722 |
| mid-gray on black | 71704 | 71418 | 68748 | 91814 | 69920 | 62208 |
| light gray (0.8) on dark gray (0.2) | 63863 | 69073 | 69638 | 91410 | 67737 | 61965 |
| white on mid-gray | 63692 | 72148 | 73638 | 91088 | 66736 | 61722 |
| white on black | 63926 | 72428 | 73913 | 91546 | 69629 | 61965 |

Reading, per renderer:

- **DirectWrite (grayscale and ClearType alike): the weight depends on the text colour's luminance
  only.** Black text measures the same on white and on mid-gray (62774 / 62908), white text the same
  on black and on mid-gray (72428 / 72148), mid-gray text the same on white and on black (70812 /
  71418). The weight rises monotonically with the text luminance: black 62.8k, dark gray 66.0k,
  light gray 69.1k, mid-gray 71.1k, white 72.4k (grayscale), i.e. white text about 15 % heavier than
  black text at equal contrast; ClearType spans 58.0k to 73.9k (27 %). This is the same shape as
  CoreGraphics' font smoothing on macOS (text luminance only, not the paper), with a different curve:
  CoreGraphics adds ink for every luminance (+15 % black, +40 % white over plain coverage), DirectWrite
  removes ink from dark text and adds to light text relative to its own plain mode. The Direct2D
  documentation's "gamma and enhanced contrast from IDWriteRenderingParams" is applied as a function
  of the foreground luminance (DirectWrite's `GetAlphaBlendParams` ramp), not of the destination.
- **GDI grayscale: the weight depends on the polarity, not on the luminance.** Two groups only:
  dark text on light paper 63.2k to 63.7k (black, dark gray and mid-gray on white all alike), light
  text on dark paper 91.1k to 91.8k (white, light gray and mid-gray on black all alike): light-on-dark
  is 44 % heavier. Mid-gray text is in one group on white and in the other on black, so the paper
  decides. GDI's grayscale AA is a gamma ramp applied at the blend against the destination.
- **GDI ClearType: polarity-dependent as well, milder** (dark-on-light 62.3k to 64.7k, light-on-dark
  66.7k to 69.9k, about 10 %), with a few per cent of paper dependence inside each group.

Consequence for the Windows analogue of the macOS `cg` route: **a per-luminance glyph mask
reproduces DirectWrite exactly by construction** (the same mechanism as `SKPlatform_SmoothedGlyphMask`
on macOS: render the glyph for the text's luminance class, composite plainly), because DirectWrite's
result does not depend on the destination. It cannot reproduce GDI, whose weight depends on the pixel
under the glyph; GDI is a blend rule (a gamma ramp selected by polarity), which is what the JDK's own
LCD loops and the Metal shader do in their own way. Which one is the "right" Windows look is a
product question: DirectWrite is what every modern application draws (and what Skia's own DirectWrite
scaler context models with its luminance pre-blend), GDI is where the JDK's Windows LCD glyphs come
from today and what the IDE's text has looked like.

## Native sheets

`dwref <out> <rows.txt> <fonts>`: 48 sheets (DirectWrite through Direct2D into an opaque WIC bitmap at
96 x scale dpi, and GDI into a 32-bit DIB), 20 rows (Inter 13, JetBrains Mono 13 from the JDK's font
files; Segoe UI 13 and Consolas 12 system faces; regular, italic, bold, bold italic, regular underlined),
no kerning and no ligatures (DirectWrite typography features off), whole-pixel rows with Java's rounded
advances (file fonts in device pixels, system fonts in points) drawn in GDI-classic measuring mode,
fractional rows in natural measuring mode. GDI has no fractional positioning: its "fractional" sheets
use GDI's own integer advances, its whole-pixel sheets Java's rounding of them.

Fringe statistics (pixels whose channel spread exceeds 48, 1x on white): DirectWrite ClearType 23.7k
fringe pixels of 37.7k ink pixels, mean spread 106, max 158; GDI ClearType 22.7k of 35.5k, mean 107,
max 159. The two native LCD renderers fringe alike; the Java arms are compared against these in
`fringe-stats.txt` once run.
