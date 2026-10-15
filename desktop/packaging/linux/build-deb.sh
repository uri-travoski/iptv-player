#!/bin/sh
# worldtv_<version>_amd64.deb: the app in /opt/worldtv, VLC from the system (Debian, Ubuntu, Mint…).
. "$(dirname "$0")/common.sh"
W="$(mktemp -d)"
install -d "$W/opt" "$W/usr/bin" "$W/DEBIAN"
cp -a "$IMAGE" "$W/opt/worldtv"
ln -s /opt/worldtv/bin/worldtv "$W/usr/bin/worldtv"
install_desktop_files "$W/usr"
SIZE=$(du -sk "$W" | cut -f1)
cat > "$W/DEBIAN/control" <<CTL
Package: worldtv
Version: $VERSION
Architecture: amd64
Section: video
Priority: optional
Installed-Size: $SIZE
Depends: libvlc5, vlc-plugin-base, libxtst6, libxi6, libxrender1, fontconfig
Maintainer: WorldTV <noreply@github.com>
Homepage: https://github.com/uri-travoski/iptv-player
Description: IPTV player for Xtream logins and M3U playlists
 Live TV with a programme guide and catch-up, films and series with resume,
 favourites and channel groups. No channels of its own: it plays the playlist
 your provider gives you. Playback uses VLC.
CTL
dpkg-deb --root-owner-group -Zxz --build "$W" "$OUT/worldtv_${VERSION}_amd64.deb"
rm -rf "$W"
echo "$OUT/worldtv_${VERSION}_amd64.deb"
