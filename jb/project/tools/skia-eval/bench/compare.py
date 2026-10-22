import re, sys
def load(p):
    d = {}
    for line in open(p):
        m = re.match(r"^(\S+)\s*:\s*([\d.]+)\s*±\s*([\d.]+)\s*FPS", line)
        if m: d[m.group(1)] = (float(m.group(2)), float(m.group(3)))
    return d
a, b = load(sys.argv[1]), load(sys.argv[2])
print(f"{'Test':<26}{'Metal':>10}{'Skia':>10}{'Skia/Metal':>12}")
rows = []
for k in sorted(set(a) | set(b)):
    ma = a.get(k, (float('nan'),0))[0]; mb = b.get(k, (float('nan'),0))[0]
    rows.append((k, ma, mb, mb/ma if ma else float('nan')))
for k, ma, mb, r in rows:
    print(f"{k:<26}{ma:>10.1f}{mb:>10.1f}{r:>11.2f}x")
import statistics
rs = [r for _,_,_,r in rows if r == r]
print(f"\ngeomean Skia/Metal: {statistics.geometric_mean(rs):.2f}x over {len(rs)} tests; missing in skia: {sorted(set(a)-set(b))}")
