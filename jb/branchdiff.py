#!/usr/bin/env python3

import argparse
import os.path
import sys
import subprocess

errors_count = 0


def fatal(msg):
    sys.stderr.write(f"[fatal] {msg}\n")
    sys.exit(1)


def error(msg):
    global errors_count
    errors_count += 1
    sys.stderr.write(f"[error] {msg}\n")


def verbose(options, *msg):
    if options.verbose:
        sys.stderr.write(f"[verbose] ")
        sys.stderr.write(*msg)
        sys.stderr.write('\n')


def first_line(str):
    return "" if not str else str.splitlines()[0]


class Options:
    def __init__(self):
        ap = argparse.ArgumentParser(description="Show commit differences between branches of JBR git repos",
                                     epilog="Example: %(prog)s  --from origin/jbr17 --to jbr17.b469 --path "
                                            "src/hotspot --limit 200")
        ap.add_argument('--jbr', dest='jbrpath', help='path to JBR git root', required=True)
        ap.add_argument('--from', dest='frombranch', help='branch to take commits from', required=True)
        ap.add_argument('--to', dest='tobranch', help='branch to apply new commits to', required=True)
        ap.add_argument('--path', dest='path', help='limit to changes in this path (relative to git root)')
        ap.add_argument('--limit', dest='limit', help='limit to this many log entries in --jdk repo', type=int,
                        default=-1)
        ap.add_argument('--html', dest="ishtml", help="print out HTML rather than plain text", action='store_true')
        ap.add_argument('-o', dest="output", help="print the list of missing commits to this file"
                                                  " to be used as exclude list later")
        ap.add_argument('--exclude', dest='exclude', help='exclude commits listed in the given file '
                                                          '(can use edited -o output file as input here)')
        ap.add_argument('-v', dest='verbose', help="verbose output", default=False, action='store_true')
        args = ap.parse_args()

        if not os.path.isdir(args.jbrpath):
            fatal(f"{args.jbrpath} not a directory")

        if not git_is_available():
            fatal("can't run git commands; make sure git is in PATH")

        self.frombranch = args.frombranch
        self.tobranch = args.tobranch
        self.jbrpath = args.jbrpath
        self.path = args.path
        self.limit = args.limit
        self.exclude = args.exclude
        self.output = args.output
        self.ishtml = args.ishtml
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

    def log(self, branch, path=None, limit=None):
        cmds = ["log", "--no-decorate", branch]
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
        self.fullmessage = ""
        self.bugid = ""

        # Commit message starts after one blank line
        read_message = False
        for l in lines:
            if read_message:
                self.fullmessage += l.strip() + "\n"
            if not read_message and l == "":
                read_message = True

        if len(self.fullmessage) > 0:
            self.message = first_line(self.fullmessage).strip()
            t = self.message.split(' ')
            if len(t) > 1:
                bugid = t[0]
                if bugid.startswith("fixup"):
                    bugid = t[1]
                bugid = bugid.strip(":")
                if bugid.startswith("JBR-") or bugid.isnumeric():
                    self.bugid = bugid


class History:
    def __init__(self, log):
        log_itr = iter(log.splitlines())
        self.commits = []
        self.unique_fullmessages = set()
        self.duplicates = set()
        commit_lines = []
        for line in log_itr:
            if line.startswith("commit ") and len(commit_lines) > 0:
                commit = Commit(commit_lines)
                self.add_commit(commit)
                commit_lines = []
            commit_lines.append(line)

        if len(commit_lines) > 0:
            commit = Commit(commit_lines)
            self.add_commit(commit)

    def add_commit(self, commit):
        self.commits.append(commit)
        if commit.fullmessage in self.unique_fullmessages:
            self.duplicates.add(commit.fullmessage)
        else:
            self.unique_fullmessages.add(commit.fullmessage)

    def appears_more_than_once(self, commit):
        return commit.fullmessage in self.duplicates

    def contains(self, commit):
        return commit.fullmessage in self.unique_fullmessages

    def size(self):
        return len(self.commits)


def print_explanation(options, jbr):
    verbose(options, f"Reading history from '{jbr.rootpath}'")
    if options.path:
        verbose(options, f"\t(only under '{options.path}')")
    if options.limit > 0:
        verbose(options, f"\t(up to '{options.limit}' commits)")
    verbose(options, f"Searching for missing fixes in '{options.tobranch}' compared with '{options.frombranch}'")


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
    jbr = GitRepo(options.jbrpath)
    print_explanation(options, jbr)

    commits_to_save = []
    try:
        log_from = jbr.log(options.frombranch, options.path, options.limit)
        log_to = jbr.log(options.tobranch, options.path, options.limit)
        history_from = History(log_from)
        history_to = History(log_to)

        verbose(options,
                f"Read {history_from.size()} commits from '{options.frombranch}', {history_to.size()} from {options.tobranch}")

        exclude_list = []
        if options.exclude:
            with open(options.exclude, "r") as exclude_file:
                l = exclude_file.read().split('\n')
                exclude_list = list(filter(lambda line: not line.startswith("#"), l))

        warned = set()
        for c in history_from.commits:
            if c.message:
                verbose(options, f"Looking for commit '{c.message}'")
                if c.message in exclude_list:
                    verbose(options, "...nope, in exclude list")
                    continue

                if not history_to.contains(c):
                    commits_to_save.append(c)
                else:
                    if history_from.appears_more_than_once(c) and c.fullmessage not in warned:
                        # Not sure which of those seemingly identical commits are present in the target branch
                        error(f"Commit '{c.message}' appears more than once in branch '{options.frombranch}'. ")
                        warned.add(c.fullmessage)
    except KeyboardInterrupt:
        fatal("Interrupted")

    print_out_commits(options, commits_to_save)
    save_commits_to_file(commits_to_save, options)
    if errors_count > 0:
        error(f"{errors_count} error(s) generated to stderr. MANUAL CHECK OF COMMITS IS REQUIRED.")


def save_commits_to_file(commits_to_save, options):
    if len(commits_to_save) > 0 and options.output:
        print()
        with open(options.output, "w") as out:
            for i, c in enumerate(reversed(commits_to_save)):
                print(f"# {c.sha}", file=out)
                print(c.message, file=out)


def print_out_commits(options, commits_to_save):
    if options.ishtml:
        print("<html><body>")
        print(f"<p><b>Commits on <code>{options.frombranch}</code>"
              f" missing from <code>{options.tobranch}</code></b></p></h1>")
    if len(commits_to_save) > 0:
        for c in sorted(commits_to_save, key=lambda commit: commit.bugid):
            if options.ishtml:
                msg = c.message
                bugurl = ""
                if c.bugid:
                    if c.bugid.isnumeric():
                        bugurl = f"https://bugs.openjdk.org/browse/JDK-{c.bugid}"
                    elif c.bugid.startswith("JBR-"):
                        bugurl = f"https://youtrack.jetbrains.com/issue/{c.bugid}"

                if len(bugurl) > 0:
                    msg = msg.replace(c.bugid, f"<a href='{bugurl}'>{c.bugid}</a>")

                sha = f"<a href='https://jetbrains.team/p/jbre/repositories/jbr/commits?commits={c.sha}'>" \
                      f"{c.sha[0:8]}</a>"
                print(f"<li>{msg} ({sha})</li>")
            else:
                print(f"{c.message} ({c.sha[0:8]})")
    if options.ishtml:
        print("</body></html>")


def check_python_min_requirements():
    if sys.version_info < (3, 6):
        fatal("Minimum version 3.6 is required to run this script")


if __name__ == '__main__':
    main()
