#!/bin/bash
# Regenerates the Skia backend's GPU list (sun.java2d.skia.SKGPUListData) from Chromium's GPU lists and
# Dawn's adapter rules at the revisions that match the pinned Skia. Run it with every Skia bump, after
# SKIA_TAG in skia_prebuilt.conf changed (usage: see usage() below, or run with -h).
#
# From the pin: the Skia milestone (SKIA_TAG m<N>-<commit>) -> Chromium's release branch for it
# (chromiumdash) -> its head; the JetBrains/skia commit -> its DEPS -> the Dawn revision it builds.
# Fetched at those revisions: gpu/config/software_rendering_list.json, gpu/config/gpu_driver_bug_list.json,
# and the upstream sources named by the fingerprints in jb/project/tools/common/skia-gpu-lists/policy.conf.
# The converter stops on anything the policy does not decide and prints the entries added, removed and
# changed; the revisions are recorded in skia_prebuilt.conf. Review the summary, commit the generated
# file with the bump, update src/java.desktop/share/legal/chromium-gpu-lists.md where it names other
# revisions (the script says so), run the jtreg tests (test/jdk/jb/java/awt/Graphics2D/SkiaGPUList*Test.java).

set -euo pipefail

usage() {
  cat >&2 <<'EOF'
usage: update_gpu_lists.sh [-j <java>] [-w <work dir>] [-C <chromium revision>] [-D <dawn revision>]

  -j  a java of JDK 17 or later (default: java on PATH)
  -w  where the fetched upstream files go (default: build/skia-gpu-lists, git-ignored)
  -C/-D  override the revisions derived from the pin (for a hand-built Skia)
EOF
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../../.." && pwd)"
pin_file="${repo_root}/jb/project/tools/common/skia_prebuilt.conf"
policy_file="${repo_root}/jb/project/tools/common/skia-gpu-lists/policy.conf"
converter="${repo_root}/jb/project/tools/common/skia-gpu-lists/GPUListConverter.java"
generated_file="${repo_root}/src/java.desktop/share/classes/sun/java2d/skia/SKGPUListData.java"
legal_notice="${repo_root}/src/java.desktop/share/legal/chromium-gpu-lists.md"

# curl: a transient failure of a Google or GitHub endpoint should not abort a run that takes minutes
curl_retries=3
# the fetched files go under a directory named by the two revisions, abbreviated as git does for display
short_revision_length=12

java=java
work_dir="${repo_root}/build/skia-gpu-lists"
chromium_revision=""
dawn_revision=""
while getopts "hj:w:C:D:" option; do
  case "${option}" in
    j) java="${OPTARG}" ;;
    w) work_dir="${OPTARG}" ;;
    C) chromium_revision="${OPTARG}" ;;
    D) dawn_revision="${OPTARG}" ;;
    h) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done

die() { echo "update_gpu_lists.sh: $*" >&2; exit 1; }
fetch() { curl -sSfL --retry "${curl_retries}" "$1" || die "cannot fetch $1"; }
# a file from a gitiles repository at a revision (gitiles serves raw files base64-encoded); GNU and
# recent BSD base64 decode with -d, macOS before 13 only with -D
if printf "" | base64 -d >/dev/null 2>&1; then base64_decode="base64 -d"; else base64_decode="base64 -D"; fi
gitiles() { fetch "$1/+/$2/$3?format=TEXT" | $base64_decode; }
# the value of a key in the pin file (empty when absent; a CRLF checkout must not leak a \r into it)
pin_value() { grep "^${1}=" "${pin_file}" | cut -d= -f2- | tr -d '\r' || true; }

skia_tag=$(pin_value SKIA_TAG)
[[ "${skia_tag}" =~ ^m([0-9]+)-([0-9a-f]+)$ ]] || die "SKIA_TAG '${skia_tag}' is not m<milestone>-<commit>"
milestone="${BASH_REMATCH[1]}"
skia_commit="${BASH_REMATCH[2]}"

if [[ -z "${chromium_revision}" ]]; then
  chromium_branch=$(fetch "https://chromiumdash.appspot.com/fetch_milestones?mstone=${milestone}" \
                    | grep -o '"chromium_branch": *"[0-9]*"' | head -1 | grep -o '[0-9][0-9]*')
  [[ -n "${chromium_branch}" ]] || die "no Chromium branch for milestone ${milestone}"
  chromium_revision=$(fetch "https://chromium.googlesource.com/chromium/src/+/refs/branch-heads/${chromium_branch}?format=JSON" \
                      | grep -o '"commit": *"[0-9a-f]*"' | head -1 | grep -o '[0-9a-f]\{40\}')
  [[ -n "${chromium_revision}" ]] || die "no head for Chromium branch ${chromium_branch}"
  echo "Skia ${skia_tag}: milestone ${milestone}, Chromium branch ${chromium_branch} at ${chromium_revision}"
fi
if [[ -z "${dawn_revision}" ]]; then
  # the tag abbreviates the JetBrains/skia commit; GitHub resolves it to the full SHA the raw URLs need
  skia_sha=$(fetch "https://api.github.com/repos/JetBrains/skia/commits/${skia_commit}" \
             | grep -o '"sha": *"[0-9a-f]\{40\}"' | head -1 | grep -o '[0-9a-f]\{40\}')
  [[ -n "${skia_sha}" ]] || die "no JetBrains/skia commit ${skia_commit}"
  dawn_revision=$(fetch "https://raw.githubusercontent.com/JetBrains/skia/${skia_sha}/DEPS" \
                  | grep '"third_party/externals/dawn"' | grep -o '@[0-9a-f]\{40\}' | tr -d @)
  [[ -n "${dawn_revision}" ]] || die "no Dawn revision in the DEPS of JetBrains/skia ${skia_sha}"
  echo "JetBrains/skia ${skia_sha}: Dawn ${dawn_revision}"
fi

sources_dir="${work_dir}/${chromium_revision:0:${short_revision_length}}-${dawn_revision:0:${short_revision_length}}"
mkdir -p "${sources_dir}/chromium/gpu/config"
for list_name in software_rendering_list gpu_driver_bug_list; do
  gitiles https://chromium.googlesource.com/chromium/src "${chromium_revision}" "gpu/config/${list_name}.json" \
      > "${sources_dir}/chromium/gpu/config/${list_name}.json"
done
# the sources of the hand ports: "fingerprint <repo>:<path>:<symbol> <sha256>"; a die inside the loop ends
# the subshell the pipeline runs it in, and pipefail then ends the script
grep '^fingerprint ' "${policy_file}" | awk '{print $2}' | cut -d: -f1,2 | sort -u | while IFS=: read -r repo_name path; do
  case "${repo_name}" in
    chromium) repo_url=https://chromium.googlesource.com/chromium/src; revision="${chromium_revision}" ;;
    dawn)     repo_url=https://dawn.googlesource.com/dawn;             revision="${dawn_revision}" ;;
    *) die "policy.conf: unknown repository ${repo_name}" ;;
  esac
  mkdir -p "$(dirname "${sources_dir}/${repo_name}/${path}")"
  gitiles "${repo_url}" "${revision}" "${path}" > "${sources_dir}/${repo_name}/${path}"
done

"${java}" "${converter}" --policy "${policy_file}" --sources "${sources_dir}" --out "${generated_file}" \
    --skia-tag "${skia_tag}" --chromium-revision "${chromium_revision}" --dawn-revision "${dawn_revision}"

# record the revisions next to the pin
rewritten_pin_file="${pin_file}.tmp"
trap 'rm -f "${rewritten_pin_file}"' EXIT
for assignment in "SKIA_GPU_LISTS_CHROMIUM=${chromium_revision}" "SKIA_GPU_LISTS_DAWN=${dawn_revision}"; do
  key="${assignment%%=*}"
  if grep -q "^${key}=" "${pin_file}"; then
    # not sed -i: GNU and BSD sed (macOS) disagree on it
    awk -v key="${key}" -v assignment="${assignment}" \
        'index($0, key "=") == 1 { print assignment; next } { print }' "${pin_file}" > "${rewritten_pin_file}"
    mv "${rewritten_pin_file}" "${pin_file}"
  else
    echo "${assignment}" >> "${pin_file}"
  fi
done
echo "recorded the revisions in ${pin_file}"

# the legal notice of the generated list names both revisions (and carries the licenses at them): not
# generated, so a bump that changed a revision must update it by hand
for revision in "${chromium_revision}" "${dawn_revision}"; do
  grep -q "${revision}" "${legal_notice}" \
      || echo "update ${legal_notice}: it does not name revision ${revision} (and check the license text there)"
done
