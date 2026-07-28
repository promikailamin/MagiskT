#####################################################################
#   AVD MagiskInit Setup
#####################################################################
#
# Patches an Android Virtual Device (AVD) ramdisk.img to integrate
# magiskinit, enabling full integration testing of Magisk's init
# replacement across different API levels.
#
# This script is NOT intended to be run directly on the host — use
# `./build.py avd_patch path/to/ramdisk.img` instead.
#
# Support API level: 23 - 36
#
# Usage (from emulator, after pushing files):
#   ./build.py avd_patch path/to/booted/avd-image/ramdisk.img
#
# After patching ramdisk.img, close the emulator, then select
# "Cold Boot Now" in AVD Manager to force a full reboot.
#
#####################################################################
# AVD Init Configurations:
#
# rootfs w/o early mount:      API 23 - 25
# rootfs with early mount:     API 26 - 27
# Legacy system-as-root:       API 28
# 2 stage init:                API 29 - 35
#####################################################################

if [ ! -f /system/build.prop ]; then
  # Running on PC
  echo 'Please run `./build.py avd_patch` instead of directly executing the script!'
  exit 1
fi

cd /data/local/tmp
chmod 755 busybox

# Re-execute with BusyBox ash in standalone mode so that all shell
# utilities (grep, sed, etc.) come from BusyBox instead of Toybox.
if [ -z "$FIRST_STAGE" ]; then
  export FIRST_STAGE=1
  export ASH_STANDALONE=1
  exec ./busybox sh $0 "$@"
fi

TARGET_FILE="$1"
OUTPUT_FILE="$1.magisk"

# Determine whether the target is a standalone ramdisk or a full boot image
if echo "$TARGET_FILE" | grep -q 'ramdisk'; then
  IS_RAMDISK=true
else
  IS_RAMDISK=false
fi

# Extract required files from the Magisk APK
unzip -oj magisk.apk 'assets/util_functions.sh' 'assets/stub.apk'
. ./util_functions.sh

api_level_arch_detect

unzip -oj magisk.apk "lib/$ABI/*" -x "lib/$ABI/libbusybox.so"
# Rename lib*.so to bare names (strip lib prefix, .so suffix)
for file in lib*.so; do
  chmod 755 $file
  mv "$file" "${file:3:${#file}-6}"
done

# Decompress or unpack the target image
if $IS_RAMDISK; then
  ./magiskboot decompress "$TARGET_FILE" ramdisk.cpio
else
  ./magiskboot unpack "$TARGET_FILE"
fi
cp ramdisk.cpio ramdisk.cpio.orig

export KEEPVERITY=true
export KEEPFORCEENCRYPT=true

echo "KEEPVERITY=$KEEPVERITY" > config
echo "KEEPFORCEENCRYPT=$KEEPFORCEENCRYPT" >> config
echo "PREINITDEVICE=$(./magisk --preinit-device)" >> config
# For API 28 (legacy system-as-root), explicitly override skip_initramfs
[ $API = "28" ] && echo 'RECOVERYMODE=true' >> config
cat config

# Compress Magisk binaries for embedding into ramdisk
./magiskboot compress=xz magisk magisk.xz
./magiskboot compress=xz stub.apk stub.xz
./magiskboot compress=xz init-ld init-ld.xz

# Patch the ramdisk: replace init with magiskinit, add overlay files
./magiskboot cpio ramdisk.cpio \
"add 0750 init magiskinit" \
"mkdir 0750 overlay.d" \
"mkdir 0750 overlay.d/sbin" \
"add 0644 overlay.d/sbin/magisk.xz magisk.xz" \
"add 0644 overlay.d/sbin/stub.xz stub.xz" \
"add 0644 overlay.d/sbin/init-ld.xz init-ld.xz" \
"patch" \
"backup ramdisk.cpio.orig" \
"mkdir 000 .backup" \
"add 000 .backup/.magisk config"

rm -f ramdisk.cpio.orig config *.xz
if $IS_RAMDISK; then
  ./magiskboot compress=gzip ramdisk.cpio "$OUTPUT_FILE"
else
  ./magiskboot repack "$TARGET_FILE" "$OUTPUT_FILE"
  ./magiskboot cleanup
fi
