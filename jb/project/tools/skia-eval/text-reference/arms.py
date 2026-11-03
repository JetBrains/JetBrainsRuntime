#!/usr/bin/env python3
"""The Java arms of the text sheets: every combination of the text settings a platform offers (2026-11-03,
user: "the sheets are the visual tool for the evaluation of every single possible combination").

  arms.py <macOS|Windows>            one line per arm:  <name>\t<software|volatile>\t<JVM options>
  arms.py <macOS|Windows> --count    the number of arms

An arm's name spells its settings, `key-value` joined by `+` (the viewer reads the dimensions back from it:
make-viewer.py / make-catalog.py, arm_dims_from_name):
  pipe-<software|metal|skia>          the Java2D pipeline (metal: the Metal pipe; skia: the Skia backend)
  scaler-<platform|skia>              the font scaler (platform: CoreText's CStrike / FreeType; skia: SKFontScaler)
  strike-<java2d|skia>                the glyphs: Java's glyph cache or Skia's own text path (Skia scaler, Skia pipe)
  shaping-<java2d|skia>               the shaper for plain strings (Skia's needs the Skia strike)
  paragraphs-<java2d|skia>            attributed strings: Java's TextLayout or SkParagraph (needs the Skia strike)
  darkening-<system|none>             sun.java2d.skia.text.darkening (Skia scaler only)
  blend-<system|plain|corrected>      sun.java2d.skia.text.blend (Skia scaler); for the platform scaler the pipe's
                                      own blending: corrected = Metal's text gamma, plain = without it / the loops
  decorations-<system|java2d>         sun.java2d.skia.text.decorations
"""
import itertools, sys

def arms(platform):
    out = []
    def add(settings, how, props):
        name = '+'.join(f'{k}-{v}' for k, v in settings)
        out.append((name, how, ' '.join(props)))
    gpu = 'metal' if platform == 'macOS' else 'skia'   # the platform's own accelerated Java2D pipe that is not Skia
    pipes = {'software': ('software', []), gpu: ('volatile', []), 'skia': ('volatile', ['-Dsun.java2d.skia=true'])} if platform == 'macOS' \
        else {'software': ('software', ['-Dsun.java2d.d3d=false']), 'skia': ('volatile', ['-Dsun.java2d.skia=true'])}
    decorations = ['system', 'java2d']
    # the platform scaler: the pipe's own blending (Metal: corrected by its text gamma, or plain without it)
    for pipe, (how, props) in pipes.items():
        blends = ['corrected', 'plain'] if pipe == 'metal' else ['plain']
        for blend in blends:
            for deco in decorations:
                p = list(props)
                if pipe == 'metal' and blend == 'plain': p.append('-Dsun.java2d.metal.textGammaCorrection=false')
                p.append(f'-Dsun.java2d.skia.text.decorations={deco}')
                add([('pipe', pipe), ('scaler', 'platform'), ('strike', 'java2d'), ('shaping', 'java2d'), ('paragraphs', 'java2d'),
                     ('blend', blend), ('decorations', deco)], how, p)
    # the Skia scaler, Java's glyph cache: every pipe
    for pipe, (how, props) in pipes.items():
        for dark, blend, deco in itertools.product(['system', 'none'], ['system', 'plain', 'corrected'], decorations):
            p = list(props) + ['-Dsun.java2d.font.scaler=skia', f'-Dsun.java2d.skia.text.darkening={dark}', f'-Dsun.java2d.skia.text.blend={blend}',
                               f'-Dsun.java2d.skia.text.decorations={deco}']
            add([('pipe', pipe), ('scaler', 'skia'), ('strike', 'java2d'), ('shaping', 'java2d'), ('paragraphs', 'java2d'),
                 ('darkening', dark), ('blend', blend), ('decorations', deco)], how, p)
    # Skia's own text path: the Skia pipe only
    how, props = pipes['skia']
    for shaping, paragraphs, dark, blend, deco in itertools.product(['java2d', 'skia'], ['java2d', 'skia'], ['system', 'none'],
                                                                      ['system', 'plain', 'corrected'], decorations):
        p = list(props) + ['-Dsun.java2d.font.scaler=skia', '-Dsun.java2d.skia.text.strike=skia', f'-Dsun.java2d.skia.text.shaping={shaping}',
                           f'-Dsun.java2d.skia.text.paragraphs={paragraphs}', f'-Dsun.java2d.skia.text.darkening={dark}',
                           f'-Dsun.java2d.skia.text.blend={blend}', f'-Dsun.java2d.skia.text.decorations={deco}']
        add([('pipe', 'skia'), ('scaler', 'skia'), ('strike', 'skia'), ('shaping', shaping), ('paragraphs', paragraphs),
             ('darkening', dark), ('blend', blend), ('decorations', deco)], how, p)
    return out

if __name__ == '__main__':
    platform = sys.argv[1] if len(sys.argv) > 1 else 'macOS'
    rows = arms(platform)
    if '--count' in sys.argv: print(len(rows)); sys.exit(0)
    for name, how, props in rows: print(f'{name}\t{how}\t{props}')
