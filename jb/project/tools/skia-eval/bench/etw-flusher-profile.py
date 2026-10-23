"""Where a Java2D flusher thread spends its CPU, from an ETW CPU-sampling trace dumped by tracerpt:

  etw-flusher-profile.py --dump <tracerpt -of CSV output> --thread "<thread name>"
                         [--map <module>.dll.map --module <module>.dll] [--undname <undname.exe>]
                         [--flushes N] [--top 40]

Samples (PerfInfo/SampleProf) are matched to their stacks (StackWalk/Stack, same thread, first
frame == sampled IP). Frames are attributed to modules through the process's image loads; frames
in --module are named through the linker map (public and static symbols; the nearest preceding
symbol, so inlined code lands in its caller). Reports, over the named threads of every process in
the trace: samples by module (exclusive, at the sampled frame), inclusive samples per function
(a function counts once per stack it appears in), and the top exclusive functions. With
--flushes, samples are also given per flush (one sample is one sampling interval, 1 ms with WPR's
CPU profile)."""
import argparse, bisect, collections, re, subprocess, sys

KERNEL = 0xFFFF800000000000


def parse_map(path):
    syms = []
    pat = re.compile(r"^\s*[0-9a-f]{4}:[0-9a-f]{8}\s+(\S+)\s+([0-9a-f]{16})\s")
    base = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if base is None and "Preferred load address is" in line:
                base = int(line.split()[-1], 16)
                continue
            m = pat.match(line)
            if m:
                syms.append((int(m.group(2), 16) - (base or 0), m.group(1)))
    syms.sort()
    return [a for a, _ in syms], [n for _, n in syms]


def demangle(names, undname):
    out = {}
    names = [n for n in names if n.startswith("?")]
    for i in range(0, len(names), 16):
        batch = names[i:i + 16]
        try:
            text = subprocess.run([undname] + batch, capture_output=True, text=True, errors="replace").stdout
        except OSError:
            break
        pairs = re.findall(r'Undecoration of :- "(.*?)"\s+is :- "(.*?)"', text, re.S)
        for mangled, plain in pairs:
            plain = re.sub(r"\b(public|private|protected): |__cdecl |__ptr64|\b(class|struct|enum|union) ", "", plain)
            plain = re.sub(r"\(.*", "()", plain)          # drop the parameter list
            plain = re.sub(r"^.*?\s(?=[A-Za-z_~<>:]+\(\)$)", "", plain)   # drop the return type
            out[mangled] = plain
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", required=True)
    ap.add_argument("--thread", required=True)
    ap.add_argument("--map")
    ap.add_argument("--module")
    ap.add_argument("--undname")
    ap.add_argument("--flushes", type=int)
    ap.add_argument("--top", type=int, default=40)
    a = ap.parse_args()

    threads = {}            # tid -> pid, for the named threads
    images = collections.defaultdict(list)   # pid -> [(base, end, name)]
    pending = {}            # tid -> ip of the last sample without a stack yet
    stacks = []             # (pid, tid, ip, [frames])
    last = {}               # tid -> (event timestamp, index in stacks) of the last matched stack: a sample
                            # taken in kernel mode brings its user-mode frames in a second event
    tid_col = re.compile(r'^\s*Thread,\s*SetName,')
    with open(a.dump, encoding="utf-8", errors="replace") as f:
        next(f)
        for line in f:
            if line.startswith("    PerfInfo, SampleProf"):
                p = line.split(",")
                pending[int(p[20])] = int(p[19], 16)
            elif line.startswith("   StackWalk,      Stack"):
                p = [x.strip() for x in line.split(",")]
                tid = int(p[21])
                if tid not in threads:
                    continue
                if pending.get(tid) is not None and p[22].lower() == hex(pending[tid]):
                    frames = [int(x, 16) for x in p[22:] if x]
                    stacks.append((int(p[20], 16), tid, pending[tid], frames))
                    last[tid] = (p[19], len(stacks) - 1)
                    pending[tid] = None
                elif tid in last and last[tid][0] == p[19]:
                    stacks[last[tid][1]][3].extend(int(x, 16) for x in p[22:] if x)
            elif line.startswith("       Image,"):
                p = [x.strip() for x in line.split(",")]
                if p[1] in ("DCStart", "Load", "DCEnd"):
                    name = p[-1].strip('"').rsplit("\\", 1)[-1].lower()
                    images[int(p[21])].append((int(p[19], 16), int(p[19], 16) + int(p[20], 16), name))
            elif tid_col.match(line):
                m = re.search(r'0x([0-9A-Fa-f]+),\s*0x([0-9A-Fa-f]+),\s*"(.*)"', line)
                if m and m.group(3) == a.thread:
                    threads[int(m.group(2), 16)] = int(m.group(1), 16)

    for pid in images:
        images[pid] = sorted(set(images[pid]))
    addrs, names = parse_map(a.map) if a.map else ([], [])
    module = (a.module or "").lower()

    def locate(pid, addr):
        if addr >= KERNEL:
            return "kernel", None
        for base, end, name in images.get(pid, ()):
            if base <= addr < end:
                if name == module and addrs:
                    i = bisect.bisect_right(addrs, addr - base) - 1
                    return name, names[i] if i >= 0 else None
                return name, None
        return "[jit or unknown]", None

    by_module = collections.Counter()
    exclusive = collections.Counter()
    inclusive = collections.Counter()
    kernel_from = collections.Counter()
    for pid, tid, ip, frames in stacks:
        user = [fr for fr in frames if fr < KERNEL]
        mod, sym = locate(pid, ip)
        by_module[mod] += 1
        if mod == "kernel" and user:
            m2, s2 = locate(pid, user[0])
            kernel_from[s2 or m2] += 1
        elif sym:
            exclusive[sym] += 1
        else:
            exclusive["[" + mod + "]"] += 1
        seen = set()
        for fr in user:
            m2, s2 = locate(pid, fr)
            key = s2 or "[" + m2 + "]"
            if key not in seen:
                seen.add(key)
                inclusive[key] += 1

    total = len(stacks)
    wanted = set(list(exclusive)[:]) | set(k for k, _ in inclusive.most_common(a.top * 2)) | set(kernel_from)
    plain = demangle(sorted(wanted), a.undname) if a.undname else {}

    def nm(s):
        return plain.get(s, s)[:120]

    per = (" (%.3f ms per flush at 1 ms per sample)" % (total / a.flushes)) if a.flushes else ""
    print("thread %r: %d sampled stacks in %d threads%s" % (a.thread, total, len(threads), per))
    print("\nexclusive, by module:")
    for mod, n in by_module.most_common():
        print("  %-28s %6d  %5.1f %%" % (mod, n, 100.0 * n / total))
    print("\nkernel samples, by the user function that entered the kernel:")
    for k, n in kernel_from.most_common(12):
        print("  %6d  %5.1f %%  %s" % (n, 100.0 * n / total, nm(k)))
    print("\ninclusive (a function counts once per stack):")
    for k, n in inclusive.most_common(a.top):
        print("  %6d  %5.1f %%  %s" % (n, 100.0 * n / total, nm(k)))
    print("\nexclusive, top functions (user mode):")
    for k, n in exclusive.most_common(a.top):
        print("  %6d  %5.1f %%  %s" % (n, 100.0 * n / total, nm(k)))


if __name__ == "__main__":
    main()
