#!/usr/bin/env python3
"""Rebuilds the catalog (build/skia-eval-out/catalog/manifest.js and its page files) from the viewer manifests it was
assembled from (matched by title),
filtering every page's dimension values to the values its own arms carry: descriptions.json describes
every platform's arms, and a Windows-only value has no place on a macOS page (2026-10-26).
Usage: make-catalog.py [platform=kind=path/to/viewer/manifest.js ...]  (no arguments: refresh the current entries)"""
import json, os, re, sys, glob

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
from kitpaths import REPO, OUT_ROOT, arm_from_name
OUTDIR = os.path.join(OUT_ROOT, 'catalog')          # the generated catalog: manifest, page files, serve-root
CATALOG = os.path.join(OUTDIR, 'manifest.js')
BUILD = os.path.join(REPO, 'build')                 # the capture trees live under build/ (their viewer/manifest.js is matched by title)

def load_manifest(path):
    src = open(path).read()
    return json.JSONDecoder().raw_decode(src[src.index('=') + 1:].lstrip())[0]   # the first statement's value; more statements may follow (window.COMPARE_START)

def reroot(manifest, viewer_dir):
    """Image paths in a source manifest are relative to its viewer directory; the catalog serves
    from its own directory (serve-root is the common parent), so every path is re-rooted."""
    prefix = os.path.relpath(viewer_dir, OUTDIR)
    for s in manifest.get('sets', []):
        for a in s.get('arms', []):
            for k, v in list(a.items()):
                if isinstance(v, str) and v.endswith('.png') and not v.startswith(('http', '/')):
                    a[k] = os.path.normpath(os.path.join(prefix, v))
    return manifest

DESC = os.path.join(HERE, '..', 'descriptions.json')

def refresh_content(manifest, platform):
    """Labels, effects, quirks and presets come from descriptions.json at catalog time, so an edit
    of the descriptions reaches every page without regenerating the source viewers."""
    if not os.path.exists(DESC): return manifest
    desc = json.load(open(DESC))
    by_key = {d['key']: d for d in desc.get('dimensions', [])}
    for d in manifest.get('dimensions', []) + manifest.get('setDimensions', []):
        src = by_key.get(d['key'])
        if not src: continue
        lab = src.get('labels') or {}
        if isinstance(lab, dict) and ('*' in lab or any(isinstance(v, dict) for v in lab.values())):
            merged = dict(lab.get('*', {})); merged.update(lab.get(platform, {}))
            d['labels'] = merged
        elif lab: d['labels'] = lab
        for k in ('name', 'description', 'under', 'column'):   # the wording and the layout declarations follow the descriptions too
            if k in src: d[k] = src[k]
        eff = src.get('effects') or {}
        if isinstance(eff, dict) and ('*' in eff or any(isinstance(v, dict) for v in eff.values())):
            merged = dict(eff.get('*', {})); merged.update(eff.get(platform, {})); d['effects'] = merged
        elif eff: d['effects'] = eff
        if src.get('values'): d['values'] = {v: src['values'].get(v, t) for v, t in d.get('values', {}).items()}
    manifest['quirks'] = desc.get('quirks', manifest.get('quirks', []))
    aa = (desc.get('sceneAntialiasing') or {}).get(platform)   # IDE screenshots: the text antialiasing they were taken with (the LCD screen check needs it)
    if aa and not manifest.get('setDimensions', [{}])[0].get('key') == 'aa': manifest['antialiasing'] = aa
    manifest['presets'] = desc.get('presets', manifest.get('presets', {}))
    arms = desc.get('arms', {})
    def arm_entry(label):   # an entry of descriptions.json, or the dimensions a generated sheet arm's name spells
        return arms.get(label) or arm_from_name(label or '')
    for s in manifest.get('sets', []):
        for a in s.get('arms', []):
            e = arm_entry(a.get('label'))
            if e:
                for k in ('description', 'title', 'dims'):
                    if k in e: a[k] = dict(e[k]) if isinstance(e[k], dict) else e[k]
    return manifest

def drop_retired_arms(manifest):
    """Arms listed under retiredArms in descriptions.json leave every page (their captures stay on disk);
    a set with fewer than two arms left goes too."""
    if not os.path.exists(DESC): return manifest
    retired = set(json.load(open(DESC)).get('retiredArms', []))
    if not retired: return manifest
    for s in manifest.get('sets', []): s['arms'] = [a for a in s.get('arms', []) if a.get('label') not in retired]
    manifest['sets'] = [s for s in manifest.get('sets', []) if len(s.get('arms', [])) >= 2]
    return manifest

def page_copy(manifest, platform, kind):
    """The page's title and the samples' description for the audience (descriptions.json pages[platform][kind]);
    manifest.title stays the capture's own name, which the refresh matches source manifests by."""
    if not os.path.exists(DESC): return manifest
    page = ((json.load(open(DESC)).get('pages') or {}).get(platform) or {}).get(kind) or {}
    if page.get('title'): manifest['pageTitle'] = page['title']
    if page.get('description'):
        for s in manifest.get('sets', []): s['description'] = page['description']
    return manifest

def drop_foreign_dimensions(manifest, platform):
    """A dimension tagged with a platform in descriptions.json (the LCD glyph source is Windows-only)
    is removed from the other platforms' pages and from their arms' dims (shared arm labels carry it)."""
    if not os.path.exists(DESC): return manifest
    desc = json.load(open(DESC))
    foreign = {d['key'] for d in desc.get('dimensions', []) if d.get('platform') and d['platform'] != platform}
    if not foreign: return manifest
    manifest['dimensions'] = [d for d in manifest.get('dimensions', []) if d['key'] not in foreign]
    for s in manifest.get('sets', []):
        for a in s.get('arms', []):
            for k in foreign: a.get('dims', {}).pop(k, None)
    return manifest

def filter_values(manifest):
    present = {}
    for s in manifest.get('sets', []):
        for a in s.get('arms', []):
            for k, v in a.get('dims', {}).items(): present.setdefault(k, set()).add(v)
    for d in manifest.get('dimensions', []):
        d['values'] = {v: t for v, t in d.get('values', {}).items() if v in present.get(d['key'], set())}
    return manifest

def current_entries():
    cat = load_manifest(CATALOG)
    by_title = {}
    for f in glob.glob(os.path.join(BUILD, '**', 'viewer', 'manifest.js'), recursive=True):
        if '/catalog/' in f: continue
        try: by_title[load_manifest(f)['title']] = f
        except Exception: pass
    out = []
    for e in cat:
        path = by_title.get(e['manifest']['title'])
        if not path:
            print('no source manifest found for', e['manifest']['title'], '- keeping the embedded copy', file=sys.stderr)
            out.append((e['platform'], e['kind'], None, e['manifest']))
        else:
            out.append((e['platform'], e['kind'], path, None))
    return out

entries = [(a.split('=')[0], a.split('=')[1], a.split('=', 2)[2], None) for a in sys.argv[1:]] or current_entries()
catalog = []
for platform, kind, path, embedded in entries:
    m = filter_values(drop_foreign_dimensions(page_copy(drop_retired_arms(refresh_content(reroot(load_manifest(path), os.path.dirname(path)) if path else embedded, platform)), platform, kind), platform))
    catalog.append({'platform': platform, 'kind': kind, 'manifest': m})
    print('%-8s %-6s %s' % (platform, kind, path or '(embedded)'))
os.makedirs(OUTDIR, exist_ok=True)
with open(CATALOG, 'w') as fh:
    fh.write('window.COMPARE_CATALOG = ' + json.dumps(catalog, indent=1, ensure_ascii=False) + ';\n')
    start = json.load(open(DESC)).get('start') if os.path.exists(DESC) else None   # the page, set and preset a visitor starts on
    if start: fh.write('window.COMPARE_START = ' + json.dumps(start, ensure_ascii=False) + ';\n')
# the page files beside it: the viewer, its stylesheet-less page stamped with the viewer's hash, the server, the LCD samples;
# serve-root names the repository as the server's root (the trees are reached through ../../ from here)
import hashlib, shutil
viewer_dir = os.path.join(HERE, '..')
shutil.copy(os.path.join(viewer_dir, 'viewer.js'), OUTDIR)
shutil.copy(os.path.join(HERE, 'serve.sh'), OUTDIR)
for f in ('lcd-rgb.png', 'lcd-bgr.png'): shutil.copy(os.path.join(viewer_dir, f), OUTDIR)
stamp = hashlib.sha1(open(os.path.join(viewer_dir, 'viewer.js'), 'rb').read()).hexdigest()[:10]
with open(os.path.join(OUTDIR, 'index.html'), 'w') as fh: fh.write(open(os.path.join(viewer_dir, 'index.html')).read().replace('@VERSION@', stamp))
with open(os.path.join(OUTDIR, 'serve-root'), 'w') as fh: fh.write(os.path.relpath(REPO, OUTDIR) + '\n')
print('wrote', CATALOG)
