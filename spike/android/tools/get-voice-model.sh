#!/usr/bin/env bash
# Fetches the offline speech model for voice commands into the app's assets.
# Vosk small Indian English model, 37 MB download, 54 MB unpacked. Kept out of git.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/model-en-in
[ -f "$DEST/uuid" ] && { echo "already present: $DEST"; exit 0; }
TMP="$(mktemp -d)"
curl -fL -o "$TMP/model.zip" https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip
unzip -q "$TMP/model.zip" -d "$TMP"
mkdir -p "$DEST"
cp -R "$TMP/vosk-model-small-en-in-0.4/." "$DEST/"
# Vosk copies the model out of assets once, and again only when this changes.
echo "taar-vosk-small-en-in-0.4" > "$DEST/uuid"
rm -rf "$TMP"
echo "model ready in $DEST"
