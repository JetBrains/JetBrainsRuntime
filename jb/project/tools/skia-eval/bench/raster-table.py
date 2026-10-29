"""Markdown tables from run-raster-bench.sh: raster-table.py <results.tsv>. One table per image type:
per workload the median [IQR] ms of each arm over the repetitions and the ratio of the raster arms'
medians to the loops' (below 1: the raster surface is faster)."""
import collections, csv, statistics, sys

ARMS = ["loops", "raster", "raster-skiatext"]


def quartiles(values):
    v = sorted(values)
    if len(v) < 4:
        return v[0], v[-1]
    q = statistics.quantiles(v, n=4, method="inclusive")
    return q[0], q[2]


def main():
    rows = collections.defaultdict(list)
    order = []
    with open(sys.argv[1], encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            rows[(r["bench"], r["configuration"], r["arm"])].append(float(r["ms"]))
            if r["configuration"] not in order:
                order.append(r["configuration"])
    for image in ["INT_RGB", "INT_ARGB", "INT_ARGB_PRE"]:
        print(f"\n{image}, ms per frame, median [IQR], n = {len(rows.get((image, order[0], 'loops'), []))}\n")
        print("| workload | loops | raster | raster + Skia text | raster / loops | + text / loops |")
        print("|---|---|---|---|---|---|")
        for w in order:
            cells, med = [], {}
            for arm in ARMS:
                v = rows.get((image, w, arm))
                if not v:
                    cells.append("-")
                    continue
                lo, hi = quartiles(v)
                med[arm] = statistics.median(v)
                cells.append(f"{med[arm]:.2f} [{lo:.2f}-{hi:.2f}]")
            ratio = lambda a: f"{med[a] / med['loops']:.2f}" if a in med and "loops" in med else "-"
            print(f"| {w} | {' | '.join(cells)} | {ratio('raster')} | {ratio('raster-skiatext')} |")


if __name__ == "__main__":
    main()
