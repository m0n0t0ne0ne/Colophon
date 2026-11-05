#!/usr/bin/env bash
# Downloads the speech model that is bundled into the APK. It is not stored in git.
set -euo pipefail
out="$(dirname "$0")/../app/src/main/assets/model.zip"
mkdir -p "$(dirname "$out")"
[ -s "$out" ] || curl -fsSL --retry 3 -o "$out" https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
echo "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498  $out" | sha256sum -c -
