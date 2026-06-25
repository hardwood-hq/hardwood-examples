#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
#
# Copyright The original authors
#
# Licensed under the Apache Software License version 2.0, available at
# http://www.apache.org/licenses/LICENSE-2.0
#
# build-compat.sh — install the hardwood-parquet-java-compat module into your local
# Maven repository so this example can resolve it.
#
# The compat module is not published to Maven Central yet, unlike the rest of Hardwood.
# Until it is, build it from source: this script clones Hardwood at the exact version
# this example targets and `mvn install`s the compat module (and the Hardwood modules it
# needs) into ~/.m2. Run it once before the first `./mvnw exec:java`; rerun it after a
# version bump. Delete it once the module ships on Maven Central.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

REPO_URL="https://github.com/hardwood-hq/hardwood.git"
SRC_DIR=".hardwood-src"

# Read the target version from this example's pom.xml so the source build and the
# example never drift — bump-hardwood-version.sh keeps <version.hardwood> current, and
# the release tag is that version prefixed with `v` (e.g. 1.0.0.CR2 -> v1.0.0.CR2).
VERSION="$(perl -ne 'print $1 if m{<version\.hardwood>([^<]+)</version\.hardwood>}' pom.xml)"
[[ -n "$VERSION" ]] || { echo "error: could not read <version.hardwood> from pom.xml" >&2; exit 1; }
TAG="v${VERSION}"

echo "Building hardwood-parquet-java-compat ${VERSION} from ${REPO_URL} (${TAG})"

# Clone the tag once into a gitignored working copy, then reuse it. A shallow clone of
# the single tag keeps the checkout small; on a rerun, fetch the (possibly new) tag and
# move onto it.
if [[ ! -d "${SRC_DIR}/.git" ]]; then
    git clone --depth 1 --branch "${TAG}" "${REPO_URL}" "${SRC_DIR}"
else
    git -C "${SRC_DIR}" fetch --depth 1 --force origin "refs/tags/${TAG}:refs/tags/${TAG}"
    git -C "${SRC_DIR}" checkout --quiet "${TAG}"
fi

# Build and install Hardwood into ~/.m2 with tests skipped. The full reactor builds
# every module in dependency order — including the internal annotation-processor module
# the compat build needs — so dev.hardwood:hardwood-parquet-java-compat:${VERSION} lands
# in your local repository, where the example then finds it through the hardwood-bom.
( cd "${SRC_DIR}" && ./mvnw -q install -Dmaven.test.skip=true )

echo
echo "Done — hardwood-parquet-java-compat ${VERSION} is in your local Maven repository."
echo "Now run:  ./mvnw -q compile exec:java"
