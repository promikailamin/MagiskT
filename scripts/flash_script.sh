#MAGISK
############################################
# Magisk Flash Script (updater-script)
#
# This is the entry point used by Magisk's install ZIP.
# It is copied into META-INF/com/google/android/updater-script
# during the build (see Setup.kt).
#
# The script:
#   1. Loads Magisk utility functions
#   2. Detects the boot image and device architecture
#   3. Copies binaries and scripts to /data/adb/magisk
#   4. Installs the addon.d survival script (if /system/addon.d exists)
#   5. Patches the boot image via install_magisk()
############################################

##############
# Preparation
##############

# Default permissions
umask 022

OUTFD=$2
COMMONDIR=$INSTALLER/assets
CHROMEDIR=$INSTALLER/assets/chromeos

if [ ! -f $COMMONDIR/util_functions.sh ]; then
  echo "! Unable to extract zip file!"
  exit 1
fi

# Load utility functions
. $COMMONDIR/util_functions.sh

setup_flashable

############
# Detection
############

if echo $MAGISK_VER | grep -q '\.'; then
  PRETTY_VER=$MAGISK_VER
else
  PRETTY_VER="$MAGISK_VER($MAGISK_VER_CODE)"
fi
print_title "Magisk $PRETTY_VER Installer"

is_mounted /data || mount /data || is_mounted /cache || mount /cache
mount_partitions
check_data
get_flags
find_boot_image

[ -z $BOOTIMAGE ] && abort "! Unable to detect target image"
ui_print "- Target image: $BOOTIMAGE"

# Detect version and architecture
api_level_arch_detect

[ $API -lt 23 ] && abort "! Magisk only support Android 6.0 and above"

ui_print "- Device platform: $ABI"

BINDIR=$INSTALLER/lib/$ABI
cd $BINDIR
# Rename lib*.so to their actual name (strip lib prefix and .so suffix)
for file in lib*.so; do mv "$file" "${file:3:${#file}-6}"; done
cd /
cp -af $INSTALLER/lib/$ABI32/libmagisk.so $BINDIR/magisk32 2>/dev/null

# Check if system root is installed and remove
$BOOTMODE || remove_system_su

##############
# Environment
##############

ui_print "- Constructing environment"

# Copy required files
rm -rf $MAGISKBIN 2>/dev/null
mkdir -p $MAGISKBIN 2>/dev/null
cp -af $BINDIR/. $COMMONDIR/. $BBBIN $MAGISKBIN

# Remove files only used by the Magisk app (not needed at boot time)
rm -f $MAGISKBIN/bootctl $MAGISKBIN/main.jar \
  $MAGISKBIN/module_installer.sh $MAGISKBIN/uninstaller.sh

chmod -R 755 $MAGISKBIN

# addon.d: install OTA survival script
if [ -d /system/addon.d ]; then
  ui_print "- Adding addon.d survival script"
  blockdev --setrw /dev/block/mapper/system$SLOT 2>/dev/null
  mount -o rw,remount /system || mount -o rw,remount /
  ADDOND=/system/addon.d/99-magisk.sh
  cp -af $COMMONDIR/addon.d.sh $ADDOND
  chmod 755 $ADDOND
fi

##################
# Image Patching
##################

install_magisk

# Cleanups
$BOOTMODE || recovery_cleanup
rm -rf $TMPDIR

ui_print "- Done"
exit 0
