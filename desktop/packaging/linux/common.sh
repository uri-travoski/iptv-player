# Shared by the package scripts. Run from the repository root after
#   ./gradlew :desktop:createReleaseDistributable -PappVersion=<version>
set -eu
VERSION="${VERSION:?set VERSION, e.g. 2.38}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
IMAGE="$ROOT/desktop/build/compose/binaries/main-release/app/worldtv"
OUT="${OUT:-$ROOT/desktop/build/dist}"
APP_ID=com.worldtv.WorldTV
mkdir -p "$OUT"
[ -x "$IMAGE/bin/worldtv" ] || { echo "No app image at $IMAGE: build it first" >&2; exit 1; }

# The desktop entry, icons and AppStream file under $1 (a /usr-like prefix).
install_desktop_files() {
  install -Dm644 "$HERE/$APP_ID.desktop" "$1/share/applications/$APP_ID.desktop"
  install -Dm644 "$HERE/worldtv.png" "$1/share/icons/hicolor/256x256/apps/$APP_ID.png"
  install -Dm644 "$HERE/worldtv.svg" "$1/share/icons/hicolor/scalable/apps/$APP_ID.svg"
  install -d "$1/share/metainfo"
  sed -e "s/@VERSION@/$VERSION/" -e "s/@DATE@/$(date -u +%F)/" "$HERE/$APP_ID.metainfo.xml" > "$1/share/metainfo/$APP_ID.metainfo.xml"
}
