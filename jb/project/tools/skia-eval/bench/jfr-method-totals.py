"""Method-timing totals of IDE runs per arm: jfr-method-totals.py <jfr tool> <root> <arm> [<arm> ...]

<root>/rep*/<arm>/*/recording.jfr are the recordings (run-scenes.sh with JFR_DUMP). For every
recording, `jfr view method-timing` gives invocations and average per timed method (cumulative over
the whole run); their product is the method's total time. Prints per arm and method the median
and IQR of the totals over the repetitions, and the invocations' median."""
import collections, glob, os, re, statistics, subprocess, sys

ROW = re.compile(r"^(\S+\(.*?\))\s+([\d,]+)\s+(\S+ ms|N/A)\s+(\S+) ms\s+(\S+ ms|N/A)\s*$")


def quartiles(values):
    v = sorted(values)
    if len(v) < 4:
        return v[0], v[-1]
    q = statistics.quantiles(v, n=4, method="inclusive")
    return q[0], q[2]


def totals(jfr, path):
    out = subprocess.run([jfr, "view", "--width", "300", "method-timing", path],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    result = {}
    for line in out.splitlines():
        m = ROW.match(line.strip())
        if m and m.group(4) != "N/A":
            calls = int(m.group(2).replace(",", ""))
            result[m.group(1)] = (calls, calls * float(m.group(4)) / 1000.0)   # seconds
    return result


def main():
    jfr, root, arms = sys.argv[1], sys.argv[2], sys.argv[3:]
    per = collections.defaultdict(lambda: collections.defaultdict(list))   # arm -> method -> [(calls, s)]
    for arm in arms:
        for path in sorted(glob.glob(os.path.join(root, "rep*", arm + "*", "*", "recording.jfr"))):
            for method, value in totals(jfr, path).items():
                per[arm][method].append(value)
    methods = sorted({m for a in per for m in per[a]})
    for method in methods:
        print(method)
        for arm in arms:
            values = per[arm].get(method, [])
            if not values:
                continue
            secs = [s for _, s in values]
            lo, hi = quartiles(secs)
            print(f"  {arm:<16} n={len(values)}  total {statistics.median(secs):8.2f} s [{lo:7.2f}-{hi:7.2f}]"
                  f"  calls {statistics.median([c for c, _ in values]):>9.0f}")


if __name__ == "__main__":
    main()
