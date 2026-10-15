#!/bin/sh
# worldtv-<version>-1.x86_64.rpm: the app in /opt/worldtv, VLC from the system (Fedora with
# RPM Fusion or Fedora's own VLC, openSUSE…).
. "$(dirname "$0")/common.sh"
T="$(mktemp -d)"
mkdir -p "$T/BUILD" "$T/RPMS" "$T/SPECS" "$T/stage"
cp -a "$IMAGE" "$T/stage/worldtv"
install_desktop_files "$T/stage/usr"
cat > "$T/SPECS/worldtv.spec" <<SPEC
Name:           worldtv
Version:        $VERSION
Release:        1
Summary:        IPTV player for Xtream logins and M3U playlists
License:        GPL-3.0-only
URL:            https://github.com/uri-travoski/iptv-player
BuildArch:      x86_64
AutoReqProv:    no
Requires:       (vlc-libs or libvlc5)
Recommends:     (vlc-plugins-base or vlc-codecs or vlc-core)
%global debug_package %{nil}
%global __os_install_post %{nil}
%global _build_id_links none
%global _binary_payload w6.xzdio

%description
Live TV with a programme guide and catch-up, films and series with resume,
favourites and channel groups. No channels of its own: it plays the playlist
your provider gives you. Playback uses VLC.

%install
mkdir -p %{buildroot}/opt %{buildroot}/usr/bin
cp -a $T/stage/worldtv %{buildroot}/opt/worldtv
ln -s /opt/worldtv/bin/worldtv %{buildroot}/usr/bin/worldtv
cp -a $T/stage/usr/share %{buildroot}/usr/share

%files
/opt/worldtv
/usr/bin/worldtv
/usr/share/applications/$APP_ID.desktop
/usr/share/icons/hicolor/256x256/apps/$APP_ID.png
/usr/share/icons/hicolor/scalable/apps/$APP_ID.svg
/usr/share/metainfo/$APP_ID.metainfo.xml
SPEC
rpmbuild --define "_topdir $T" -bb "$T/SPECS/worldtv.spec" >/dev/null
cp "$T"/RPMS/x86_64/worldtv-"$VERSION"-1.x86_64.rpm "$OUT/"
rm -rf "$T"
echo "$OUT/worldtv-$VERSION-1.x86_64.rpm"
