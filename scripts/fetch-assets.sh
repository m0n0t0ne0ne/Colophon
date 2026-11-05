#!/usr/bin/env bash
# Downloads the speech model that is bundled into the APK. It is not stored in git.
set -euo pipefail
out="$(dirname "$0")/../app/src/main/assets/model.zip"
[ -s "$out" ] || curl -fsSL -o "$out" https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
ls -la "$out"
