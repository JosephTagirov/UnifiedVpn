#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
properties_file="$repository_root/gradle.properties"
source_root="$repository_root/.downloads/linux-release-sources"

fail() {
    echo "[linux-release] ERROR: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

read_property() {
    key=$1
    value=$(sed -n "s/^${key}=//p" "$properties_file" | tail -n 1)
    [ -n "$value" ] || fail "Missing $key in gradle.properties"
    printf '%s\n' "$value"
}

verify_checkout() {
    directory=$1
    expected_sha=$2
    label=$3

    [ -d "$directory/.git" ] || fail "$label checkout is not a Git repository: $directory"
    actual_sha=$(git -C "$directory" rev-parse HEAD)
    [ "$actual_sha" = "$expected_sha" ] || \
        fail "$label checkout is $actual_sha, expected $expected_sha"
    [ -z "$(git -C "$directory" status --porcelain)" ] || \
        fail "$label checkout has local changes: $directory"
}

prepare_checkout() {
    repository_url=$1
    directory=$2
    expected_sha=$3
    label=$4

    if [ ! -d "$directory/.git" ]; then
        [ ! -e "$directory" ] || fail "$label path exists but is not a Git repository: $directory"
        mkdir -p "$(dirname -- "$directory")"
        git clone --filter=blob:none --no-checkout "$repository_url" "$directory"
    fi
    [ -z "$(git -C "$directory" status --porcelain)" ] || \
        fail "$label checkout has local changes: $directory"
    git -C "$directory" fetch --depth=1 origin "$expected_sha"
    git -C "$directory" checkout --detach "$expected_sha"
    verify_checkout "$directory" "$expected_sha" "$label"
}

[ "$(uname -s)" = "Linux" ] || fail "Run this script on Linux"
[ -f "$properties_file" ] || fail "gradle.properties was not found"

for command_name in git go java make cc sed tail; do
    require_command "$command_name"
done

appimage_tool=${APPIMAGETOOL:-}
if [ -z "$appimage_tool" ]; then
    appimage_tool=$(command -v appimagetool || true)
fi
[ -n "$appimage_tool" ] || fail "Set APPIMAGETOOL to an executable appimagetool path"
[ -x "$appimage_tool" ] || fail "APPIMAGETOOL is not executable: $appimage_tool"

olcrtc_sha=$(read_property "olcbox.olcrtcSha")
awg_sha=$(read_property "olcbox.awgCoreSha")
xray_sha=$(read_property "olcbox.xraySha")

if [ -z "${OLCRTC_REPO:-}" ]; then
    OLCRTC_REPO="$source_root/olcrtc"
    prepare_checkout \
        "https://github.com/openlibrecommunity/olcrtc.git" \
        "$OLCRTC_REPO" "$olcrtc_sha" "olcRTC"
else
    verify_checkout "$OLCRTC_REPO" "$olcrtc_sha" "olcRTC"
fi

if [ -z "${SING_BOX_AWG_REPO:-}" ]; then
    SING_BOX_AWG_REPO="$source_root/sing-box-awg"
    prepare_checkout \
        "https://github.com/Throneproj/sing-box.git" \
        "$SING_BOX_AWG_REPO" "$awg_sha" "AWG sing-box"
else
    verify_checkout "$SING_BOX_AWG_REPO" "$awg_sha" "AWG sing-box"
fi

if [ -z "${XRAY_REPO:-}" ]; then
    XRAY_REPO="$source_root/xray"
    prepare_checkout \
        "https://github.com/XTLS/Xray-core.git" \
        "$XRAY_REPO" "$xray_sha" "Xray"
else
    verify_checkout "$XRAY_REPO" "$xray_sha" "Xray"
fi

export OLCRTC_REPO SING_BOX_AWG_REPO XRAY_REPO
export APPIMAGETOOL="$appimage_tool"

echo "[linux-release] Building pinned native engines and AppImage"
cd "$repository_root"
sh ./gradlew \
    :desktopApp:verifyReleaseLinuxAppImage \
    --no-configuration-cache \
    --no-daemon \
    --console=plain

echo "[linux-release] PASS: AppImage launched in extract mode and verified its JVM/native assets"
echo "[linux-release] Output directory: $repository_root/desktopApp/build/compose/binaries/main-release/appimage"
