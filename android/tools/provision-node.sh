#!/usr/bin/env bash
# Provision the Node.js runtime for the Android super Agent (step 1: node binary).
#
# Downloads pinned Termux .debs (official packages.termux.dev), extracts the
# aarch64 Node 24 LTS binary plus the shared libraries it needs, renames the
# versioned .so names to plain lib*.so names (Android's APK packager only
# extracts lib*.so files), rewrites DT_NEEDED with patchelf, and places
# everything in app/src/main/jniLibs/arm64-v8a/ so the APK ships it.
#
# Nothing here is committed to the repo: the binaries are tens of MB and are
# provisioned locally by maintainers when building an APK. .gitignore excludes
# jniLibs. No GitHub Actions workflow is included in this source release.
#
# Pinned 2026-11-06 (verified by hand: node v24.22.3, aarch64, Android linker,
# NEEDED libs listed below). To update versions, check the Termux package index
# (https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz)
# and change the VERSION variables. The versioned .so names in RENAME_MAP must
# match the new packages' SONAMEs (check with readelf -d on the node binary).
#
# Runtime note (corrected 2026-11-08 after K70 verification): Android's
# linker IGNORES DT_RPATH (it prints 'unused DT entry: DT_RPATH (ignoring)'),
# so RPATH=$ORIGIN does NOT make the loader find sibling libs. The real
# mechanism is LD_LIBRARY_PATH, which RuntimeEnv.kt sets in baseEnv() to
# nativeLibraryDir. RPATH is still set below (harmless, may help elsewhere).
# Second gotcha, also verified on K70: renaming a .so file does NOT rename its
# SONAME, and Android's linker keys VERNEED (versioned-symbol) lookups by
# SONAME. libnode.so failed with 'cannot find "libcrypto_3.so" from
# verneed[0]' because libcrypto_3.so still carried SONAME libcrypto.so.3.
# Every renamed lib must therefore get --set-soname to its new file name.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
JNILIBS="$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

BASE="https://packages.termux.dev/apt/termux-main"
# Pinned 2026-11-12 (package versions and archive hashes checked against the Termux package index; pool rotation: libicu 78.3 delisted, replaced by 79.1;
# nodejs-lts bumped 24.22.2-1 -> 24.22.3, binary reports 24.22.3 and
# links libicu*.so.79 - verified by hand from the .debs before this pin).
NODE_VER="24.22.3"
ICU_VER="79.1"
OPENSSL_VER="1:3.6.5"
CARES_VER="1.34.9"
SQLITE_VER="3.54.1-1"
ZLIB_VER="1.4"
LIBCXX_VER="30"

# old NEEDED name -> new file name (Android packager drops versioned .so names)
declare -A RENAME_MAP=(
  [libicudata.so.79]=libicudata_79.so
  [libicuuc.so.79]=libicuuc_79.so
  [libicui18n.so.79]=libicui18n_79.so
  [libcrypto.so.3]=libcrypto_3.so
  [libssl.so.3]=libssl_3.so
  [libz.so.1]=libz_1.so
)

echo "== pre-check pinned .deb URLs (fail fast on pool rotation, not mid-download)"
# Regression guard (B1): the Termux pool rotates versions without notice and
# curl -fSL only fails at the missing file. HEAD-check every URL first so a
# delisted pin fails here with the package name in the message.
for url in \
  "$BASE/pool/main/n/nodejs-lts/nodejs-lts_${NODE_VER}_aarch64.deb" \
  "$BASE/pool/main/libi/libicu/libicu_${ICU_VER}_aarch64.deb" \
  "$BASE/pool/main/o/openssl/openssl_${OPENSSL_VER}_aarch64.deb" \
  "$BASE/pool/main/c/c-ares/c-ares_${CARES_VER}_aarch64.deb" \
  "$BASE/pool/main/libs/libsqlite/libsqlite_${SQLITE_VER}_aarch64.deb" \
  "$BASE/pool/main/z/zlib/zlib_${ZLIB_VER}_aarch64.deb" \
  "$BASE/pool/main/libc/libc++/libc++_${LIBCXX_VER}_aarch64.deb" ; do
  echo "HEAD $url"
  code="$(curl -s -o /dev/null -w "%{http_code}" -I --max-time 30 "$url")"
  [ "$code" = "200" ] || { echo "PIN STALE (HTTP $code): $url - check the Termux package index and re-pin"; exit 1; }
done

echo "== download pinned Termux .debs"
mkdir -p "$STAGE/debs"
curl -fSL --retry 3 -o "$STAGE/debs/nodejs-lts.deb" \
  "$BASE/pool/main/n/nodejs-lts/nodejs-lts_${NODE_VER}_aarch64.deb"
curl -fSL --retry 3 -o "$STAGE/debs/libicu.deb" \
  "$BASE/pool/main/libi/libicu/libicu_${ICU_VER}_aarch64.deb"
curl -fSL --retry 3 -o "$STAGE/debs/openssl.deb" \
  "$BASE/pool/main/o/openssl/openssl_${OPENSSL_VER}_aarch64.deb"
curl -fSL --retry 3 -o "$STAGE/debs/c-ares.deb" \
  "$BASE/pool/main/c/c-ares/c-ares_${CARES_VER}_aarch64.deb"
curl -fSL --retry 3 -o "$STAGE/debs/libsqlite.deb" \
  "$BASE/pool/main/libs/libsqlite/libsqlite_${SQLITE_VER}_aarch64.deb"
curl -fSL --retry 3 -o "$STAGE/debs/zlib.deb" \
  "$BASE/pool/main/z/zlib/zlib_${ZLIB_VER}_aarch64.deb"
curl -fSL --retry 3 -o "$STAGE/debs/libcxx.deb" \
  "$BASE/pool/main/libc/libc++/libc++_${LIBCXX_VER}_aarch64.deb"

echo "== extract"
mkdir -p "$STAGE/pkg"
for deb in "$STAGE/debs"/*.deb; do
  dpkg-deb -x "$deb" "$STAGE/pkg"
done
TERMUX_USR="$STAGE/pkg/data/data/com.termux/files/usr"
[ -x "$TERMUX_USR/bin/node" ] || { echo "node binary not found in nodejs-lts .deb"; exit 1; }

echo "== install patchelf if missing"
if ! command -v patchelf >/dev/null 2>&1; then
  sudo apt-get update -qq && sudo apt-get install -y -qq patchelf
fi
command -v readelf >/dev/null 2>&1 || sudo apt-get install -y -qq binutils

echo "== place libs in jniLibs"
mkdir -p "$JNILIBS"
cp "$TERMUX_USR/bin/node" "$JNILIBS/libnode.so"
chmod +x "$JNILIBS/libnode.so"

copy_lib() { # $1 = name in Termux usr/lib, $2 = target name in jniLibs
  local src="$TERMUX_USR/lib/$1"
  if [ ! -e "$src" ]; then
    # Fall back to the versioned file of the same stem (e.g. libsqlite3.so.0).
    src="$(ls "$TERMUX_USR/lib/$1".* 2>/dev/null | head -n 1 || true)"
    [ -n "$src" ] || { echo "MISSING lib: $1"; exit 1; }
  fi
  cp -L "$src" "$JNILIBS/$2"
}
copy_lib libicudata.so.79 libicudata_79.so
copy_lib libicuuc.so.79   libicuuc_79.so
copy_lib libicui18n.so.79 libicui18n_79.so
copy_lib libcrypto.so.3   libcrypto_3.so
copy_lib libssl.so.3      libssl_3.so
copy_lib libz.so.1        libz_1.so
copy_lib libcares.so      libcares.so
copy_lib libsqlite3.so    libsqlite3.so
copy_lib libc++_shared.so libc++_shared.so

echo "== rewrite DT_NEEDED + set RPATH=\$ORIGIN"
replace_needed() { # $1=file $2=old $3=new
  if readelf -d "$1" 2>/dev/null | grep -q "NEEDED.*\[$2\]"; then
    patchelf --replace-needed "$2" "$3" "$1"
  fi
}
for f in "$JNILIBS"/*.so; do
  for old in "${!RENAME_MAP[@]}"; do
    replace_needed "$f" "$old" "${RENAME_MAP[$old]}"
  done
  patchelf --set-rpath '$ORIGIN' --force-rpath "$f"
done

echo "== set SONAME to the renamed file name"
# The file was renamed (e.g. libcrypto.so.3 -> libcrypto_3.so) but its SONAME
# still says the old name. Android's linker resolves VERNEED by SONAME, so a
# stale SONAME breaks versioned symbols (OPENSSL_3.0.0 etc.). New names are
# the same length as the old ones, so --set-soname fits in place.
for old in "${!RENAME_MAP[@]}"; do
  patchelf --set-soname "${RENAME_MAP[$old]}" "$JNILIBS/${RENAME_MAP[$old]}"
done

echo "== verify"
# 1. node is the pinned version and the right architecture.
file "$JNILIBS/libnode.so" | grep -q "ARM aarch64" || { echo "libnode.so is not aarch64"; exit 1; }
# NB：不要写 strings … | grep -q（grep -q 在首个匹配处就退出，strings
# 随即收 SIGPIPE 得 141；脚本 set 了 pipefail，管道就以 141 失败，
# 明明字符串在文件里。先落盘再 grep，含义不变。）
strings "$JNILIBS/libnode.so" > "$STAGE/libnode.strings"
grep -q "24\.22\.3" "$STAGE/libnode.strings" || { echo "libnode.so is not node $NODE_VER"; exit 1; }
# 2. every NEEDED entry resolves to a system lib or a sibling in jniLibs.
python3 - "$JNILIBS" <<'PYEOF'
import os, subprocess, sys
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
d = sys.argv[1]
have = set(os.listdir(d))
ok = True
for name in sorted(have):
    out = subprocess.run(["readelf", "-d", os.path.join(d, name)],
                         capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "NEEDED" in line:
            lib = line.split("[")[1].split("]")[0]
            if lib not in SYSTEM and lib not in have:
                print(f"UNRESOLVED: {name} needs {lib}")
                ok = False
if not ok:
    sys.exit(1)
print("all DT_NEEDED entries resolve")
# 3. every SONAME (when present) equals the file name: Android's linker keys
# VERNEED lookups by SONAME, so a stale SONAME breaks versioned symbols even
# when the file itself is found. Libs without a SONAME (libnode.so itself,
# libsqlite3.so, libpython3.11.so) are looked up by file name and are fine.
for name in sorted(have):
    out = subprocess.run(["readelf", "-d", os.path.join(d, name)],
                         capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "SONAME" in line:
            soname = line.split("[")[1].split("]")[0]
            if soname != name:
                print(f"STALE SONAME: {name} carries SONAME {soname}")
                ok = False
if not ok:
    sys.exit(1)
print("all SONAMEs match file names")
PYEOF
ls -la "$JNILIBS"
du -sh "$JNILIBS"
echo "node provisioned: libnode.so + ${#RENAME_MAP[@]} renamed libs (SONAMEs fixed) + 3 plain libs"
