#!/usr/bin/env bash
# Version consistency for the App8 release train (see README "Versioning").
#
#   scripts/check-version.sh                 # CI on every PR / push
#   scripts/check-version.sh --release <tag> # release workflow and /release preflight
#
# Always:
#   - EngineVersion.CURRENT is SemVer (MAJOR.MINOR.PATCH, optional -prerelease).
#   - dsl.lock is one SemVer line and equals EngineVersion.CURRENT: the SDK and the DSL spec
#     ride the same train, so dsl.lock names the app8-dsl tag of the release being built.
# With --release <tag>:
#   - <tag> (a leading "v" is ignored) equals EngineVersion.CURRENT.
#   - The app8-dsl tag named in dsl.lock exists on GitHub.
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION_FILE="engine-core/src/main/kotlin/dev/app8/engine/core/EngineVersion.kt"
SEMVER='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$'
DSL_REPO=https://github.com/appeight/app8-dsl.git

fail() { echo "::error::$*" >&2; exit 1; }

engine=$(sed -nE 's/.*CURRENT: *String *= *"([^"]+)".*/\1/p' "$VERSION_FILE")
[[ -n "$engine" ]] || fail "EngineVersion.CURRENT not found in $VERSION_FILE"
[[ "$engine" =~ $SEMVER ]] || fail "EngineVersion.CURRENT '$engine' is not SemVer"

[[ -f dsl.lock ]] || fail "dsl.lock is missing"
[[ $(grep -c . dsl.lock) -eq 1 ]] || fail "dsl.lock must be exactly one line (an app8-dsl tag)"
dsl=$(tr -d '[:space:]' < dsl.lock)
[[ "$dsl" =~ $SEMVER ]] || fail "dsl.lock '$dsl' is not a SemVer app8-dsl tag"
[[ "$dsl" == "$engine" ]] ||
    fail "dsl.lock ($dsl) != EngineVersion.CURRENT ($engine); bump both together (one release train)"

echo "EngineVersion.CURRENT = $engine, dsl.lock = $dsl"

if [[ "${1:-}" == "--release" ]]; then
    tag=${2:?usage: check-version.sh --release <tag>}
    [[ "${tag#v}" == "$engine" ]] || fail "tag '$tag' != EngineVersion.CURRENT ($engine)"
    git ls-remote --exit-code --tags "$DSL_REPO" "refs/tags/$dsl" > /dev/null ||
        fail "app8-dsl has no tag '$dsl'; tag app8-dsl first (VERSIONING.md, step 6)"
    echo "Release $tag: app8-dsl tag $dsl exists"
fi
