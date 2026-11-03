#!/usr/bin/env python3
"""Packages the catalog as a self-contained, servable folder:  make-dist.py [--out DIR] [--no-webp]

Reads catalog/manifest.js, copies every referenced image once (deduplicated by content) into
<out>/images/<hash>.<png|webp> — lossless WebP where it is smaller than the PNG (cwebp; pixel-exact) —
rewrites the manifest's paths, and adds index.html, viewer.js, the LCD check samples, a serve.py,
.nojekyll and README.md. Converted images are cached in dist-cache/ by content hash, so re-runs
only process new captures. The result is what publish.sh pushes to GitHub Pages."""
import hashlib, json, os, re, shutil, subprocess, sys, time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from kitpaths import OUT_ROOT
CAT = os.path.join(OUT_ROOT, 'catalog')             # the generated catalog (catalog/make-catalog.py)
out = os.path.join(OUT_ROOT, 'dist'); webp = True; workflow = False
args = sys.argv[1:]
while args:
    a = args.pop(0)
    if a == '--out': out = os.path.abspath(args.pop(0))
    elif a == '--no-webp': webp = False
    elif a == '--workflow': workflow = True   # deploy through GitHub Actions instead of the legacy Pages builder
    else: print(__doc__); sys.exit(2)
cache = os.path.join(OUT_ROOT, 'dist-cache'); os.makedirs(cache, exist_ok=True)
cwebp = shutil.which('cwebp') if webp else None
if webp and not cwebp: print('cwebp not found: shipping PNG only', file=sys.stderr)

src = open(os.path.join(CAT, 'manifest.js')).read()
catalog = json.JSONDecoder().raw_decode(src[src.index('=') + 1:].lstrip())[0]   # the first statement's value; more statements may follow (window.COMPARE_START)
files = sorted({a['src'] for e in catalog for st in e['manifest']['sets'] for a in st['arms']})

def digest(path):
    h = hashlib.sha1()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''): h.update(chunk)
    return h.hexdigest()

def convert(item):
    """One unique image: the smaller of the PNG and its lossless WebP, cached by content hash."""
    h, full = item
    png = os.path.join(cache, h + '.png'); wp = os.path.join(cache, h + '.webp'); done = os.path.join(cache, h + '.done')
    if not os.path.exists(done):
        shutil.copy(full, png)
        if cwebp:
            subprocess.run([cwebp, '-quiet', '-lossless', '-z', '6', full, '-o', wp], check=False)
            if os.path.exists(wp) and os.path.getsize(wp) < os.path.getsize(png): os.remove(png)
            elif os.path.exists(wp): os.remove(wp)
        open(done, 'w').close()
    return h, (wp if os.path.exists(wp) else png)

t0 = time.time()
fulls = {path: os.path.normpath(os.path.join(CAT, path)) for path in files}
with ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as ex: hashes = dict(zip(files, ex.map(lambda p: digest(fulls[p]), files)))
unique = {}
for path, h in hashes.items(): unique.setdefault(h, fulls[path])   # one conversion per distinct content (duplicates would race on the cache)
with ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as ex: cached = dict(ex.map(convert, unique.items()))
prepared = {path: (h, cached[h]) for path, h in hashes.items()}
print('%d images referenced, %d unique, prepared in %.0f s' % (len(files), len(unique), time.time() - t0))

if os.path.exists(out): shutil.rmtree(out)
os.makedirs(os.path.join(out, 'images'))
total = 0
for path, (h, cached) in prepared.items():
    dst = os.path.join(out, 'images', os.path.basename(cached))
    if not os.path.exists(dst): shutil.copy(cached, dst); total += os.path.getsize(dst)
for e in catalog:
    for st in e['manifest']['sets']:
        for a in st['arms']: a['src'] = 'images/' + os.path.basename(prepared[a['src']][1])
with open(os.path.join(out, 'manifest.js'), 'w') as fh:
    fh.write('window.COMPARE_CATALOG = ' + json.dumps(catalog, ensure_ascii=False, separators=(',', ':')) + ';\n')
    for line in src.splitlines():   # the catalog's other statements (window.COMPARE_START: the page, set and preset a visitor starts on)
        if line.startswith('window.') and not line.startswith('window.COMPARE_CATALOG'): fh.write(line + '\n')
shutil.copy(os.path.join(HERE, 'viewer.js'), out)
for f in ('lcd-rgb.png', 'lcd-bgr.png'): shutil.copy(os.path.join(HERE, f), out)
stamp = hashlib.sha1(open(os.path.join(HERE, 'viewer.js'), 'rb').read() + open(os.path.join(HERE, 'index.html'), 'rb').read()).hexdigest()[:10]
with open(os.path.join(out, 'index.html'), 'w') as fh: fh.write(open(os.path.join(HERE, 'index.html')).read().replace('@VERSION@', stamp))
open(os.path.join(out, '.nojekyll'), 'w').close()
if workflow:   # the Actions deployment: upload the folder as the Pages artifact and deploy it (no Jekyll, no size-sensitive legacy build)
    os.makedirs(os.path.join(out, '.github', 'workflows'))
    with open(os.path.join(out, '.github', 'workflows', 'pages.yml'), 'w') as fh:
        fh.write('''name: Deploy to GitHub Pages
on:
  push:
    branches: [main]
  workflow_dispatch:
permissions:
  contents: read
  pages: write
  id-token: write
concurrency:
  group: pages
  cancel-in-progress: true
jobs:
  deploy:
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/configure-pages@v5
      - uses: actions/upload-pages-artifact@v3
        with:
          path: .
      - id: deployment
        uses: actions/deploy-pages@v4
''')
with open(os.path.join(out, 'serve.py'), 'w') as fh:
    fh.write('''#!/usr/bin/env python3
"""Serves this folder on http://127.0.0.1:<port>/ and opens the viewer. Pixel access (difference,
color channels) needs an http origin; opening index.html from disk shows the images only."""
import http.server, os, socket, sys, threading, webbrowser
os.chdir(os.path.dirname(os.path.abspath(__file__)))
port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
while True:
    try: srv = http.server.ThreadingHTTPServer(('127.0.0.1', port), http.server.SimpleHTTPRequestHandler); break
    except OSError: port += 1
url = 'http://127.0.0.1:%d/' % port
print(url); threading.Timer(0.5, lambda: webbrowser.open(url)).start()
try: srv.serve_forever()
except KeyboardInterrupt: pass
''')
with open(os.path.join(out, 'README.md'), 'w') as fh:
    fh.write('''# Text rendering comparison

Screenshots of the same text drawn by the platform's own text stack and by Java2D configurations
(today's pipelines, the Skia backend experiment of the JetBrains Runtime), compared pixel for pixel:
controlled text sheets and IDE screenshots, on macOS and Windows, at several display scales.

**Open:** the hosted copy, or `python3 serve.py` here and the browser opens it (a local http origin is
needed for the difference and color-channel views; opening `index.html` from disk shows the images only).
Chrome or Edge.

**Use:** pick a page (platform, content), the sample conditions, and two renderings A and B — or a
suggested comparison from the Preset list. Show one side (A / B buttons, Space peeks at the other),
alternate them, the difference, or the color channels of one side. Zoom with the slider, the digit keys,
pinch or Shift + wheel; images are never interpolated. The notes column says what matters for the
comparison, where the differences come from, and explains the values on display. The address bar holds
the whole view and can be shared as a link. `H` lists the keys.

**LCD samples** were rendered for horizontal RGB sub-pixel stripes; the button in the sample conditions
checks your screen's order and warns when it differs.

Generated by `jb/project/tools/skia-eval/viewer/make-dist.py`; images are lossless WebP or PNG, deduplicated by content.
''')
size = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(out) for f in fs)
kinds = {}
for f in os.listdir(os.path.join(out, 'images')): kinds[f.rsplit('.', 1)[1]] = kinds.get(f.rsplit('.', 1)[1], 0) + 1
print('dist: %s — %.0f MB, images %s' % (out, size / 1e6, kinds))
