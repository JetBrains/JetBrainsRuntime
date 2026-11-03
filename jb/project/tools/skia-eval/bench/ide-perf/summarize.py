"""Summarises IDE performance launches (bench/ide-perf): summarize.py <jfr tool> <series dir> [arm ...]

<series dir>/rep<N>-<arm>-<launch>/ are the launches (run-series.sh). Per launch kind, arm and metric
the median [IQR] over the repetitions of:
  - JFR method timing totals (seconds, cumulative over the launch) and invocations;
  - typing (typing.ijperf): the IDE's typing spans, key press to editor painted — the mean of the
    spans' mean latency, the largest p90 and the largest max (ms);
  - UI scenes (ui.ijperf + IdePerfScenes): each scene's wall time (ms; paced, so a check, not a metric);
  - the Skia backend's statistics (J2D_SKIA_STATS, stdout.txt): each phase's total time (ms) and count,
    and the counters, summed over the launch."""
import collections, glob, json, os, re, statistics, subprocess, sys

ROW = re.compile(r"^(\S+\(.*?\))\s+([\d,]+)\s+(\S+ ms|N/A)\s+(\S+) ms\s+(\S+ ms|N/A)\s*$")
STATS = re.compile(r"SKStats [\d.]+ s: (\d+) entries, (\d+) ops, queue ([\d.]+) ms \|(.*?)\| (.*)$")
SPAN = re.compile(r" (\S+) ([\d.]+) ms \((\d+)\)")
COUNTER = re.compile(r"([a-z][a-z ]*?) (\d+)")


def quartiles(values):
    v = sorted(values)
    if len(v) < 4:
        return v[0], v[-1]
    q = statistics.quantiles(v, n=4, method="inclusive")
    return q[0], q[2]


def jfr_totals(jfr, path):
    out = subprocess.run([jfr, "view", "--width", "300", "method-timing", path],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    result = {}
    for line in out.splitlines():
        m = ROW.match(line.strip())
        if m and m.group(4) != "N/A":
            calls = int(m.group(2).replace(",", ""))
            name = re.sub(r"^(?:\w+\.)+(\w+\.\w+\()", r"\1", m.group(1))   # Class.method(args)
            result["jfr " + name + " s"] = calls * float(m.group(4).replace(",", "")) / 1000.0
            result["jfr " + name + " calls"] = calls
    return result


def typing(path):
    try:
        data = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    means, p90s, maxes = [], [], []
    for trace in data.get("data", []):
        for span in trace.get("spans", []):
            if span.get("operationName") != "typing":
                continue
            tags = {t["key"]: t["value"] for t in span.get("tags", [])}
            if "latency#mean_value" in tags:
                means.append(float(tags["latency#mean_value"]))
                p90s.append(float(tags["latency#p90"]))
                maxes.append(float(tags["latency#max"]))
    if not means:
        return {}
    return {"typing latency mean ms": statistics.mean(means), "typing latency p90 ms": max(p90s),
            "typing latency max ms": max(maxes), "typing spans": len(means)}


def skstats(path):
    result = collections.Counter()
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    except OSError:
        return {}
    for line in lines:
        m = STATS.search(line)
        if not m:
            continue
        result["sk entries"] += int(m.group(1))
        result["sk ops"] += int(m.group(2))
        result["sk queue ms"] += float(m.group(3))
        for name, ms, count in SPAN.findall(" " + m.group(4)):
            result["sk " + name + " ms"] += float(ms)
            result["sk " + name + " n"] += int(count)
        for name, value in COUNTER.findall(m.group(5)):
            result["sk " + name.strip()] += int(value)
    return dict(result)


def scenes(path):
    result = {}
    try:
        for line in open(path, encoding="utf-8"):
            parts = line.strip().split("\t")
            if len(parts) == 2:
                result["scene " + parts[0] + " ms"] = float(parts[1])
    except OSError:
        pass
    return result


def main():
    jfr, root, arms = sys.argv[1], sys.argv[2], sys.argv[3:]
    per = collections.defaultdict(lambda: collections.defaultdict(lambda: collections.defaultdict(list)))
    seen_arms = []
    for d in sorted(glob.glob(os.path.join(root, "rep*-*-*"))):
        _, arm, launch = os.path.basename(d).split("-", 2)
        if arms and arm not in arms:
            continue
        if arm not in seen_arms:
            seen_arms.append(arm)
        values = {}
        values.update(jfr_totals(jfr, os.path.join(d, "perf.jfr")) if os.path.exists(os.path.join(d, "perf.jfr")) else {})
        values.update(typing(os.path.join(d, "opentelemetry.json")))
        values.update(skstats(os.path.join(d, "stdout.txt")))
        values.update(scenes(os.path.join(d, "scenes.tsv")))
        for k, v in values.items():
            per[launch][k][arm].append(v)
    order = arms or seen_arms
    for launch in sorted(per):
        print(f"\n== {launch}: median [IQR] over repetitions (n per arm)")
        print(f"{'metric':<58}" + "".join(f"{a:>26}" for a in order))
        for metric in sorted(per[launch]):
            cells = []
            for arm in order:
                v = per[launch][metric].get(arm, [])
                if not v:
                    cells.append(f"{'-':>26}")
                    continue
                lo, hi = quartiles(v)
                cells.append(f"{statistics.median(v):>11.2f} [{lo:.1f}-{hi:.1f}] {len(v):>2}".rjust(26))
            print(f"{metric:<58}" + "".join(cells))


if __name__ == "__main__":
    main()
