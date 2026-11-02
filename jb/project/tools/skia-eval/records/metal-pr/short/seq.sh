#!/bin/bash
python3 - "$1" <<'PY'
import sys
todo = sys.argv[1]
picks = [l for l in open(todo).read().splitlines() if l.startswith('pick ')]
assert len(picks) == 3, picks
out = []
for i, l in enumerate(picks, 1):
    out.append(l)
    out.append(f"exec git commit --amend -q -S -F /tmp/metal-pr/short/{i}.txt")
open(todo, 'w').write("\n".join(out) + "\n")
PY
