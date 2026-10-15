#!/bin/sh
# WorldTV-<version>-x86_64.flatpak: a bundle to install with
#   flatpak install --user WorldTV-<version>-x86_64.flatpak
# (Flathub must be set up as a remote: it supplies the KDE runtime and VLC).
# Needs flatpak and flatpak-builder; downloads the KDE SDK and VLC from Flathub (~1.5 GB) once.
. "$(dirname "$0")/common.sh"
W="$ROOT/desktop/build/flatpak"
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
# The runtime VLC is built on (e.g. org.kde.Platform/x86_64/5.15-25.08): WorldTV uses the same.
RUNTIME=$(flatpak --user remote-info flathub org.videolan.VLC//stable | sed -n 's/^ *Runtime: *//p')
RTV="${RUNTIME##*/}"
[ -n "$RTV" ] || { echo "Couldn't read VLC's runtime from Flathub" >&2; exit 1; }
flatpak --user install -y --noninteractive flathub "org.kde.Platform//$RTV" "org.kde.Sdk//$RTV" org.videolan.VLC//stable

rm -rf "$W/src"
mkdir -p "$W/src"
cp -a "$IMAGE" "$W/src/app"
cp "$HERE/$APP_ID.desktop" "$HERE/worldtv.png" "$HERE/worldtv.svg" "$W/src/"
sed -e "s/@VERSION@/$VERSION/" -e "s/@DATE@/$(date -u +%F)/" "$HERE/$APP_ID.metainfo.xml" > "$W/src/$APP_ID.metainfo.xml"
sed "s/@RUNTIME_VERSION@/$RTV/" "$HERE/$APP_ID.yml" > "$W/src/$APP_ID.yml"
cat > "$W/src/worldtv.sh" <<'RUN'
#!/bin/sh
# VLC is the base app's, in /app/lib.
export WORLDTV_VLC_DIR=/app/lib
export VLC_PLUGIN_PATH=/app/lib/vlc/plugins
exec /app/lib/worldtv/bin/worldtv "$@"
RUN

flatpak-builder --user --force-clean --disable-rofiles-fuse --repo="$W/repo" "$W/build" "$W/src/$APP_ID.yml"
flatpak build-bundle --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
  "$W/repo" "$OUT/WorldTV-$VERSION-x86_64.flatpak" "$APP_ID"
echo "$OUT/WorldTV-$VERSION-x86_64.flatpak"
