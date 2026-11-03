#!/usr/bin/env python3
"""Installs the comparison viewer into <root>/viewer and writes its manifest from the images under <root>.

  make-viewer.py <root> [--arms a,b,c] [--flat] [--title T] [--descriptions file.json]
                        [--set-dims aa,positioning,scale,paper] [--set-description text] [--exclude-dirs review-,clusters-]

Nested layout (the scene matrices): <root>/<arm>/<...>/<name>.png; every path below the arm directory
is one set, its arms are the top-level directories that have that path. --arms fixes the arm order
(first = the natural A), otherwise alphabetical. --set-dims names the path components of a set as
dimensions (a name 'theme-scale' splits a component like 'dark-2x' on the dash); the viewer then
offers the sets as radio columns like the arms.
Flat layout (--flat, the sheets): <root>/*.png is one set whose arms are the files; a 'text-' or
'sheet-' prefix is dropped from the labels. A rows.txt beside the images gives the row labels
(row height 56 px) that the viewer shows under the cursor.

Arm dimensions and descriptions come from descriptions.json beside this script (or --descriptions):
arms are matched by label; the theme of nested sets (a 'dark-' / 'light-' path component) becomes the
'theme' dimension.
"""
import json, os, re, shutil, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kitpaths import arm_from_name

def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__); sys.exit(2)
    root = os.path.abspath(args[0])
    here = os.path.dirname(os.path.abspath(__file__))
    import platform as _pf
    opts = {'arms': None, 'flat': False, 'title': os.path.basename(root), 'exclude': ['review-', 'clusters-', 'viewer'],
            'platform': {'Darwin': 'macOS', 'Windows': 'Windows'}.get(_pf.system(), 'Linux'),
            'descriptions': os.path.join(here, 'descriptions.json'), 'setdesc': None, 'setdims': None}
    i = 1
    while i < len(args):
        if args[i] == '--arms': opts['arms'] = args[i + 1].split(','); i += 2
        elif args[i] == '--flat': opts['flat'] = True; i += 1
        elif args[i] == '--title': opts['title'] = args[i + 1]; i += 2
        elif args[i] == '--descriptions': opts['descriptions'] = args[i + 1]; i += 2
        elif args[i] == '--set-description': opts['setdesc'] = args[i + 1]; i += 2
        elif args[i] == '--set-dims': opts['setdims'] = args[i + 1].split(','); i += 2
        elif args[i] == '--exclude-dirs': opts['exclude'] = args[i + 1].split(','); i += 2
        elif args[i] == '--platform': opts['platform'] = args[i + 1]; i += 2   # macOS | Windows | Linux: picks the display labels
        else: print('unknown option', args[i]); sys.exit(2)
    desc = {'dimensions': [], 'arms': {}, 'sets': {}}
    if os.path.exists(opts['descriptions']):
        with open(opts['descriptions']) as fh: desc = json.load(fh)

    def describe(arm, label, labels=()):
        d = desc['arms'].get(label, {}) or arm_from_name(label) or {}
        if d.get('dims'): arm['dims'] = dict(d['dims'])
        if d.get('description'): arm['description'] = d['description']
        if d.get('title'): arm['title'] = d['title']
        return arm

    sets = []
    if opts['flat']:
        files = sorted(f for f in os.listdir(root) if f.lower().endswith('.png'))
        arms = [describe({'label': strip(f), 'src': '../' + f}, strip(f)) for f in files]
        if opts['arms']:
            order = {a: k for k, a in enumerate(opts['arms'])}
            arms.sort(key=lambda a: order.get(a['label'], len(order)))
        s = {'name': os.path.basename(root), 'arms': arms}
        rows = os.path.join(root, 'rows.txt')
        if os.path.exists(rows):
            with open(rows) as fh: s['rows'] = [l.rstrip('\n') for l in fh if l.strip()]
            s['rowHeight'] = png_height(os.path.join(root, files[0])) // len(s['rows'])
            s['description'] = opts['setdesc'] or desc['sets'].get('text-sheets', '')
        elif opts['setdesc']: s['description'] = opts['setdesc']
        sets.append(s)
    else:
        armdirs = sorted(d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d)) and not any(d.startswith(x) for x in opts['exclude']))
        if opts['arms']:
            order = {a: k for k, a in enumerate(opts['arms'])}
            armdirs.sort(key=lambda d: order.get(d, len(order)))
        bysets = {}
        for arm in armdirs:
            for dirpath, _, files in os.walk(os.path.join(root, arm)):
                for f in sorted(files):
                    if not f.lower().endswith('.png'): continue
                    rel = os.path.relpath(os.path.join(dirpath, f), os.path.join(root, arm)).replace(os.sep, '/')
                    a = describe({'label': arm, 'src': '../' + arm + '/' + rel}, arm, armdirs)
                    m = re.search(r'(^|/)(dark|light)(-|/|$)', rel)
                    if m: a.setdefault('dims', {})['theme'] = m.group(2)
                    bysets.setdefault(rel, []).append(a)
        rows = os.path.join(root, 'rows.txt')
        rowlabels = None
        if os.path.exists(rows):
            with open(rows) as fh: rowlabels = [l.rstrip('\n') for l in fh if l.strip()]
        for rel in sorted(bysets):
            if len(bysets[rel]) < 2: continue
            s = {'name': rel[:-4], 'arms': bysets[rel]}
            if opts['setdims']:
                parts = rel[:-4].split('/')
                dims = {}
                for name, part in zip(opts['setdims'], parts):
                    if '-' in name:
                        for n, v in zip(name.split('-'), part.split('-')): dims[n] = v
                    else: dims[name] = part
                s['dims'] = dims
            if rowlabels:
                s['rows'] = rowlabels; s['rowHeight'] = png_height(os.path.join(root, bysets[rel][0]['src'][3:])) // len(rowlabels)
                s['description'] = opts['setdesc'] or desc['sets'].get('text-sheets', '')
            elif opts['setdesc']: s['description'] = opts['setdesc']
            sets.append(s)
    if not sets:
        print('no comparable images under', root); sys.exit(1)
    used = {k for s in sets for a in s['arms'] for k in a.get('dims', {})}
    # a page lists only the values its arms carry: descriptions.json describes every platform's
    # arms, and a Windows-only value (DirectWrite, GDI) has no place on a macOS page
    present = {}
    for s_ in sets:
        for a in s_['arms']:
            for k, v in a.get('dims', {}).items(): present.setdefault(k, set()).add(v)
    dimensions = [dict(d, values={v: t for v, t in d.get('values', {}).items() if v in present.get(d['key'], set())})
                  for d in desc.get('dimensions', []) if d['key'] in used]
    for d in dimensions:   # display labels and effects: the platform's over the common ones
        for part in ('labels', 'effects'):
            lab = d.get(part) or {}
            if isinstance(lab, dict) and ('*' in lab or any(isinstance(v, dict) for v in lab.values())):
                merged = dict(lab.get('*', {})); merged.update(lab.get(opts.get('platform') or '', {}))
                d[part] = merged
    usedsets = {k for s in sets for k in s.get('dims', {})}
    known = {d['key']: d for d in desc.get('dimensions', [])}
    setdimensions = [dict(known.get(k, {'key': k, 'name': k, 'values': {}})) for k in (opts['setdims'] and [n for name in opts['setdims'] for n in name.split('-')] or []) if k in usedsets]
    for d in setdimensions:   # the same label rule as for the arm dimensions
        lab = d.get('labels') or {}
        if isinstance(lab, dict) and ('*' in lab or any(isinstance(v, dict) for v in lab.values())):
            merged = dict(lab.get('*', {})); merged.update(lab.get(opts.get('platform') or '', {}))
            d['labels'] = merged
    out = os.path.join(root, 'viewer'); os.makedirs(out, exist_ok=True)
    for f in ('viewer.js', 'serve.sh'):
        shutil.copy(os.path.join(here, f), out)
    # a version stamp on the script URLs so a browser never shows a stale viewer after a regeneration
    import hashlib, time
    stamp = hashlib.sha1((open(os.path.join(here, 'viewer.js'), 'rb').read() + str(time.time()).encode())).hexdigest()[:10]
    with open(os.path.join(here, 'index.html')) as fh: html = fh.read().replace('@VERSION@', stamp)
    with open(os.path.join(out, 'index.html'), 'w') as fh: fh.write(html)
    with open(os.path.join(out, 'manifest.js'), 'w') as fh:
        fh.write('window.COMPARE_MANIFEST = ' + json.dumps({'title': opts['title'], 'dimensions': dimensions, 'setDimensions': setdimensions, 'sets': sets, 'quirks': desc.get('quirks', []), 'presets': desc.get('presets', {})}, indent=1, ensure_ascii=False) + ';\n')
    undescribed = sorted({a['label'] for s in sets for a in s['arms'] if 'dims' not in a})
    print('%d sets, %d images -> %s' % (len(sets), sum(len(s['arms']) for s in sets), out))
    if undescribed: print('arms without dimensions (add them to descriptions.json): ' + ', '.join(undescribed))
    print('open with: bash %s' % os.path.join(out, 'serve.sh'))

def png_height(path):
    """Height from the PNG header (IHDR), no image library needed."""
    import struct
    with open(path, 'rb') as fh:
        head = fh.read(24)
    return struct.unpack('>I', head[20:24])[0]

def strip(f):
    n = f[:-4]
    for p in ('text-', 'sheet-'):
        if n.startswith(p): n = n[len(p):]
    return n

if __name__ == '__main__':
    main()
