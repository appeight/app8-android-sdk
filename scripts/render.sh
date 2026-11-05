#!/usr/bin/env bash
# render.sh — render one preview envelope on the running emulator and screenshot it.
#
# Usage: scripts/render.sh <envelope.json> [out.png]
#
# The envelope is the iOS preview-host format (app8-ios app8-preview-host/scripts/build-bundle.sh).
# Writes <out.png> and <out>.diag.json ({"warnings": [...]}: unmapped icons, placeholder
# components, decode drops, layout failures). Exits non-zero with a one-line error on stderr.
# Needs the sample installed (./gradlew :sample:installDebug) and one running device.
# Does not touch SystemUI demo mode.
set -euo pipefail

die() { echo "render.sh: $*" >&2; exit 1; }

[[ $# -ge 1 ]] || die "usage: render.sh <envelope.json> [out.png]"
ENVELOPE=$1
OUT=${2:-${ENVELOPE%.json}.png}
[[ -f "$ENVELOPE" ]] || die "no such envelope: $ENVELOPE"
command -v jq >/dev/null 2>&1 || die "jq required"

ADB=${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}
PKG=dev.app8.sample
DEV=/sdcard/Android/data/$PKG/files
TIMEOUT=${RENDER_TIMEOUT:-30}

$ADB get-state >/dev/null 2>&1 || die "no device"
$ADB shell pm path $PKG >/dev/null 2>&1 || die "$PKG not installed (./gradlew :sample:installDebug)"

# Host is up (singleTask: an existing instance is brought to front, not restarted).
$ADB shell am start -n $PKG/.PreviewHostActivity >/dev/null 2>&1 || die "cannot start PreviewHostActivity"
# The app creates jobs/, output/ and assets/ itself (it must own them).
for _ in $(seq 1 50); do
  [[ "$($ADB shell "[ -d $DEV/output ] && echo ok" | tr -d '\r')" == ok ]] && break
  sleep 0.1
done

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cp "$ENVELOPE" "$TMP/job.json"

# assetsRoot is a Mac path; the emulator can't read it. Push the directory once per
# (path, newest mtime) and point the envelope at the device copy. The copy is keyed by both
# and published with an atomic rename, so concurrent renders (several harnesses on one
# device) never see a half-pushed or deleted directory.
ROOT=$(jq -r '.bundle.assetsRoot // empty' "$ENVELOPE")
if [[ -n "$ROOT" && -d "$ROOT" ]]; then
  HASH=$(printf '%s' "$ROOT" | shasum | cut -c1-12)
  STAMP=$(find "$ROOT" -type f -exec stat -f '%m' {} + 2>/dev/null | sort -n | tail -1)
  REMOTE=$DEV/assets/$HASH-$STAMP
  if [[ "$($ADB shell "[ -d $REMOTE ] && echo ok" | tr -d '\r')" != ok ]]; then
    STAGE=$DEV/assets/.staging-$HASH-$$
    $ADB shell "rm -rf $STAGE; mkdir -p $STAGE" >/dev/null 2>&1
    $ADB push "$ROOT/." $STAGE/ >/dev/null 2>&1 || die "asset push failed"
    # Lost the race to another render: theirs is complete, drop ours.
    $ADB shell "if [ -d $REMOTE ]; then rm -rf $STAGE; else mv $STAGE $REMOTE; fi" >/dev/null 2>&1
    # Older copies of the same root are stale.
    $ADB shell "for d in $DEV/assets/$HASH-*; do [ \"\$d\" = $REMOTE ] || rm -rf \"\$d\"; done" >/dev/null 2>&1 || true
  fi
  jq --arg r "$REMOTE" '.bundle.assetsRoot = $r' "$ENVELOPE" > "$TMP/job.json"
fi

JOB=job-$(date +%s)-$$
$ADB shell rm -f "$DEV/output/$JOB.*" >/dev/null 2>&1 || true
$ADB push "$TMP/job.json" "$DEV/jobs/$JOB.tmp" >/dev/null 2>&1 || die "envelope push failed"
$ADB shell mv "$DEV/jobs/$JOB.tmp" "$DEV/jobs/$JOB.json"

deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
  state=$($ADB shell "if [ -f $DEV/output/$JOB.ready ]; then echo ready; elif [ -f $DEV/output/$JOB.error ]; then echo error; fi" | tr -d '\r')
  [[ "$state" == ready ]] && break
  if [[ "$state" == error ]]; then
    msg=$($ADB shell cat "$DEV/output/$JOB.error" | tr -d '\r' | head -1)
    $ADB shell rm -f "$DEV/output/$JOB.error"
    die "render failed: $msg"
  fi
  (( $(date +%s) < deadline )) || die "timed out after ${TIMEOUT}s waiting for $JOB"
  sleep 0.1
done

mkdir -p "$(dirname "$OUT")"
$ADB exec-out screencap -p > "$OUT" || die "screencap failed"
$ADB pull "$DEV/output/$JOB.diag.json" "${OUT%.png}.diag.json" >/dev/null 2>&1 || echo '{"warnings":[]}' > "${OUT%.png}.diag.json"
$ADB shell rm -f "$DEV/output/$JOB.ready" "$DEV/output/$JOB.diag.json"
echo "$OUT"
