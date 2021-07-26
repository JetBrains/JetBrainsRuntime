#!/usr/bin/env python3

import argparse
import math
import os.path
import sys
import subprocess


def fatal(msg):
    sys.stderr.write(f"[fatal] {msg}\n")
    sys.exit(1)


def verbose(options, *msg):
    if options.verbose:
        sys.stdout.write(f"[verbose] ")
        sys.stdout.write(*msg)
        sys.stdout.write('\n')


def first_line(str):
    return "" if not str else str.splitlines()[0]


class Options:
    def __init__(self):
        ap = argparse.ArgumentParser(description="Show bugfixes differences between JBR and OpenJDK git repos",
                                     epilog="Example: %(prog)s  --jdk ./jdk11u/ --jbr ./JetBrainsRuntime/ --path src/hotspot --limit 200")
        ap.add_argument('--jdk', dest='jdkpath', help='path to OpenJDK git repo', required=True)
        ap.add_argument('--jbr', dest='jbrpath', help='path to JBR git repo', required=True)
        ap.add_argument('--path', dest='path', help='limit to changes in this path (relative to git root)')
        ap.add_argument('--limit', dest='limit', help='limit to this many log entries in --jdk repo', type=int, default=-1)
        ap.add_argument('-o', dest="output_dir", help="save patches to this directory (created if necessary)")
        ap.add_argument('-v', dest='verbose', help="verbose output", default=False, action='store_true')
        args = ap.parse_args()

        if not os.path.isdir(args.jdkpath):
            fatal(f"{args.jdkpath} not a directory")

        if not os.path.isdir(args.jbrpath):
            fatal(f"{args.jbrpath} not a directory")

        if not git_is_available():
            fatal("can't run git commands; make sure git is in PATH")

        self.jdkpath = args.jdkpath
        self.jbrpath = args.jbrpath
        self.path = args.path
        self.limit = args.limit
        self.output_dir = args.output_dir
        self.verbose = args.verbose


class GitRepo:
    def __init__(self, rootpath):
        self.rootpath = rootpath

    def run_git_cmd(self, git_args):
        args = ["git", "-C", self.rootpath]
        args.extend(git_args)
        # print(f"Runnig git cmd '{' '.join(args)}'")
        p = subprocess.run(args, capture_output=True, text=True)
        if p.returncode != 0:
            fatal(f"git returned non-zero code in {self.rootpath} ({first_line(p.stderr)})")
        return p.stdout

    def save_git_cmd(self, fname, git_args):
        args = ["git", "-C", self.rootpath]
        args.extend(git_args)
        # print(f"Runnig git cmd '{' '.join(args)}'")
        with open(fname, "w") as stdout_file:
            p = subprocess.run(args, stdout=stdout_file)
            if p.returncode != 0:
                fatal(f"git returned non-zero code in {self.rootpath} ({first_line(p.stderr)})")

    def current_branch(self):
        branch_name = self.run_git_cmd(["branch", "--show-current"]).strip()
        return branch_name

    def log(self, path=None, limit=None):
        cmds = ["log", "--no-decorate"]
        if limit:
            cmds.extend(["-n", str(limit)])
        if path:
            cmds.append(path)
        full_log = self.run_git_cmd(cmds)
        return full_log


class Commit:
    def __init__(self, lines):
        self.sha = lines[0].split()[1]
        self.message = ""
        self.bugid = None

        # Commit message starts after one blank line
        read_message = False
        for l in lines:
            if read_message:
                self.message += l + "\n"
            if not read_message and l == "":
                read_message = True

        if self.message and self.message != "" and ":" in self.message:
            maybe_bugid = self.message.split(":")[0].strip()
            if 10 >= len(maybe_bugid) >= 4:
                self.bugid = maybe_bugid


class History:
    def __init__(self, log):
        log_itr = iter(log.splitlines())
        self.commits = []
        commit_lines = []
        for line in log_itr:
            if line.startswith("commit ") and len(commit_lines) > 0:
                commit = Commit(commit_lines)
                self.commits.append(commit)
                commit_lines = []
            commit_lines.append(line)

        if len(commit_lines) > 0:
            commit = Commit(commit_lines)
            self.commits.append(commit)

    def contains(self, str):
        return any(str in commit.message for commit in self.commits)

    def size(self):
        return len(self.commits)


def print_explanation(options, jdk, jbr):
    verbose(options, f"Reading history from '{jdk.rootpath}' on branch '{jdk.current_branch()}'")
    if options.path:
        verbose(options, f"\t(only under '{options.path}')")
    verbose(options, f"Searching for same fixes in '{jbr.rootpath}' on branch '{jbr.current_branch()}'")


def git_is_available():
    p = None
    try:
        p = subprocess.run(["git", "--help"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except:
        pass
    return p is not None and p.returncode == 0


def main():
    check_python_min_requirements()

    options = Options()

    jdk = GitRepo(options.jdkpath)
    jbr = GitRepo(options.jbrpath)

    print_explanation(options, jdk, jbr)

    commits_to_save = []
    try:
        jdk_log = jdk.log(options.path, options.limit)
        jdk_history = History(jdk_log)

        jbr_log = jbr.log(options.path)
        jbr_history = History(jbr_log)

        verbose(options, f"Read {jdk_history.size()} commits in JDK, {jbr_history.size()} in JBR")

        for c in jdk_history.commits:
            if c.bugid:
                verbose(options, f"Looking for bugfix for {c.bugid}")
                if not jbr_history.contains(c.bugid):
                    commits_to_save.append(c)
                    print(f"[note] Fix for {c.bugid} not found in JBR ({jbr.rootpath})")
                    print(f"    commit {c.sha}")
                    print(f"    {first_line(c.message).strip()}")
    except KeyboardInterrupt:
        fatal("Interrupted")

    if len(commits_to_save) > 0 and options.output_dir:
        print()
        if not os.path.exists(options.output_dir):
            verbose(options, f"Creating output directory {options.output_dir}")
            os.makedirs(options.output_dir)
        nzeroes = len(str(len(commits_to_save)))
        for i, c in enumerate(reversed(commits_to_save)):
            fname = os.path.join(options.output_dir, f"{str(i).zfill(nzeroes)}-{c.bugid}.patch")
            print(f"[note] {c.bugid} saved as {fname}")
            fname = os.path.abspath(fname)
            jdk.save_git_cmd(fname, ["format-patch", "-1", c.sha, "--stdout"])

        script_fname = os.path.join(options.output_dir, "apply.sh")
        with open(script_fname, "w") as script_file:
            print(apply_script_code.format(os.path.abspath(jbr.rootpath), os.path.abspath(options.output_dir)),
                  file=script_file)
            print(f"[note] Execute 'bash {script_fname}' to apply patches to {jbr.rootpath}")


def check_python_min_requirements():
    if sys.version_info < (3, 6):
        fatal("Minimum version 3.6 is required to run this script")


apply_script_code = """
#!/bin/bash

GITROOT={0}
PATCHROOT={1}

cd $PATCHROOT || exit 1
PATCHES=$(find $PATCHROOT -name '*.patch' | sort -n)

for P in $PATCHES; do
    git -C $GITROOT am $P
    if [ $? != 0 ]; then
        mv "$P" "$P.failed"
        echo "[ERROR] Patch $P did not apply cleanly. Try applying it manually."
        echo "[NOTE]  Execute this script to apply the remaining patches."
        exit 1
    else
        mv "$P" "$P.done"
    fi
done

echo "[NOTE] Done applying patches; check $PATCHROOT for .patch and .patch.failed to see if all have been applied."
"""

if __name__ == '__main__':
    main()
