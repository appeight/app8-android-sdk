#!/bin/sh
# Regenerates golden-expected.txt by running golden-corpus.txt through the REAL iOS
# expression engine sources (compiled standalone with tiny stubs for A8Log/App8Context).
#   IOS_SDK=/path/to/app8-ios-sdk ./regenerate.sh
# Requires a macOS Swift toolchain. Output is deterministic (locale en_US, TZ=UTC) as long as
# the corpus avoids dictionary-order-dependent results (Swift dictionaries are unordered).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="${IOS_SDK:?set IOS_SDK}/Sources/App8Engine"
WORK=$(mktemp -d)
cp "$SRC"/Variables/Expression/*.swift "$SRC"/Variables/Models/*.swift "$SRC"/Variables/Store/*.swift \
   "$SRC"/Variables/Resolution/VariableContext.swift "$SRC"/Variables/Validation/SchemaValidator.swift \
   "$SRC"/Core/EngineLimits.swift "$SRC"/Utils/Codables/JsonValue.swift "$HERE"/main.swift "$HERE"/Stubs.swift "$WORK"/
swiftc -swift-version 5 -O "$WORK"/*.swift -o "$WORK"/oracle
TZ=UTC "$WORK"/oracle "$HERE"/../golden-vars.json < "$HERE"/../golden-corpus.txt > "$HERE"/../golden-expected.txt
echo "wrote $HERE/../golden-expected.txt"
