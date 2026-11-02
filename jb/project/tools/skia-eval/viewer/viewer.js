// Rendering comparison viewer: the arms of one comparison set (captures or sheets of the same scene
// through different pipelines, scalers, ...), shown one at a time, flipped, blended, flickered, as an
// amplified difference or as sub-pixel stripes, at any zoom without interpolation (every device pixel
// shows exactly one image pixel). Arms carry dimensions (pipeline, scaler, ...) so A and B can be
// chosen per dimension; the documentation panel explains the selected values.
//
// manifest.js: window.COMPARE_MANIFEST = {
//   title, dimensions: [ { key, name, values: { value: description } } ],
//   sets: [ { name, description, rows: [label...], rowHeight, arms: [ { label, src, dims: {key: value}, description } ] } ] }
(function () {
  'use strict';
  // one viewer, several comparison trees: a catalog of {platform, kind, manifest}; a single manifest is a catalog of one
  const CATALOG = window.COMPARE_CATALOG || [{ platform: 'this machine', kind: (window.COMPARE_MANIFEST.sets[0].rows ? 'text sheets' : 'IDE scenes'), manifest: window.COMPARE_MANIFEST }];
  const KIND_NAMES = { text: 'text samples', scenes: 'IDE screenshots' };
  const MODE_NAMES = { single: 'single', flicker: 'alternating A / B', diff: 'difference |A − B|', stripes: 'color channels' };
  const name = (a) => a.title || a.label;
  let entry = CATALOG[0];
  let M = entry.manifest;
  const PRESETS = [1, 2, 3, 4, 6, 8, 12, 16];
  const GAINS = [1, 4, 8, 16];
  const MODES = ['single', 'flicker', 'diff', 'stripes'];
  const other = (side) => side === 'a' ? 'b' : 'a';
  const ZMIN = 0.125, ZMAX = 32;
  const $ = (id) => document.getElementById(id);
  const canvas = $('c'), ctx = canvas.getContext('2d', { alpha: false });
  const stage = $('stage');

  const state = { set: 0, a: 0, b: 1, mode: 'single', side: 'a', gain: 4, rate: 500, zoom: 1, ox: 0, oy: 0, docs: true, preset: null, perf: false };
  const kindName = (k) => KIND_NAMES[k] || k;
  const isText = () => /text/.test(entry.kind);
  let flip = false;            // Space held
  let flickerPhase = false, flickerTimer = null;
  let pixelsAllowed = true;    // false once getImageData is refused (file:// origin)
  const cache = new Map();     // src -> entry
  let mouse = null;            // last device position over the canvas

  // ---- manifest and selection -------------------------------------------------------------
  const sets = () => M.sets;
  const current = () => sets()[state.set];
  const arm = (i) => current().arms[Math.min(i, current().arms.length - 1)];
  const dimsOf = (a) => a.dims || {};
  const dimensions = () => M.dimensions || [];
  // dimensions whose values vary across the arms of the current set, in manifest order
  function varyingDims() {
    return dimensions().filter(d => new Set(current().arms.map(a => dimsOf(a)[d.key] || '')).size > 1);
  }
  function fillSelect(sel, items, value) {
    sel.innerHTML = '';
    items.forEach((label, i) => { const o = document.createElement('option'); o.value = i; o.textContent = label; sel.appendChild(o); });
    sel.value = value;
  }
  // Dimension controls for a list of items (the arms of a slot, or the sets): one radio column per
  // dimension in manifest order. A dimension whose values only vary under one value of an earlier
  // dimension (text blend under renderer = metal, grayscale route under scaler = skia) becomes a
  // dropdown attached to that value instead of a column, so nothing appears or disappears while
  // choosing. Columns always show all values; a value that no item has together with the choices in
  // the earlier columns is disabled, and a column with nothing to choose under them is disabled whole.
  // Every arm of every set of the manifest: the universe a column's rows come from, so the same
  // rows (and the same bar height) appear whichever set is shown; rows the set lacks are disabled.
  function allArms() { return sets().flatMap(s => s.arms); }
  function longestLabel(dim, universe) {
    return Math.max(dim.name.length, ...universe.map(u => String((u.dims || {})[dim.key] || '').length));
  }
  function buildControls(box, dims, items, dimsOfItem, currentIndex, onPick, universe) {
    box.innerHTML = '';
    const d = (i) => dimsOfItem(items[i]);
    const mine = d(currentIndex);
    const universeValues = (dim) => universe ? [...new Set(universe.map(u => (u.dims || {})[dim.key]).filter(Boolean))] : null;
    const indices = items.map((_, i) => i);
    const match = (choices) => indices.filter(i => Object.keys(choices).every(k => d(i)[k] === choices[k]));
    const valuesOf = (dim, pool) => [...new Set(pool.map(i => d(i)[dim.key]).filter(Boolean))];
    // a dimension gets a column when it varies here, or anywhere in the kind (the macOS scenes have one scale: the column stays, its other values disabled)
    const relevant = dims.filter(dim => valuesOf(dim, indices).length > 1 || (universeValues(dim) || []).length > 1);
    // attachments: a dimension becomes dropdowns on the values of an earlier dimension when it is
    // contextual to it — it varies under exactly one of its values (text blend under renderer = metal),
    // or its value sets under the parent's values are pairwise disjoint, i.e. it refines the parent
    // (grayscale route under scaler: native → native/native unsmoothed, skia → coverage/…/cg).
    // The nearest earlier dimension that qualifies wins, so the dropdown sits next to the most
    // specific choice (text layout next to blending = skia text path, not next to pipeline = skia).
    const attached = new Map();   // dim key -> { parent, values: the parent values that get a dropdown }
    relevant.forEach((dim, k) => {
      if (dim.column) return;   // declared a column of its own (text layout): never a dropdown
      if (dim.under) {   // declared a dropdown on the values of a named earlier dimension where it exists (color under paper)
        const parent = relevant.slice(0, k).find(p => p.key === dim.under);
        const present = parent && !attached.has(parent.key) ? valuesOf(parent, indices).filter(v => valuesOf(dim, match({ [parent.key]: v })).length > 0) : [];
        if (present.length) { attached.set(dim.key, { parent, values: present }); return; }
      }
      for (let j = k - 1; j >= 0 && !attached.has(dim.key); j--) {
        const parent = relevant[j]; if (attached.has(parent.key) || valuesOf(parent, indices).length < 2) continue;   // a parent must vary here (a lone scale value is no context)
        const perValue = valuesOf(parent, indices).map(v => ({ v, set: valuesOf(dim, match({ [parent.key]: v })) }));
        const varying = perValue.filter(x => x.set.length > 1).map(x => x.v);
        const disjoint = perValue.every((a, i) => perValue.every((b, j2) => i === j2 || !a.set.some(x => b.set.includes(x))));
        if (varying.length === 1 || (disjoint && varying.length > 0)) attached.set(dim.key, { parent, values: varying });
      }
    });
    const choices = {};
    for (const dim of relevant) {
      if (attached.has(dim.key)) continue;
      const before = { ...choices };
      const pool = match(before);
      const available = valuesOf(dim, pool);
      const chosen = available.includes(mine[dim.key]) ? mine[dim.key] : available[0];
      const col = document.createElement('div'); col.className = 'col';
      const head = document.createElement('div'); head.className = 'colname'; head.textContent = dim.name; head.title = dim.description || dim.name; col.appendChild(head);
      // rows from the whole manifest in manifest order, so the column keeps its shape across sets
      const seen = valuesOf(dim, indices), everywhere = universeValues(dim);
      let all = everywhere ? everywhere.concat(seen.filter(v => !everywhere.includes(v))) : seen;
      const order = box.id === 'dimsS' ? Object.keys(dim.values || {}) : [];   // set dimensions in the order the descriptions list their values
      if (order.length) all = all.slice().sort((p, q) => ((order.indexOf(p) + 1) || 1e9) - ((order.indexOf(q) + 1) || 1e9));
      if (all.length && all.every(v => /^\d+(\.\d+)?x$/.test(v))) all = all.slice().sort((p, q) => parseFloat(p) - parseFloat(q));   // display scales ascending
      if (universe) { col.dataset.basis = (longestLabel(dim, universe) + 4) + 'ch'; col.style.flex = '0 1 ' + col.dataset.basis; }
      if (all.length > 6) {   // a long list (the ten scenes) is a dropdown, not a column of radios
        const sel = document.createElement('select'); sel.title = dim.description || dim.name;
        for (const v of all) { const o = document.createElement('option'); o.value = v; o.textContent = (dim.labels || {})[v] || v; o.disabled = !available.includes(v); o.title = (dim.values || {})[v] || ''; sel.appendChild(o); }
        sel.value = chosen; sel.onchange = () => onPick({ ...before, [dim.key]: sel.value });
        col.appendChild(sel); box.appendChild(col);
        if (chosen !== undefined) choices[dim.key] = chosen;
        continue;
      }
      for (const v of all) {
        const l = document.createElement('label'); l.title = (dim.values || {})[v] || '';
        const r = document.createElement('input'); r.type = 'radio'; r.name = box.id + '-' + dim.key; r.value = v;
        r.checked = v === chosen; r.disabled = !available.includes(v);
        r.onchange = () => onPick({ ...before, [dim.key]: v });
        l.appendChild(r); const t = document.createElement('span'); t.className = 't'; t.textContent = (dim.labels || {})[v] || v; l.appendChild(t);
        // the dropdowns attached to this value
        for (const [key, att] of attached) if (att.parent === dim && att.values.includes(v)) {
          const sub = relevant.find(x => x.key === key);
          const under = { ...before, [dim.key]: v };
          const subValues = valuesOf(sub, match(under));
          const sel = document.createElement('select'); sel.title = sub.description || sub.name;
          for (const sv of subValues) { const o = document.createElement('option'); o.value = sv; o.textContent = sub.name + ': ' + ((sub.labels || {})[sv] || sv); o.title = (sub.values || {})[sv] || ''; sel.appendChild(o); }
          const current = v === chosen && subValues.includes(mine[key]) ? mine[key] : subValues[0];
          sel.value = current; sel.disabled = !(v === chosen) || !available.includes(v);
          if (v === chosen) choices[key] = current;   // the dropdown's value counts as a choice too
          sel.onchange = () => onPick({ ...under, [key]: sel.value });
          l.appendChild(sel);
        }
        col.appendChild(l);
      }
      box.appendChild(col);
      if (chosen !== undefined) choices[dim.key] = chosen;
    }
    const rest = match(choices);
    if (rest.length > 1 || (universe && box.id !== 'dimsS')) {   // items that share every dimension: choose by name (the column is reserved on arm bars)
      const col = document.createElement('div'); col.className = 'col';
      const head = document.createElement('div'); head.className = 'colname'; head.textContent = 'rendering'; col.appendChild(head);
      if (rest.length <= 1) { col.classList.add('reserved'); }
      for (const i of rest.length > 1 ? rest : []) {
        const l = document.createElement('label'); l.title = items[i].description || '';
        const r = document.createElement('input'); r.type = 'radio'; r.name = box.id + '-item'; r.value = i; r.checked = i === currentIndex;
        r.onchange = () => onPick(null, i);
        l.appendChild(r); const t = document.createElement('span'); t.className = 't'; t.textContent = name(items[i]); l.appendChild(t);
        col.appendChild(l);
      }
      box.appendChild(col);
    }
  }
  // the item closest to the current one among those matching the choices (later dimensions keep their value when they can)
  function nearest(items, dimsOfItem, currentIndex, choices) {
    const mine = dimsOfItem(items[currentIndex]);
    let best = -1, score = -1;
    items.forEach((it, i) => {
      const dd = dimsOfItem(it);
      if (!Object.keys(choices).every(k => dd[k] === choices[k])) return;
      let sc = 0; for (const k in mine) if (!(k in choices) && dd[k] === mine[k]) sc++;
      if (sc > score) { score = sc; best = i; }
    });
    return best;
  }
  // Suggested comparisons per content kind: set A, B and the way they are shown
  // A preset names its two arms either directly (a, b) or per platform (arms: {platform: [a, b]}), so
  // one entry serves every platform and a platform switch keeps the chosen preset (state.preset, its id).
  function presetArms(p) { return p.arms ? (p.arms[entry.platform] || null) : [p.a, p.b]; }
  function presetAvailable(p, set) { const ab = presetArms(p); return !!ab && ab.every(l => (set || current()).arms.some(a => a.label === l)); }
  function presetsFor() {   // the presets that exist on this platform's pages at all
    return ((M.presets || {})[entry.kind] || []).filter(p => presetArms(p) && sets().some(st => presetAvailable(p, st)));
  }
  function presetIndex() { return presetsFor().findIndex(p => { const ab = presetArms(p); return ab && arm(state.a).label === ab[0] && arm(state.b).label === ab[1]; }); }
  function applyPreset(i) {
    const p = presetsFor()[i]; if (!p) return;
    const ab = presetArms(p); if (!ab) return;
    if (!presetAvailable(p)) {   // not in this set: the nearest set that has both arms
      const si = sets().findIndex(st => presetAvailable(p, st)); if (si < 0) return;
      state.set = si;
    }
    state.a = current().arms.findIndex(a => a.label === ab[0]); state.b = current().arms.findIndex(a => a.label === ab[1]);
    state.preset = p.id || p.name;
    if (p.mode) { state.mode = p.mode; flicker(p.mode === 'flicker'); }
    syncControls(); render();
  }
  // a preset may word the same comparison in a platform's own terms (names[platform]) and name its arms per platform
  function buildPresets() {
    const sel = $('preset'); sel.innerHTML = '';
    const o = document.createElement('option'); o.value = -1; o.textContent = 'suggested comparisons…'; sel.appendChild(o);
    presetsFor().forEach((p, i) => { const x = document.createElement('option'); x.value = i; x.textContent = (p.names || {})[entry.platform] || p.name; x.disabled = !presetAvailable(p); x.title = presetAvailable(p) ? '' : 'not in this sample set'; sel.appendChild(x); });
    sel.value = presetIndex();
    sel.style.display = presetsFor().length ? '' : 'none';
  }
  // The column names once, over both slots: a header row mirroring A's columns, every column of
  // the header, A and B set to one shared width so the three rows align.
  // A column's flex basis is its natural width (its content, at least the kind-wide basis): measured once, all
  // reads before all writes; offsetWidth rounds down (the longest label lost a pixel and got an ellipsis)
  const natural = (c) => { c.style.flex = '0 0 auto'; c.style.minWidth = c.dataset.basis || ''; };
  const wide = (c) => Math.ceil(c.getBoundingClientRect().width) + 1;
  const size = (c, w) => { c.style.flex = '0 1 ' + w + 'px'; c.style.minWidth = '0'; };
  function fitColumns(box) { const cols = [...box.children]; cols.forEach(natural); const ws = cols.map(wide); cols.forEach((c, i) => size(c, ws[i])); }
  function buildDimHeader() {
    const head = $('dimsH'); head.innerHTML = '';
    const colsA = [...$('dimsA').children], colsB = [...$('dimsB').children];
    colsA.forEach((colA, i) => {
      const colB = colsB[i];
      const name = colA.querySelector('.colname');
      const col = document.createElement('div'); col.className = 'col' + (colA.classList.contains('reserved') ? ' reserved' : '');
      const n = document.createElement('div'); n.className = 'colname'; n.textContent = name ? name.textContent : ''; n.title = name ? name.title : '';
      col.appendChild(n); head.appendChild(col);
    });
    // one layout for all the measurements, then all the writes (a read after each write forced a layout per column):
    // the natural width of each column (its content, at least the kind-wide basis) becomes the flex basis of the
    // header's, A's and B's column, so the three rows align and all columns shrink in proportion in a narrow window
    colsA.forEach(natural); colsB.forEach(natural);
    const widths = colsA.map((colA, i) => Math.max(wide(colA), colsB[i] ? wide(colsB[i]) : 0));
    [...head.children].forEach((col, i) => { size(col, widths[i]); size(colsA[i], widths[i]); if (colsB[i]) size(colsB[i], widths[i]); });
  }
  function buildDimColumns(slot, index) {
    buildControls($('dims' + slot), dimensions(), current().arms, dimsOf, index, (choices, direct) => {
      const i = direct !== undefined ? direct : nearest(current().arms, dimsOf, index, choices);
      if (i >= 0) { if (slot === 'A') state.a = i; else state.b = i; }
      state.preset = null;
      syncControls(); render();
    }, allArms());
  }
  // set-level dimensions (sheet: aa, positioning, scale, paper; scenes: theme, scale, scene), the
  // same column rule as for the arms; the select stays for sets without dimensions
  const setDims = () => (M.setDimensions || []);
  function matchingSets(choices) {
    return sets().map((st, i) => i).filter(i => Object.keys(choices).every(k => (sets()[i].dims || {})[k] === choices[k]));
  }
  function buildSetColumns() {
    buildControls($('dimsS'), setDims(), sets(), (st) => st.dims || {}, state.set, (choices, direct) => {
      const i = direct !== undefined ? direct : nearest(sets(), (st) => st.dims || {}, state.set, choices);
      if (i >= 0) selectSet(i); else { syncControls(); render(); }
    }, CATALOG.filter(c => c.kind === entry.kind).flatMap(c => (c.manifest.sets || [])));   // the same kind on every platform: scales unified, papers per kind
    fitColumns($('dimsS'));
    $('set').style.display = setDims().length === 0 ? '' : 'none';
  }

  function selectCollection(platform, kind) {
    const e = CATALOG.find(c => c.platform === platform && c.kind === kind) || CATALOG.find(c => c.kind === kind) || CATALOG.find(c => c.platform === platform);
    if (!e || e === entry) return;
    entry = e; M = e.manifest; document.title = (M.pageTitle || M.title || 'Text rendering comparison');
    state.set = 0; state.a = 0; state.b = Math.min(1, current().arms.length - 1); state.ox = 0; state.oy = 0;
    if (isText()) applyIdeDefaults(); else { syncControls(); render(); }
    const keep = presetsFor().findIndex(p => (p.id || p.name) === state.preset);
    if (keep >= 0) applyPreset(keep);
    current().arms.forEach(a => load(a.src));
  }
  function defaultsSelected() {
    const { dims } = ideDefaults();
    const d = current().dims || {};
    return setDims().every(sd => !(sd.key in dims) || d[sd.key] === undefined || d[sd.key] === dims[sd.key]);
  }

  // Quirks: notes from the descriptions that apply to the current set and arms ({ when: { arm: {k: v|[v]}, set: {k: v|[v]} }, text })
  function matches(cond, dims) {
    return Object.keys(cond || {}).every(k => { const want = cond[k]; const have = (dims || {})[k]; return Array.isArray(want) ? want.includes(have) : want === have; });
  }
  function activeQuirks() {
    const out = [];
    for (const q of (M.quirks || [])) {
      if (q.when.platform && q.when.platform !== entry.platform) continue;   // a note about one platform's stack
      if (q.when.kind && q.when.kind !== entry.kind) continue;   // a note that matters for the scenes only (or the sheets only)
      if (!matches(q.when.set, current().dims)) continue;
      const slots = [];
      if (q.when.arm) { if (matches(q.when.arm, dimsOf(arm(state.a)))) slots.push('A'); if (matches(q.when.arm, dimsOf(arm(state.b)))) slots.push('B'); if (!slots.length) continue; }
      out.push({ q, slots });
    }
    return out;
  }
  // The notes box: what decides whether the comparison is meaningful (not a candidate, falls short),
  // then what differs between A and B, then the remaining notes. A note is one line — its summary,
  // or the first sentence of its text — that opens to the full text (descriptions.json quirks[].summary).
  const dimLabel = (dim, v) => (dim.labels || {})[v] || v;
  function differences() {
    if (!isText() && !sets().length) return '';
    const A = dimsOf(arm(state.a)), B = dimsOf(arm(state.b));
    const lines = [];
    for (const dim of dimensions()) {
      const a = A[dim.key], b = B[dim.key];
      if (!a || !b || a === b) continue;
      const eff = dim.effects || {};
      lines.push('<div class="d"><span class="k">' + esc(dim.name) + '</span> ' + esc(dimLabel(dim, a)) + ' <i>→</i> ' + esc(dimLabel(dim, b)) + '</div>'
        + (eff[a] || eff[b] ? '<div class="effects">' + (eff[a] ? '<span><b>A</b> ' + rich(eff[a]) + '</span>' : '') + (eff[b] ? '<span><b>B</b> ' + rich(eff[b]) + '</span>' : '') + '</div>' : ''));
    }
    if (!lines.length) return '<div class="quirk diff"><b>Same rendering stack</b> on A and B: any difference is noise, timing or a defect.</div>';
    return '<div class="quirk diff"><b>What differs</b>' + lines.join('') + '</div>';
  }
  const splitSentence = (t) => { const m = /^(.*?[.!?])(\s+(.*))?$/s.exec(t); return m && m[3] ? [m[1], m[3]] : [t, '']; };
  function noteHtml({ q, slots }) {
    const KIND = { note: ['quirk', '⚠ Note'], invalid: ['quirk invalid', '⛔ Not a candidate'], shortfall: ['quirk shortfall', '△ Falls short'] };
    const [cls, head] = KIND[q.kind || 'note'] || KIND.note;
    const where = slots.length ? ' on ' + slots.join(' and ') : '';
    const [summary, rest] = q.summary ? [q.summary, q.text || ''] : splitSentence(q.text || '');
    const line = '<b>' + head + where + ':</b> ' + rich(summary);
    return rest ? '<details class="' + cls + '"><summary>' + line + '</summary><div>' + rich(rest) + '</div></details>' : '<div class="' + cls + '">' + line + '</div>';
  }
  function quirks() {
    const list = activeQuirks();
    // verdicts first, then notes about the sample itself (no arm condition: the scene's layout noise), then what differs, then the arms' notes
    const rank = (x) => ({ invalid: 0, shortfall: 1 })[x.q.kind || 'note'] ?? (x.q.when && x.q.when.arm ? 3 : 2);
    const sorted = list.slice().sort((p, q) => rank(p) - rank(q));
    const box = $('quirks');
    box.classList.toggle('on', true);
    box.innerHTML = sorted.filter(x => rank(x) < 3).map(noteHtml).join('') + differences() + sorted.filter(x => rank(x) >= 3).map(noteHtml).join('');
  }

  // What IntelliJ would use on the machine the viewer runs on (UISettings / AntialiasingType rules,
  // 2026.3): macOS never gets sub-pixel AA (gray) and runs with fractional metrics (Auto resolves to
  // on since Catalina); Windows and Linux default to sub-pixel AA (LCD) and whole-pixel advances
  // (Auto resolves to off with LCD). Scale from the display's pixel ratio, paper from the OS theme.
  function ideDefaults() {
    const mac = entry.platform === 'macOS', win = entry.platform === 'Windows';   // the page's platform, not the viewer's
    const dpr = window.devicePixelRatio || 1;
    const dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    const d = { aa: mac ? 'gray' : 'lcd', positioning: mac ? 'fractional' : 'whole-pixel', scale: dpr >= 1.5 ? '2x' : '1x',
      paper: dark ? 'white-on-black' : 'black-on-white', theme: dark ? 'dark' : 'light' };
    const why = (mac ? 'macOS: grayscale AA (sub-pixel AA is off on macOS unless enable.macos.*.subpixelAA), fractional metrics (Auto → on)'
      : (win ? 'Windows' : 'Linux') + ': sub-pixel AA (LCD) and whole-pixel advances (Auto → off with LCD)')
      + '; display scale ' + dpr + ' → ' + d.scale + '; OS theme ' + (dark ? 'dark' : 'light') + '. Dimensions this set does not have are left alone.';
    return { dims: d, why };
  }
  function applyIdeDefaults() {
    const { dims } = ideDefaults();
    const keys = setDims().map(d => d.key).filter(k => k in dims);
    let best = -1, score = -1;
    sets().forEach((st, i) => {
      const d = st.dims || {};
      let sc = 0; for (const k of keys) if (d[k] === dims[k]) sc++;
      for (const k in d) if (!(k in dims) && d[k] === (current().dims || {})[k]) sc += 0.5;   // keep e.g. the scene
      if (sc > score) { score = sc; best = i; }
    });
    if (best >= 0) selectSet(best);
  }

  // ---- the screen's sub-pixel order (LCD samples are RGB-striped) ------------------------------
  // No web API tells a display's sub-pixel order, so the viewer asks once per screen — RGB vs BGR samples
  // side by side — and keeps the answer in localStorage, keyed by the monitor (its name when the
  // window-management permission is given, else its geometry and pixel ratio).
  let screenKey = null, screenInternal = null;
  function screenGeometry() { return screen.width + 'x' + screen.height + '@' + (window.devicePixelRatio || 1); }
  function currentScreenKey() { return screenKey || screenGeometry(); }
  function lcdAnswer() { try { return localStorage.getItem('lcdOrder:' + currentScreenKey()); } catch (e) { return null; } }
  function lcdSelected() { return isText() ? (current().dims || {}).aa === 'lcd' : M.antialiasing === 'lcd'; }   // sheets: the selected mode; screenshots: what the IDE drew with
  function updateLcdBadge() {
    const b = $('lcdcheck'); if (!b) return;
    b.hidden = !lcdSelected(); if (b.hidden) return;
    const a = lcdAnswer();
    b.className = a === 'rgb' ? '' : 'warn';
    const what = isText() ? 'LCD samples' : 'LCD screenshots';
    b.textContent = a === 'rgb' ? 'screen: RGB ✓' : a === 'bgr' ? '\u26a0 sub-pixel order mismatch: this screen is BGR, the ' + what + ' are RGB'
      : a === 'other' ? '\u26a0 this screen has no horizontal RGB stripes: the ' + what + ' cannot show their effect here' : '\u26a0 check this screen\u2019s sub-pixel order for the ' + what;
    b.title = a === 'rgb' ? 'The ' + what + ' were rendered for horizontal RGB stripes, like this screen. Click to check again.'
      : 'The ' + what + ' were rendered for horizontal RGB stripes. On a screen with another order the colour fringes land on the wrong sub-pixels: the text looks rough and coloured instead of sharper, so LCD quality cannot be judged here. The fringes also only align at zoom 1 on a 1x screen; at higher zoom or pixel density they show as colour bands. Click to check again.';
  }
  function lcdHint() {
    const mac = /Mac/.test(navigator.platform), dpr = window.devicePixelRatio || 1;
    let hint = '';
    if (mac && screenInternal) hint += 'A built-in Mac display is RGB. ';
    else if (mac) hint += 'Built-in Mac displays are RGB; external monitors vary. ';
    if (dpr > 1) hint += 'This is a high-density screen (' + dpr + ' device pixels per CSS pixel), so the samples are shown at ' + Math.round(100 / dpr) + ' % of their usual size to keep one device pixel per sample pixel. That is how small LCD fringes are here: look closely, or choose "can\u2019t tell".';
    return hint;
  }
  function openLcdDialog() {
    const dpr = window.devicePixelRatio || 1;
    for (const id of ['lcdimgrgb', 'lcdimgbgr']) {   // one device pixel per sample pixel: CSS size = natural size / pixel ratio (never scaled)
      const img = $(id); img.onload = () => { img.style.width = (img.naturalWidth / dpr) + 'px'; img.style.height = (img.naturalHeight / dpr) + 'px'; };
      img.src = id === 'lcdimgrgb' ? 'lcd-rgb.png' : 'lcd-bgr.png'; if (img.complete && img.naturalWidth) img.onload();
    }
    $('lcdkey').textContent = currentScreenKey(); $('lcdhint').textContent = lcdHint();
    $('lcddlg').classList.add('on');
    // Chromium: the monitor's own name as the key (asks for the window-management permission once); the
    // dialog does not wait for it — the key and the hint update when the answer comes
    if (window.getScreenDetails && !screenKey) {
      window.getScreenDetails().then(d => {
        const use = () => { const c = d.currentScreen; screenKey = (c.label || screenGeometry()) + (c.isInternal ? ' (built-in)' : ''); screenInternal = c.isInternal; $('lcdkey').textContent = currentScreenKey(); $('lcdhint').textContent = lcdHint(); updateLcdBadge(); };
        use(); d.addEventListener('currentscreenchange', use);
      }).catch(() => { /* denied: the geometry stays the key */ });
    }
  }
  function answerLcd(order) {
    try { localStorage.setItem('lcdOrder:' + currentScreenKey(), order); } catch (e) { /* no storage: the badge keeps asking */ }
    $('lcddlg').classList.remove('on'); updateLcdBadge();
  }
  function syncControls() {
    const platforms = [...new Set(CATALOG.map(c => c.platform))], kinds = [...new Set(CATALOG.map(c => c.kind))];
    fillSelect($('platform'), platforms, platforms.indexOf(entry.platform));
    fillSelect($('kind'), kinds.map(kindName), kinds.indexOf(entry.kind));
    $('collection').classList.toggle('hidden', !(platforms.length > 1 || kinds.length > 1));
    fillSelect($('set'), sets().map(s => s.name), state.set);
    buildSetColumns();
    // IntelliJ's defaults apply to the sheets' rendering dimensions; disabled once they are selected
    $('defaults').style.display = isText() ? '' : 'none';
    $('defaults').disabled = defaultsSelected();
    $('defaults').title = ideDefaults().why;
    updateLcdBadge();
    quirks();
    buildDimColumns('A', state.a); buildDimColumns('B', state.b);
    buildDimHeader();
    buildPresets();
    $('setLegend').textContent = isText() ? 'Sample conditions' : 'Screenshot';
    docs();   // the explanation follows the arms, not the mode
    syncModes();
  }
  // The comparison widgets (mode, blend, gain, rate, zoom, docs): nothing about the arms changed, so the
  // control columns, the notes and the presets stay; the explanation panel follows the mode and the docs toggle.
  function syncModes() {
    document.querySelectorAll('#modes input[type=radio]').forEach(r => r.checked = r.value === state.mode);
    document.querySelectorAll('#modes .sub').forEach(el => {
      const on = el.dataset.for === state.mode;
      el.classList.toggle('off', !on);
      el.querySelectorAll('input, select').forEach(c => c.disabled = !on);
    });
    $('rate').value = state.rate;
    $('gain').value = state.gain;
    showZoom();
    $('docstoggle').checked = state.docs; document.body.classList.toggle('docs', state.docs);
    writeHash();
  }
  // Pan and zoom change only the zoom buttons, the zoom text and the address bar: the control
  // columns, the notes and the explanation panel stay as they are. syncControls() rebuilt all of
  // them (hundreds of elements, forced layouts, the panel's innerHTML, history.replaceState) on
  // every wheel event — the stutter the Firefox profile of 2026-10-26 showed.
  let hashTimer = null;
  function syncView() {
    showZoom();
    if (!hashTimer) hashTimer = setTimeout(() => { hashTimer = null; writeHash(); }, 300);   // the address bar at most a few times a second
  }
  function showZoom() {
    const f = $('zoomv');
    if (document.activeElement !== f) f.value = Number.isInteger(state.zoom) ? String(state.zoom) : state.zoom.toFixed(2);
    if (!sliding) $('zoomslider').value = Math.log2(state.zoom);
    document.querySelectorAll('#zoomstops option').forEach(o => o.classList.toggle('on', +o.label === state.zoom));
    $('snap').hidden = Number.isInteger(state.zoom);
  }
  // the slider's value is log2 of the zoom, continuous from 1 to 32; the browser snaps the thumb to the datalist's
  // tick marks (the whole-number zooms of the buttons), which the mapping makes exact
  let sliding = false;
  const STOPS = [1, 2, 4, 8, 16, 32];   // the tick marks: the doublings, evenly spaced on the log2 track (3, 6, 12 by key or typed)
  function sliderZoom(v) { const near = STOPS.find(n => Math.abs(Math.log2(n) - v) < 0.01); return near !== undefined ? near : +Math.pow(2, v).toFixed(3); }
  function zoomText(z) { return Number.isInteger(z) ? String(z) : z >= 1 ? z.toFixed(2) : '1/' + (1 / z).toFixed(1); }

  // ---- documentation panel -----------------------------------------------------------------
  function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;'); }
  function rich(s) { return esc(s).replace(/`([^`]+)`/g, '<code>$1</code>'); }
  // The explanations at the foot of the side column: the two arms and exactly the values on display — each
  // once — behind a disclosure, and the description of the samples behind another. Open/closed states persist.
  // (The controls above the canvas already show the configuration; the panel does not repeat it.)
  const open = { explain: false, about: false };
  function docs() {
    if (!state.docs) return;
    const s = current(), A = arm(state.a), B = arm(state.b), dA = dimsOf(A), dB = dimsOf(B);
    let h = '';
    const shown = [];   // (dim, value) pairs on display, for the explanations
    for (const dim of dimensions()) { const a = dA[dim.key], b = dB[dim.key]; if (a) shown.push([dim, a]); if (b && b !== a) shown.push([dim, b]); }
    setDims().forEach(dim => { const v = (s.dims || {})[dim.key]; if (v) shown.push([dim, v]); });
    // explanations: the two arms, then every value on display, once
    let ex = '';
    [['A', A], ['B', B]].forEach(([slot, a]) => { if (a.description) ex += '<div class="dim"><b>' + slot + ' · ' + esc(name(a)) + '</b> <span>' + rich(a.description) + '</span></div>'; });
    const seen = new Set();
    for (const [dim, v] of shown) { const k = dim.key + '|' + v; if (seen.has(k)) continue; seen.add(k); const t = (dim.values || {})[v]; if (t) ex += '<div class="dim"><b>' + esc(dim.name) + ': ' + esc(dimLabel(dim, v)) + '</b> <span>' + rich(t) + '</span></div>'; }
    if (ex) h += '<details class="more"' + (open.explain ? ' open' : '') + ' data-k="explain"><summary>Explain the values on display</summary>' + ex + '</details>';
    if (s.description) h += '<details class="more"' + (open.about ? ' open' : '') + ' data-k="about"><summary>About these ' + (isText() ? 'samples' : 'screenshots') + '</summary><p>' + rich(s.description) + '</p></details>';
    $('docs').innerHTML = h;
    $('docs').querySelectorAll('details.more').forEach(d => d.addEventListener('toggle', () => { open[d.dataset.k] = d.open; }));
  }

  // ---- URL hash: the whole view state, shareable ------------------------------------------
  function writeHash() {
    const h = new URLSearchParams({ platform: entry.platform, view: entry.kind, set: current().name, a: arm(state.a).label, b: arm(state.b).label, mode: state.mode, side: state.side,
      gain: state.gain, rate: state.rate, zoom: +state.zoom.toFixed(4), ox: state.ox, oy: state.oy, docs: state.docs ? 1 : 0 });
    history.replaceState(null, '', '#' + h.toString());
  }
  function readHash() {
    if (!location.hash) return;
    const h = new URLSearchParams(location.hash.slice(1));
    const e = CATALOG.find(c => c.platform === h.get('platform') && c.kind === h.get('view')) || CATALOG.find(c => c.kind === h.get('view')) || CATALOG.find(c => c.platform === h.get('platform'));
    if (e) { entry = e; M = e.manifest; }
    const si = sets().findIndex(s => s.name === h.get('set')); if (si >= 0) state.set = si;
    const ai = current().arms.findIndex(a => a.label === h.get('a')); if (ai >= 0) state.a = ai;
    const bi = current().arms.findIndex(a => a.label === h.get('b')); if (bi >= 0) state.b = bi;
    const m = h.get('mode');
    if (MODES.includes(m)) state.mode = m;
    else if (m === 'a' || m === 'b') { state.mode = 'single'; state.side = m; }   // links from before 2026-10-27 (A only / B only / blend)
    else if (m === 'blend') state.mode = 'single';
    if (['a', 'b'].includes(h.get('side'))) state.side = h.get('side');
    if (GAINS.includes(+h.get('gain'))) state.gain = +h.get('gain');
    if ([1000, 500, 250].includes(+h.get('rate'))) state.rate = +h.get('rate');
    if (h.has('zoom') && +h.get('zoom') > 0) state.zoom = Math.min(ZMAX, Math.max(ZMIN, +h.get('zoom')));
    if (h.has('ox')) state.ox = +h.get('ox') | 0;
    if (h.has('oy')) state.oy = +h.get('oy') | 0;
    if (h.has('docs')) state.docs = h.get('docs') === '1';
    state.perf = h.get('perf') === '1';
    state.bench = h.get('bench') === '1';
    state.dump = h.get('dump') === '1';
    state.lcdcheck = h.get('lcdcheck') === '1';   // opens the screen check once the page is up (a look at the dialog without clicking)
  }

  // ---- images ------------------------------------------------------------------------------
  function load(src) {
    if (cache.has(src)) return cache.get(src);
    const entry = { src, img: new Image(), w: 0, h: 0, data: null, ready: false, failed: false };   // src: the composition cache keys on it
    entry.img.onload = () => {
      entry.w = entry.img.naturalWidth; entry.h = entry.img.naturalHeight; entry.ready = true;
      if (pixelsAllowed) {
        try {
          const oc = document.createElement('canvas'); oc.width = entry.w; oc.height = entry.h;
          const octx = oc.getContext('2d', { willReadFrequently: true });
          octx.drawImage(entry.img, 0, 0);
          entry.data = new Uint32Array(octx.getImageData(0, 0, entry.w, entry.h).data.buffer);
        } catch (e) {
          pixelsAllowed = false;
          $('warn').textContent = 'The browser refuses pixel access to images opened from disk: difference, color channels and the pixel readout need the page served over http (run serve.sh).';
        }
      }
      render();
    };
    entry.img.onerror = () => { entry.failed = true; render(); };
    entry.img.src = src;
    cache.set(src, entry);
    return entry;
  }

  // ---- rendering ---------------------------------------------------------------------------
  let raf = 0;
  function render() { if (!raf) raf = requestAnimationFrame(draw); }
  // the toolbar wraps to several lines: the stage and the panel start below it. The observer fires
  // when the panel's height changes — draw() used to measure it every frame, a forced layout per frame.
  function layout() {
    // the grid's minimum width is the tools row's: Show at its size, Zoom at its 240 px floor, Help's widest control
    const helpMin = Math.max(0, ...[...$('helpBox').children].filter(c => c.tagName !== 'LEGEND').map(c => c.offsetWidth));
    $('grid').style.minWidth = ($('modes').offsetWidth + 240 + helpMin + 2 * 8 + 2 * 11 + 2 * 10) + 'px';
    const h = $('panel').offsetHeight + 'px';
    stage.style.top = h; $('side').style.top = h;
  }
  new ResizeObserver(() => { layout(); render(); }).observe($('panel'));

  function draw() {
    raf = 0;
    const dpr = window.devicePixelRatio || 1;
    const cw = Math.max(1, Math.round(stage.clientWidth * dpr)), ch = Math.max(1, Math.round(stage.clientHeight * dpr));
    if (canvas.width !== cw || canvas.height !== ch) {
      canvas.width = cw; canvas.height = ch;
      canvas.style.width = (cw / dpr) + 'px'; canvas.style.height = (ch / dpr) + 'px';
    }
    const A = load(arm(state.a).src), B = load(arm(state.b).src);
    // which rendering is on the canvas: the lit side (single, color channels), the phase (alternate), both (difference);
    // Space shows the other one while held
    const showB = state.mode === 'flicker' ? flickerPhase !== flip : state.mode === 'diff' ? flip : (flip ? other(state.side) : state.side) === 'b';
    const z = state.zoom;
    ctx.imageSmoothingEnabled = false;
    ctx.fillStyle = '#202124'; ctx.fillRect(0, 0, cw, ch);
    if (!A.ready || !B.ready) { status(A.failed || B.failed ? 'image failed to load' : 'loading…'); return; }

    const mode = state.mode === 'diff' && !flip ? 'diff' : state.mode === 'stripes' ? 'stripes' : 'single';
    const E = showB ? B : A;
    // the A and B buttons light up for what is on the canvas right now: one side, or both for the difference
    $('sideA').classList.toggle('on', mode === 'diff' || !showB);
    $('sideB').classList.toggle('on', mode === 'diff' || showB);
    const t0 = performance.now();
    if (mode === 'single' || (mode === 'stripes' && z < 3)) {
      blit(E.img, 1, E.w, E.h, z);                 // the browser's nearest-neighbour scaling, GPU side
    } else if (pixelsAllowed && A.data && B.data) {
      const c = composedImage(mode, mode === 'stripes' ? E : A, mode === 'stripes' ? E : B);   // cached until the images or the gain change
      blit(c.canvas, c.scale, c.w, c.h, z);
    } else {
      blit(E.img, 1, E.w, E.h, z);
    }
    overlay(A, B, mode, showB);
    readout(A, B);
    if (state.perf) { const ms = performance.now() - t0; perfLog(ms); }
    if (state.bench) { state.bench = false; setTimeout(bench, 0); }
    if (state.dump) { state.dump = false; setTimeout(dumpCopy, 0); }
    if (state.lcdcheck) { state.lcdcheck = false; setTimeout(openLcdDialog, 0); }
  }
  // #bench=1: once the images are there, the input handlers' own cost per event kind on the console
  // (synthetic events; the draw they request is measured by #perf=1). A headless Chrome run with
  // --enable-logging=stderr prints the lines — the check that a viewer change kept the handlers cheap.
  function bench() {
    const ev = (type, init) => { const t = performance.now(); stage.dispatchEvent(new (type === 'wheel' ? WheelEvent : KeyboardEvent)(type, { bubbles: true, cancelable: true, ...init })); return performance.now() - t; };
    const key = (k, init) => { const t = performance.now(); document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: k, ...init })); return performance.now() - t; };
    const stats = (xs) => { const q = xs.slice().sort((p, r) => p - r); return `n=${q.length} median ${q[q.length >> 1].toFixed(2)} ms  p90 ${q[Math.floor(q.length * .9)].toFixed(2)} ms  max ${q[q.length - 1].toFixed(2)} ms`; };
    const rows = [];
    rows.push(['pan (wheel)', stats(Array.from({ length: 200 }, (_, i) => ev('wheel', { deltaX: 3, deltaY: (i & 1 ? 1 : -1) * 7, clientX: 300, clientY: 300 })))]);
    rows.push(['zoom (shift-wheel)', stats(Array.from({ length: 100 }, (_, i) => ev('wheel', { deltaY: (i & 1 ? 1 : -1) * 20, shiftKey: true, clientX: 300, clientY: 300 })))]);
    rows.push(['zoom (digit key)', stats(Array.from({ length: 40 }, (_, i) => key(String(1 + (i % 4)))))]);
    rows.push(['mode (keys)', stats('abfdcabfdc'.repeat(4).split('').map(k => key(k)))]);
    rows.push(['side buttons', stats(Array.from({ length: 40 }, (_, i) => { const t = performance.now(); $(i & 1 ? 'sideB' : 'sideA').click(); return performance.now() - t; }))]);
    rows.push(['swap arms (s)', stats(Array.from({ length: 20 }, () => key('s')))]);
    rows.push(['arrows (pan)', stats(Array.from({ length: 40 }, (_, i) => key(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'][i & 3])))]);
    rows.forEach(([k, v]) => console.log('BENCH ' + k.padEnd(20) + v));
    // the composition cache must tell two arms apart (2026-10-27: entries had no src, every stripes view showed the first one)
    const ready = current().arms.map(a => load(a.src)).filter(e => e.ready && e.data);
    if (ready.length >= 2) console.log('BENCH compose-key        ' + (composedImage('stripes', ready[0], ready[0]).key !== composedImage('stripes', ready[1], ready[1]).key ? 'ok' : 'STALE'));
    state.mode = 'single'; state.side = 'a'; syncControls(); render();
    // the screen check's dialog must open from its badge (visible under LCD) and close again
    if (!$('lcdcheck').hidden) { $('lcdcheck').click(); setTimeout(() => { console.log('BENCH lcd-dialog         ' + ($('lcddlg').classList.contains('on') ? 'opens' : 'FAILS') + ', key ' + $('lcdkey').textContent); $('lcdcancel').click(); console.log('BENCH-DONE'); }, 200); }
    else console.log('BENCH-DONE');
  }
  // #dump=1: the notes box and the explanation panel generated for every (set, A, B) of this page,
  // aggregated as unique text lines with their frequencies (a copy audit; DUMP lines on the console).
  function dumpCopy() {
    const strip = (html) => html.replace(/<[^>]+>/g, '\n').split('\n').map(x => x.trim()).filter(Boolean);
    const keep = { set: state.set, a: state.a, b: state.b };
    const notes = new Map(), diffs = new Map(), doc = new Map(); let combos = 0, chars = { notes: 0, diffs: 0, docs: 0 };
    const count = (m, k) => m.set(k, (m.get(k) || 0) + 1);
    sets().forEach((st, si) => {
      state.set = si;
      st.arms.forEach((_, ai) => st.arms.forEach((__, bi) => {
        if (ai === bi) return;
        state.a = ai; state.b = bi; combos++;
        activeQuirks().forEach(({ q, slots }) => count(notes, (q.kind || 'note') + ' | ' + slots.join('+') + ' | ' + q.text));
        const dh = differences(); chars.diffs += dh.length; strip(dh).forEach(l => count(diffs, l));
        const s = current(); let h = '';
        for (const dim of setDims()) { const v = (s.dims || {})[dim.key]; if (!v) continue; const vd = (dim.values || {})[v]; h += dim.name + ': ' + v + (vd ? ' — ' + vd : '') + '\n'; }
        if (s.description) h += s.description + '\n';
        state.docs = true; docs(); h += $('docs').innerText;
        chars.docs += h.length; h.split('\n').forEach(l => { if (l.trim()) count(doc, l.trim()); });
        chars.notes += activeQuirks().reduce((n, x) => n + x.q.text.length, 0);
      }));
    });
    Object.assign(state, keep);
    const top = (m, n) => [...m.entries()].sort((p, q) => q[1] - p[1]).slice(0, n).map(([k, v]) => ({ n: v, t: k }));
    const out = { page: entry.platform + '/' + entry.kind, combos, meanChars: { notes: Math.round(chars.notes / combos), diffs: Math.round(chars.diffs / combos), docs: Math.round(chars.docs / combos) },
      uniqueNotes: notes.size, uniqueDiffLines: diffs.size, uniqueDocLines: doc.size, notes: top(notes, 60), diffs: top(diffs, 80), docs: top(doc, 400) };
    const pre = document.createElement('pre'); pre.id = 'dumpout'; pre.style.display = 'none'; pre.textContent = JSON.stringify(out); document.body.appendChild(pre);   // read with --dump-dom
    console.log('DUMP-DONE');
  }
  // #perf=1 in the address bar: draw times in the status bar and the console (no profiler needed)
  let perfSamples = [];
  function perfLog(ms) {
    perfSamples.push(ms); if (perfSamples.length > 60) perfSamples.shift();
    const sorted = perfSamples.slice().sort((p, q) => p - q);
    const line = 'draw ' + ms.toFixed(1) + ' ms (median of last ' + sorted.length + ': ' + sorted[sorted.length >> 1].toFixed(1) + ' ms)';
    $('status').textContent = line + '  ·  ' + $('status').textContent.split('  ·  ').slice(-2).join('  ·  ');
    console.log(line);
  }

  // Composition at image resolution, once per (mode, A, B, blend, gain): blend, difference and the
  // color stripes are per image pixel (the stripes at three columns per pixel), so they are computed
  // into an offscreen canvas and blitted with the browser's nearest-neighbour scaling. The canvas used
  // to be filled pixel by pixel in JavaScript at device resolution on every frame (≈ 7 million
  // iterations per wheel event on a 2x display), which stuttered when scrolling and zooming (2026-10-26).
  let composed = { key: '', canvas: null, scale: 1 };
  function composedImage(mode, A, B) {
    const key = [mode, A.src, B.src, mode === 'diff' ? state.gain : ''].join('|');
    if (composed.key === key && composed.canvas) return composed;
    const w = A.w, h = A.h, scale = mode === 'stripes' ? 3 : 1;
    const c = document.createElement('canvas'); c.width = w * scale; c.height = h;
    const cx = c.getContext('2d');
    const img = cx.createImageData(w * scale, h);
    const out = new Uint32Array(img.data.buffer);
    const a = A.data, b = B.data, bw = B.w, bh = B.h;
    const g = state.gain;
    for (let y = 0; y < h; y++) {
      const rowA = y * w, rowB = y < bh ? y * bw : -1;
      let o = y * w * scale;
      for (let x = 0; x < w; x++) {
        const pa = a[rowA + x];
        const pb = rowB >= 0 && x < bw ? b[rowB + x] : 0xff000000;
        if (mode === 'diff') {
          const r = Math.min(255, Math.abs((pa & 255) - (pb & 255)) * g);
          const gg = Math.min(255, Math.abs(((pa >> 8) & 255) - ((pb >> 8) & 255)) * g);
          const bb = Math.min(255, Math.abs(((pa >> 16) & 255) - ((pb >> 16) & 255)) * g);
          out[o++] = 0xff000000 | (bb << 16) | (gg << 8) | r;
        } else {   // stripes: R, G, B of A as three vertical bars per image pixel
          out[o++] = 0xff000000 | (pa & 255);
          out[o++] = 0xff000000 | (pa & 0xff00);
          out[o++] = 0xff000000 | (pa & 0xff0000);
        }
      }
    }
    cx.putImageData(img, 0, 0);
    composed = { key, canvas: c, scale, w, h };
    return composed;
  }
  // Draws source `src` (an image or canvas holding `srcScale` columns per image pixel) so that image
  // pixel (ix, iy) covers device pixels [ix·z − ox, (ix+1)·z − ox): nearest sampling, no interpolation.
  function blit(src, srcScale, w, h, z) {
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(src, 0, 0, w * srcScale, h, -state.ox, -state.oy, w * z, h * z);
  }

  function overlay(A, B, mode, showB) {
    const s = current();
    const shown = (showB ? 'B: ' + name(arm(state.b)) : 'A: ' + name(arm(state.a)));
    let txt = s.name + '  ·  ' + (mode === 'diff' ? MODE_NAMES.diff + '  ·  A ' + name(arm(state.a)) + '  /  B ' + name(arm(state.b)) : (mode === 'stripes' ? 'color channels of ' : '') + shown) + '  ·  zoom ' + zoomText(state.zoom);
    if (A.w !== B.w || A.h !== B.h) txt += '  ·  the images differ in size: ' + A.w + '×' + A.h + ' vs ' + B.w + '×' + B.h;
    $('overlay').textContent = txt;
  }

  function hex(p) { return '#' + [(p & 255), (p >> 8) & 255, (p >> 16) & 255].map(v => v.toString(16).padStart(2, '0')).join(''); }
  function rgb(p) { return ((p & 255) + ',' + ((p >> 8) & 255) + ',' + ((p >> 16) & 255)).padEnd(11); }
  function readout(A, B) {
    let s = 'your screen: ' + (window.devicePixelRatio || 1) + ' physical px per CSS px  ·  view origin ' + state.ox + ',' + state.oy + ' px';
    if (mouse && pixelsAllowed && A.data && B.data) {
      const ix = Math.floor((mouse.x + state.ox) / state.zoom), iy = Math.floor((mouse.y + state.oy) / state.zoom);
      if (ix >= 0 && iy >= 0 && ix < A.w && iy < A.h) {
        const pa = A.data[iy * A.w + ix];
        const pb = ix < B.w && iy < B.h ? B.data[iy * B.w + ix] : null;
        s += '  ·  image px <b>' + ix + ',' + iy + '</b>';
        const rows = current().rows, rh = current().rowHeight;
        if (rows && rh && rows[Math.floor(iy / rh)]) s += '  <b>' + esc(rows[Math.floor(iy / rh)]) + '</b>';
        s += '  A ' + rgb(pa) + ' ' + hex(pa);
        if (pb !== null) {
          const d = Math.max(Math.abs((pa & 255) - (pb & 255)), Math.abs(((pa >> 8) & 255) - ((pb >> 8) & 255)), Math.abs(((pa >> 16) & 255) - ((pb >> 16) & 255)));
          s += '  B ' + rgb(pb) + ' ' + hex(pb) + '  largest channel difference <b>' + d + '</b>';
        }
      }
    }
    status(s);
  }
  function status(html) { $('status').innerHTML = html; }

  // ---- interaction -------------------------------------------------------------------------
  function setMode(m) { state.mode = m; flicker(m === 'flicker'); syncModes(); render(); }
  // The A and B buttons: with one side lit, the other button switches to it; with both lit (difference,
  // alternate), a button switches the view to the *other* side alone; the lit button of a single view does nothing.
  function clickSide(side) {
    if (state.mode === 'single' || state.mode === 'stripes') { if (state.side === side) return; state.side = side; }
    else { state.side = other(side); state.mode = 'single'; flicker(false); }
    syncModes(); render();
  }
  function showSide(side) {   // the A / B keys: that side alone (color channels keep their mode)
    state.side = side; if (state.mode !== 'stripes') { state.mode = 'single'; flicker(false); }
    syncModes(); render();
  }
  $('sideA').onclick = () => clickSide('a'); $('sideB').onclick = () => clickSide('b');
  function flicker(on) {
    if (flickerTimer) { clearInterval(flickerTimer); flickerTimer = null; }
    if (on) flickerTimer = setInterval(() => { flickerPhase = !flickerPhase; render(); }, state.rate);
    flickerPhase = false;
  }
  // zoom to nz keeping the image point under (atX, atY) (device px; viewport centre by default) in place
  function setZoom(nz, atX, atY) {
    nz = Math.max(ZMIN, Math.min(ZMAX, nz));
    const dx = atX == null ? canvas.width / 2 : atX, dy = atY == null ? canvas.height / 2 : atY;
    const u = (dx + state.ox) / state.zoom, v = (dy + state.oy) / state.zoom;
    state.zoom = nz;
    state.ox = Math.round(u * nz - dx); state.oy = Math.round(v * nz - dy);
    syncView(); render();
  }
  function presetStep(dir, atX, atY) {
    const z = state.zoom;
    const next = dir > 0 ? PRESETS.find(p => p > z + 1e-9) : [...PRESETS].reverse().find(p => p < z - 1e-9);
    if (next !== undefined) setZoom(next, atX, atY);
  }
  function fit() {
    const A = load(arm(state.a).src); if (!A.ready) return;
    const z = Math.min(canvas.width / A.w, canvas.height / A.h);
    state.zoom = Math.max(ZMIN, Math.min(ZMAX, z)); state.ox = 0; state.oy = 0; syncView(); render();
  }
  function selectSet(i) { state.set = (i + sets().length) % sets().length; state.a = Math.min(state.a, current().arms.length - 1); state.b = Math.min(state.b, current().arms.length - 1); syncControls(); render(); }
  function devicePos(e) { const r = canvas.getBoundingClientRect(); const dpr = window.devicePixelRatio || 1; return { x: Math.floor((e.clientX - r.left) * dpr), y: Math.floor((e.clientY - r.top) * dpr) }; }

  $('platform').onchange = (e) => selectCollection([...new Set(CATALOG.map(c => c.platform))][+e.target.value], entry.kind);
  $('kind').onchange = (e) => selectCollection(entry.platform, [...new Set(CATALOG.map(c => c.kind))][+e.target.value]);
  $('preset').onchange = (e) => applyPreset(+e.target.value);
  $('set').onchange = (e) => selectSet(+e.target.value);
  $('defaults').onclick = applyIdeDefaults;
  $('defaults').title = ideDefaults().why;
  $('lcdcheck').onclick = () => { openLcdDialog(); };
  $('lcddlg').querySelectorAll('[data-order]').forEach(b => b.onclick = () => answerLcd(b.dataset.order));   // the samples themselves and the "can't tell" button
  $('lcdcancel').onclick = () => $('lcddlg').classList.remove('on');
  $('lcddlg').onclick = (e) => e.stopPropagation();
  window.addEventListener('resize', updateLcdBadge);   // the window may have moved to another screen
  $('swap').onclick = () => { [state.a, state.b] = [state.b, state.a]; syncControls(); render(); };
  document.querySelectorAll('#modes input[type=radio]').forEach(r => r.onchange = () => setMode(r.value));
  $('rate').onchange = (e) => { state.rate = +e.target.value; if (state.mode === 'flicker') flicker(true); syncModes(); };
  document.querySelectorAll('#zoomstops option').forEach(o => o.onclick = () => setZoom(+o.label));   // a tick label is a shortcut to its zoom
  $('zoomslider').oninput = (e) => { sliding = true; setZoom(sliderZoom(+e.target.value)); sliding = false; };
  $('zoomslider').onchange = (e) => { e.target.blur(); showZoom(); };   // the keys go back to the viewer once the drag ends
  $('zoomv').onchange = (e) => { const z = +e.target.value; if (z > 0) setZoom(z); else showZoom(); e.target.blur(); };
  $('snap').onclick = () => setZoom(Math.max(1, Math.round(state.zoom)));
  $('gain').onchange = (e) => { state.gain = +e.target.value; if (state.mode !== 'diff') setMode('diff'); else { syncModes(); render(); } };
  $('docstoggle').onchange = (e) => { state.docs = e.target.checked; syncModes(); render(); };
  // the key list is a transient popover: any click outside it or Escape closes it
  $('helpbtn').onclick = (e) => { e.stopPropagation(); $('help').classList.toggle('on'); };
  $('help').onclick = (e) => e.stopPropagation();
  document.addEventListener('click', () => $('help').classList.remove('on'));

  let drag = null;
  stage.addEventListener('mousedown', (e) => { drag = { ...devicePos(e), ox: state.ox, oy: state.oy }; });
  window.addEventListener('mouseup', () => { if (drag) { drag = null; writeHash(); } });
  stage.addEventListener('mousemove', (e) => {
    mouse = devicePos(e);
    if (drag) { state.ox = drag.ox - (mouse.x - drag.x); state.oy = drag.oy - (mouse.y - drag.y); }
    render();
  });
  stage.addEventListener('mouseleave', () => { mouse = null; render(); });
  // trackpad: two-finger scroll pans the view, pinch (a wheel event with ctrlKey) zooms continuously
  // around the cursor; a mouse wheel with Shift held zooms as well. Never interpolated at any zoom.
  stage.addEventListener('wheel', (e) => {
    e.preventDefault();
    const perLine = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1;
    if (e.ctrlKey || e.metaKey || e.shiftKey) {
      const p = devicePos(e);
      setZoom(state.zoom * Math.exp(-e.deltaY * perLine * (e.ctrlKey ? 0.01 : 0.002)), p.x, p.y);
    } else {
      const dpr = window.devicePixelRatio || 1;
      state.ox += Math.round(e.deltaX * perLine * dpr); state.oy += Math.round(e.deltaY * perLine * dpr);
      syncView(); render();
    }
  }, { passive: false });
  stage.addEventListener('dblclick', (e) => { const p = devicePos(e); presetStep(1, p.x, p.y); });

  window.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'SELECT' || e.target.tagName === 'INPUT') return;
    const k = e.key;
    if (k === ' ') { if (!flip) { flip = true; render(); } e.preventDefault(); return; }
    if ('123468'.includes(k) && k.length === 1 && !e.metaKey && !e.ctrlKey) { setZoom(+k, mouse && mouse.x, mouse && mouse.y); e.preventDefault(); return; }   // zoom = the digit
    const step = e.shiftKey ? 10 : 64;
    switch (k) {
      case 'a': case 'A': showSide('a'); break;
      case 'b': case 'B': showSide('b'); break;
      case 's': case 'S': [state.a, state.b] = [state.b, state.a]; syncControls(); render(); break;
      case 'f': case 'F': setMode('flicker'); break;
      case 'd': case 'D': setMode('diff'); break;
      case 'c': case 'C': setMode('stripes'); break;
      case 'g': case 'G': state.gain = GAINS[(GAINS.indexOf(state.gain) + 1) % GAINS.length]; if (state.mode !== 'diff') state.mode = 'diff'; syncModes(); render(); break;
      case '+': case '=': presetStep(1, mouse && mouse.x, mouse && mouse.y); break;
      case '-': case '_': presetStep(-1, mouse && mouse.x, mouse && mouse.y); break;
      case 'n': case 'N': setZoom(Math.max(1, Math.round(state.zoom)), mouse && mouse.x, mouse && mouse.y); break;
      case '[': selectSet(state.set - 1); break;
      case ']': selectSet(state.set + 1); break;
      case 'ArrowLeft': state.ox -= step; syncView(); render(); break;
      case 'ArrowRight': state.ox += step; syncView(); render(); break;
      case 'ArrowUp': state.oy -= step; syncView(); render(); break;
      case 'ArrowDown': state.oy += step; syncView(); render(); break;
      case 'Home': state.ox = 0; state.oy = 0; syncView(); render(); break;
      case 'i': case 'I': state.docs = !state.docs; syncModes(); render(); break;
      case 'h': case 'H': case '?': $('help').classList.toggle('on'); break;
      case 'Escape': $('help').classList.remove('on'); break;
      default: return;
    }
    e.preventDefault();
  });
  window.addEventListener('keyup', (e) => { if (e.key === ' ') { flip = false; render(); } });
  window.addEventListener('resize', render);
  window.addEventListener('hashchange', () => { readHash(); syncControls(); render(); });

  // ---- start -------------------------------------------------------------------------------
  readHash();
  document.title = (M.pageTitle || M.title || 'Text rendering comparison');   // after the hash picked the page
  if (state.b === state.a && current().arms.length > 1) state.b = (state.a + 1) % current().arms.length;
  if (!new URLSearchParams(location.hash.slice(1)).has('set') && isText()) { const { dims } = ideDefaults(); const keys = setDims().map(d => d.key).filter(k => k in dims); let best = -1, score = -1; sets().forEach((st, i) => { let sc = 0; for (const k of keys) if ((st.dims || {})[k] === dims[k]) sc++; if (sc > score) { score = sc; best = i; } }); if (best >= 0) state.set = best; }
  syncControls();
  flicker(state.mode === 'flicker');
  current().arms.forEach(a => load(a.src));   // warm the whole set so switching is instant
  render();
})();
