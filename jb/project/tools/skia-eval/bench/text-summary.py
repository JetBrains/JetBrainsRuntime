"""Summarises run-text-benches.sh: text-summary.py <results.tsv>. Median and IQR (ms) per bench,
configuration, metric and arm, over the repetitions; NA values are counted, not averaged."""
import collections, csv, statistics, sys


def quartiles(values):
    v = sorted(values)
    if len(v) < 4:
        return v[0], v[-1]
    q = statistics.quantiles(v, n=4, method="inclusive")
    return q[0], q[2]


def main():
    rows = collections.defaultdict(list)
    missing = collections.Counter()
    with open(sys.argv[1], encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            key = (r["bench"], r["configuration"], r["metric"], r["arm"])
            if r["ms"] == "NA":
                missing[key] += 1
            else:
                rows[key].append(float(r["ms"]))
    print(f"{'bench':<16}{'configuration':<14}{'metric':<8}{'arm':<18}{'n':>3}{'median ms':>11}{'IQR':>18}{'NA':>4}")
    for key in sorted(set(rows) | set(missing)):
        v = rows.get(key, [])
        if v:
            lo, hi = quartiles(v)
            print(f"{key[0]:<16}{key[1]:<14}{key[2]:<8}{key[3]:<18}{len(v):>3}{statistics.median(v):>11.2f}   [{lo:>6.2f}-{hi:>6.2f}]{missing[key]:>4}")
        else:
            print(f"{key[0]:<16}{key[1]:<14}{key[2]:<8}{key[3]:<18}{0:>3}{'-':>11}{'':>18}{missing[key]:>4}")


if __name__ == "__main__":
    main()
