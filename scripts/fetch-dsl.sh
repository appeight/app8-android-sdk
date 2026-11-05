#!/usr/bin/env bash
# Clones appeight/app8-dsl at the tag in dsl.lock, so the doc-example tests
# (DslDocumentationTest) find `app8-dsl/docs` next to this repo.
#
#   scripts/fetch-dsl.sh [dest]            # dest defaults to ../app8-dsl
#   scripts/fetch-dsl.sh --strict [dest]   # release: the tag must exist
#
# Between releases dsl.lock names the upcoming train, whose app8-dsl tag is cut at release
# time. Until then the non-strict mode falls back to app8-dsl main and prints a warning.
set -euo pipefail

cd "$(dirname "$0")/.."

strict=false
if [[ "${1:-}" == "--strict" ]]; then strict=true; shift; fi
dest=${1:-../app8-dsl}
repo=https://github.com/appeight/app8-dsl.git
tag=$(tr -d '[:space:]' < dsl.lock)

if [[ -e "$dest" ]]; then
    echo "$dest already exists; leaving it as is" >&2
    exit 0
fi

if git ls-remote --exit-code --tags "$repo" "refs/tags/$tag" > /dev/null; then
    git clone --quiet --depth 1 --branch "$tag" "$repo" "$dest"
    echo "app8-dsl $tag -> $dest"
elif $strict; then
    echo "::error::app8-dsl has no tag '$tag' (dsl.lock)" >&2
    exit 1
else
    echo "::warning::app8-dsl tag '$tag' (dsl.lock) is not published yet; testing against app8-dsl main"
    git clone --quiet --depth 1 "$repo" "$dest"
    echo "app8-dsl main -> $dest"
fi
