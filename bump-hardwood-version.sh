#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
#
# Copyright The original authors
#
# Licensed under the Apache Software License version 2.0, available at
# http://www.apache.org/licenses/LICENSE-2.0
#
# bump-hardwood-version.sh — set the Hardwood version across every example in a single
# run, keeping each example's standalone copy in lockstep. The version is pinned in two
# places per example, and both move together here:
#
#   <version.hardwood>X</version.hardwood>          in every pom.xml
#   https://hardwood.dev/api/X/                     in every README and Main.java
#
# The Javadoc link is the only documentation URL that carries a version — the prose docs
# deliberately track https://hardwood.dev/latest/ and are left alone. The URL rewrite
# matches whatever the segment currently holds, a concrete version (e.g. 1.0.0.CR1) or
# `latest`, so an example that has drifted is still corrected.
#
# The rewrite then verifies the tree still compiles, and rolls every edited file back if
# it does not — so a typo'd or unreleased version never leaves the examples half-bumped.

set -euo pipefail

usage() {
    cat <<'EOF'
bump-hardwood-version.sh — set the Hardwood version across every example's pom.xml and
Javadoc link in lockstep, then verify the tree still compiles.

Usage: bump-hardwood-version.sh <new-version>
       bump-hardwood-version.sh 1.0.0.CR3
EOF
}

NEW_VERSION=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)    usage; exit 0 ;;
        -*)           echo "error: unknown argument '$1'" >&2; usage >&2; exit 1 ;;
        *)            NEW_VERSION="$1"; shift ;;
    esac
done

[[ -n "$NEW_VERSION" ]] || { usage >&2; exit 1; }

cd "$(dirname "${BASH_SOURCE[0]}")"

# Collect every tracked pom.xml. Bail if there are none rather than letting `perl -i`
# fall through to reading STDIN and hang.
mapfile -t POMS < <(git ls-files '*pom.xml')
[[ ${#POMS[@]} -gt 0 ]] || { echo "error: no tracked pom.xml found under $(pwd)" >&2; exit 1; }

# Collect every example README/Main.java that carries a versioned Javadoc link. CLAUDE.md
# is excluded on purpose: it documents the URL shape with a literal <version> placeholder
# that must not be turned into a concrete version.
mapfile -t DOCS < <(git ls-files '*.md' '*.java' | grep -vx 'CLAUDE.md' | xargs grep -l 'hardwood\.dev/api/' 2>/dev/null || true)

echo "Setting Hardwood version to ${NEW_VERSION}"

# Rewrite the version in place, matching whatever value is currently present so a file
# that has drifted out of lockstep is still corrected. Identical rewrites leave bytes —
# including the trailing newline — untouched.
NEW="$NEW_VERSION" perl -i -pe '
    s{(<version\.hardwood>)[^<]*(</version\.hardwood>)}{$1$ENV{NEW}$2}g;
' "${POMS[@]}"

# Bump the version segment of the Javadoc URL — `[^/]+` matches a concrete version or
# `latest`. The prose docs under /latest/ are a different path and stay put.
if [[ ${#DOCS[@]} -gt 0 ]]; then
    NEW="$NEW_VERSION" perl -i -pe '
        s{(hardwood\.dev/api/)[^/]+(/)}{$1$ENV{NEW}$2}g;
    ' "${DOCS[@]}"
fi

FILES=("${POMS[@]}" "${DOCS[@]}")

echo
echo "Updated files:"
git diff --name-only -- "${FILES[@]}" | sed 's/^/  ~ /'

# hardwood-parquet-java-compat is the one module not yet on Maven Central, so the
# verifying compile below cannot resolve it from a remote. Its example ships a
# build-compat.sh that clones Hardwood at the matching tag and installs the module into
# ~/.m2. Run it here — now that the pom carries ${NEW_VERSION} it builds that exact
# version — so compat lands locally before the compile that needs it. Drop this block
# once the module ships on Central and build-compat.sh is deleted.
COMPAT_BUILD="parquet-java-compat/build-compat.sh"
if [[ -x "$COMPAT_BUILD" ]]; then
    echo
    echo "Installing hardwood-parquet-java-compat ${NEW_VERSION} into ~/.m2: ${COMPAT_BUILD}"
    if ! "$COMPAT_BUILD"; then
        echo >&2
        echo "error: building compat ${NEW_VERSION} from source failed; reverting the version edits (git checkout)." >&2
        git checkout -- "${FILES[@]}"
        exit 1
    fi
fi

echo
# `-U` forces Maven to re-check the remote repositories, re-attempting resolutions that a
# previous run cached as failures. Without it, a version freshly published to Central but
# requested locally before the sync completed stays "absent" until the daily update
# interval elapses — so a real, released version would be reported as a build failure.
echo "Verifying the tree still compiles: ./mvnw -U -q compile"
if ! ./mvnw -U -q compile; then
    echo >&2
    echo "error: build failed at ${NEW_VERSION}; reverting the version edits (git checkout)." >&2
    git checkout -- "${FILES[@]}"
    exit 1
fi
echo "Build OK."
