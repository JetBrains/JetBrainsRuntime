# Medians and ranges over the gpumem-<arm>-<rep>.txt of run-gpumem-mac.sh (run in that directory): the footprint, vmmap's regions
# (dirty + compressed), and on Skia what Graphite's caches, our atlases and the Metal device hold.
import re, glob, statistics as st, sys
def mb(v):
    m = re.match(r"([\d.]+)([KMG])", v); x = float(m.group(1)); return x / 1024 if m.group(2) == "K" else x * 1024 if m.group(2) == "G" else x
rows = {}
for f in sorted(glob.glob("gpumem-*-[0-9].txt")):
    arm = f.split("-")[1]; t = open(f).read(); d = {}
    d["footprint"] = mb(re.search(r"Physical footprint:\s+(\S+)", t).group(1))
    exitpart = t[t.rfind("java heap committed"):]
    d["heap committed"] = float(re.search(r"java heap committed (\d+)", exitpart).group(1))
    def region(name):   # the DIRTY column (third size) of a vmmap summary row
        tot = 0
        for line in exitpart.splitlines():
            if re.match(r"^" + re.escape(name) + r"(\s{2,}|\s\(empty\))", line):
                c = re.findall(r"\d[\d.]*[KMG]", line); tot += mb(c[2]) + mb(c[3])
        return tot
    d["VM_ALLOCATE (heap)"] = region("VM_ALLOCATE")
    d["graphics (owned unmapped)"] = region("owned unmapped (graphics)")
    d["IOAccelerator (graphics)"] = region("IOAccelerator (graphics)")
    d["malloc (all zones, incl. empty)"] = sum(region(z) for z in ["MALLOC_LARGE", "MALLOC_SMALL", "MALLOC_TINY", "MALLOC_NANO", "MALLOC_MEDIUM"])
    d["malloc empty"] = sum(mb(re.findall(r"\d[\d.]*[KMG]", l)[2]) + mb(re.findall(r"\d[\d.]*[KMG]", l)[3]) for l in exitpart.splitlines() if re.match(r"^MALLOC_\w+ \(empty\)", l))
    d["native (footprint - VM_ALLOCATE)"] = d["footprint"] - d["VM_ALLOCATE (heap)"]
    last = t[t.rfind("\ncontext 0x"):]
    m = re.search(r"Metal device allocated for the process: ([\d.]+)", last)
    if m:
        d["Metal device allocated"] = float(m.group(1))
        r = re.search(r"recorder ([\d.]+) of [\d.]+ MB budgeted .*?context ([\d.]+) of", last)
        d["Graphite budgeted (recorder + context)"] = float(r.group(1)) + float(r.group(2))
        d["our render targets (unbudgeted)"] = sum(float(x) for x in re.findall(r"SkSurfaceRenderTarget\] unbudgeted\s+\d+\s+([\d.]+) MB", last))
        p = re.search(r"pipelines built (\d+); Skia strike cache ([\d.]+) MB in (\d+) strikes.*?resource cache ([\d.]+)", t[t.rfind("process:"):])
        d["Graphite pipelines"] = float(p.group(1)); d["Skia strike cache"] = float(p.group(2)); d["Skia resource cache"] = float(p.group(4))
    rows.setdefault(arm, []).append(d)
keys = list(rows["skia"][0].keys())
print(f"{'':42s} {'Skia median [range]':>24s} {'Metal median [range]':>24s} {'Skia - Metal':>12s}")
for k in keys:
    def cell(arm):
        v = [r[k] for r in rows.get(arm, []) if k in r]
        return (st.median(v), min(v), max(v)) if v else None
    s, m = cell("skia"), cell("metal")
    fmt = lambda c: f"{c[0]:8.1f} [{c[1]:6.1f}-{c[2]:6.1f}]" if c else " " * 24
    diff = f"{s[0] - m[0]:+10.1f}" if s and m else ""
    print(f"{k:42s} {fmt(s):>24s} {fmt(m):>24s} {diff:>12s}")
