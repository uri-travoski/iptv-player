#!/bin/bash
# WorldTV-<version>-x86_64.AppImage: runs on any recent distribution, VLC inside (no install).
# Built on the oldest supported system (CI: Ubuntu 22.04) so its libraries work on newer ones.
# Needs the system's VLC (libvlc5, vlc-plugin-base, libvlc-bin) to copy from.
. "$(dirname "$0")/common.sh"
set -o pipefail
TOOLS="${TOOLS:-$ROOT/desktop/build/appimage-tools}"
mkdir -p "$TOOLS"
fetch() { [ -x "$TOOLS/$1" ] || { curl -fsSL -o "$TOOLS/$1" "$2" && chmod +x "$TOOLS/$1"; }; }
fetch linuxdeploy https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-x86_64.AppImage
fetch appimagetool https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
export APPIMAGE_EXTRACT_AND_RUN=1

LIBDIR=$(dirname "$(readlink -f "$(ldconfig -p | awk '/libvlc\.so\.5 / && /x86-64/ {print $NF; exit}')")")
[ -f "$LIBDIR/libvlc.so.5" ] || { echo "libvlc5 not installed" >&2; exit 1; }
VLC="$LIBDIR/vlc"

A="$(mktemp -d)/WorldTV.AppDir"
mkdir -p "$A/usr/lib/vlc/plugins"
cp -L "$LIBDIR/libvlc.so.5" "$LIBDIR/libvlccore.so.9" "$A/usr/lib/vlc/"
cp -a "$VLC"/libvlc_pulse.so* "$A/usr/lib/vlc/" 2>/dev/null || true
# Only what playback needs: no VLC interfaces, no video windows (WorldTV draws the frames).
for d in access audio_filter audio_mixer audio_output codec demux logger misc packetizer spu \
         stream_extractor stream_filter text_renderer video_chroma video_filter; do
  [ -d "$VLC/plugins/$d" ] && cp -a "$VLC/plugins/$d" "$A/usr/lib/vlc/plugins/"
done
mkdir -p "$A/usr/lib/vlc/plugins/video_output"
cp "$VLC"/plugins/video_output/lib{vmem,vdummy}_plugin.so "$A/usr/lib/vlc/plugins/video_output/"
# Hardware decoding needs the system's drivers: left to the .deb / .rpm / Flatpak.
rm -f "$A"/usr/lib/vlc/plugins/codec/*{vaapi,vdpau}* "$A"/usr/lib/vlc/plugins/video_chroma/*{vdpau,vaapi}* 2>/dev/null || true

install_desktop_files "$A/usr"
cat > "$A/AppRun" <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
export WORLDTV_VLC_DIR="$HERE/usr/lib/vlc"
export VLC_PLUGIN_PATH="$HERE/usr/lib/vlc/plugins"
export LD_LIBRARY_PATH="$HERE/usr/lib:$HERE/usr/lib/vlc${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$HERE/usr/lib/worldtv/bin/worldtv" "$@"
RUN
chmod +x "$A/AppRun"

# The libraries VLC and its plugins need, except those every system has (linuxdeploy's list).
DEPS=(--deploy-deps-only "$A/usr/lib/vlc")
for d in "$A"/usr/lib/vlc/plugins/*/; do DEPS+=(--deploy-deps-only "$d"); done
"$TOOLS/linuxdeploy" --appdir "$A" "${DEPS[@]}" \
  --desktop-file "$A/usr/share/applications/$APP_ID.desktop" \
  --icon-file "$A/usr/share/icons/hicolor/256x256/apps/$APP_ID.png" > "$TOOLS/linuxdeploy.log" 2>&1 || { tail -20 "$TOOLS/linuxdeploy.log" >&2; exit 1; }
# The app (with its own Java) goes in after linuxdeploy, which would otherwise scan the JRE.
cp -a "$IMAGE" "$A/usr/lib/worldtv"
# linuxdeploy put libs in usr/lib; plugins look there through LD_LIBRARY_PATH.
"$VLC/vlc-cache-gen" "$A/usr/lib/vlc/plugins" 2>/dev/null || true

ln -sf "usr/share/applications/$APP_ID.desktop" "$A/$APP_ID.desktop" 2>/dev/null || true
F="$OUT/WorldTV-$VERSION-x86_64.AppImage"
ARCH=x86_64 "$TOOLS/appimagetool" --no-appstream "$A" "$F" >/dev/null
rm -rf "$(dirname "$A")"
echo "$F"
