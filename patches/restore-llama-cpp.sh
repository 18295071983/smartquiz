#!/usr/bin/env bash
# Restore the project's local llama.cpp changes from the tracked patch.
#
# src/main/cpp/llama.cpp is a nested repo that the main repository ignores,
# so these changes only survive here. See patches/README.md.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LLAMA_DIR="${REPO_ROOT}/src/main/cpp/llama.cpp"
PATCH="${SCRIPT_DIR}/0001-android-app-compat.patch"
REV_FILE="${SCRIPT_DIR}/BASE_REVISION.txt"
LLAMA_URL="https://gitcode.com/gh_mirrors/ll/llama.cpp.git"

if [ ! -f "${PATCH}" ]; then
    echo "ERROR: patch not found: ${PATCH}" >&2
    exit 1
fi

BASE_COMMIT="$(sed -n 's/^base_commit[[:space:]]*=[[:space:]]*//p' "${REV_FILE}" | head -n1)"
if [ -z "${BASE_COMMIT}" ]; then
    echo "ERROR: could not read base_commit from ${REV_FILE}" >&2
    exit 1
fi

echo "patch       : ${PATCH}"
echo "base commit : ${BASE_COMMIT}"

if [ -d "${LLAMA_DIR}/.git" ]; then
    echo "llama.cpp already present at ${LLAMA_DIR}"
    cd "${LLAMA_DIR}"
    echo "--- current state ---"
    git status --short || true
else
    echo "cloning llama.cpp into ${LLAMA_DIR}"
    mkdir -p "$(dirname "${LLAMA_DIR}")"
    git clone "${LLAMA_URL}" "${LLAMA_DIR}"
    cd "${LLAMA_DIR}"
    git checkout "${BASE_COMMIT}"
fi

echo "--- applying patch ---"
if git apply --check "${PATCH}" 2>/dev/null; then
    git apply "${PATCH}"
    echo "OK: patch applied"
elif git apply --reverse --check "${PATCH}" 2>/dev/null; then
    echo "OK: patch already applied (nothing to do)"
else
    echo "ERROR: patch does not apply cleanly." >&2
    echo "The tree may be on a different upstream revision." >&2
    echo "Re-generate the patch with:" >&2
    echo "  cd ${LLAMA_DIR} && git diff origin/master..master > ${PATCH}" >&2
    exit 1
fi

echo "--- result ---"
git status --short
echo "done."
