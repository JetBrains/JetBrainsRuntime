"""Summarises run-rpt-arms.sh logs: rpt-summary.py <dir with <arm>-<rep>.log> [reference arm] [refresh Hz].

Per test and arm: the median over the repetitions of each run's median FPS, and the IQR over the
repetitions. Then each arm's ratio to the reference arm (default software) per test and the
geometric mean, and tests whose median sits within 3 % of the refresh rate or an integer fraction
of it (possible vsync caps)."""
import collections, glob, os, re, statistics, sys

LINE = re.compile(r"^(\S+)\s*:\s*([\d.]+)\s*±\s*([\d.]+)\s*FPS")


def quartiles(values):
    v = sorted(values)
    if len(v) < 4:
        return v[0], v[-1]
    q = statistics.quantiles(v, n=4, method="inclusive")
    return q[0], q[2]


def main():
    folder = sys.argv[1]
    reference = sys.argv[2] if len(sys.argv) > 2 else "software"
    refresh = float(sys.argv[3]) if len(sys.argv) > 3 else 144.0
    runs = collections.defaultdict(lambda: collections.defaultdict(list))   # arm -> test -> [fps]
    for path in sorted(glob.glob(os.path.join(folder, "*.log"))):
        arm = re.sub(r"-\d+\.log$", "", os.path.basename(path))
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = LINE.match(line)
                if m:
                    runs[arm][m.group(1)].append(float(m.group(2)))
    arms = sorted(runs, key=lambda a: (a != reference, a))
    tests = sorted({t for a in arms for t in runs[a]})
    med = {a: {t: statistics.median(runs[a][t]) for t in runs[a]} for a in arms}

    print(f"n per arm and test: " + ", ".join(f"{a}={min(len(v) for v in runs[a].values())}" for a in arms))
    print(f"{'test':<26}" + "".join(f"{a:>22}" for a in arms))
    for t in tests:
        cells = []
        for a in arms:
            if t in runs[a]:
                lo, hi = quartiles(runs[a][t])
                cells.append(f"{med[a][t]:>9.1f} [{lo:>6.1f}-{hi:>6.1f}]")
            else:
                cells.append(f"{'-':>22}")
        print(f"{t:<26}" + "".join(f"{c:>22}" for c in cells))

    if reference in med:
        print(f"\ngeometric mean of the per-test ratio to {reference}:")
        for a in arms:
            if a == reference:
                continue
            ratios = [med[a][t] / med[reference][t] for t in tests if t in med[a] and med[reference].get(t)]
            print(f"  {a:<20} {statistics.geometric_mean(ratios):.2f}x over {len(ratios)} tests")

    caps = [refresh / k for k in (1, 2, 3, 4)]
    near = [(a, t, med[a][t]) for a in arms for t in med[a] if any(abs(med[a][t] - c) <= 0.03 * c for c in caps)]
    print(f"\nwithin 3 % of {', '.join(f'{c:.0f}' for c in caps)} FPS (vsync?): {len(near)} of {sum(len(med[a]) for a in arms)}")
    for a, t, v in near[:40]:
        print(f"  {a:<20} {t:<26} {v:.1f}")


if __name__ == "__main__":
    main()
