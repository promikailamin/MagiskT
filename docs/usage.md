# Magisk - Complete Usage Guide

## Table of Contents

1. [What is Magisk?](#what-is-magisk)
2. [Installation](#installation)
3. [Magisk Manager App UI](#magisk-manager-app-ui)
4. [Superuser Management](#superuser-management)
5. [Modules](#modules)
6. [DenyList](#denylist)
7. [Zygisk](#zygisk)
8. [Installation Methods](#installation-methods)
9. [Command Line Interface](#command-line-interface)
10. [Boot Image Patching](#boot-image-patching)
11. [Troubleshooting](#troubleshooting)

---

## What is Magisk?

Magisk is a suite of open-source software for customizing Android, supporting devices running Android 6.0+ (API 23+). It provides:

- **Systemless root** via `su` command
- **Systemless modifications** via modules (modify system without altering system partition)
- **Zygisk** - Inject code into app processes via Zygote
- **DenyList** - Hide root/Magisk from selected apps (bypass root detection)
- **Boot image patching** - Modify boot images for custom init
- **SELinux policy manipulation** via magiskpolicy

### Key Concepts

| Term | Definition |
|------|------------|
| **Systemless** | Modifications are applied via mount overlay, never modifying actual system partitions |
| **MagiskSU** | Root access management system with per-app permissions |
| **Magisk Modules** | ZIP files containing systemless modifications (themes, mods, etc.) |
| **Zygisk** | Modified Zygote that loads modules into app processes |
| **DenyList** | List of apps that should not detect root/Magisk |
| **magiskinit** | Replaces `/init` to set up Magisk environment during boot |
| **magiskboot** | Tool to unpack/repack/re-sign boot images |
| **magiskpolicy** | SELinux policy patching tool |

---

## Installation

### Prerequisites

- Android 6.0+ device with unlocked bootloader
- Custom recovery (e.g., TWRP) or PC with platform-tools (adb/fastboot)
- Patched boot image from Magisk Manager or PC

### Installation Methods

#### Method 1: Direct Install (In-App)

1. Open Magisk Manager app
2. Tap **Install** on the Magisk card
3. Select **Direct Install** (Recommended)
4. Tap **Let's Go**
5. Wait for installation to complete
6. Tap **Reboot**

#### Method 2: Patch Boot Image File

1. Extract `boot.img` from your device's stock firmware or OTA
2. Open Magisk Manager
3. Tap **Install** on the Magisk card
4. Select **Select and Patch a File**
5. Choose your `boot.img`
6. Patched file is saved to `Download/magisk_patched-*.img`
7. Flash via fastboot:
   ```
   fastboot flash boot magisk_patched-*.img
   fastboot reboot
   ```

#### Method 3: Custom Recovery

1. Download `Magisk-*.apk` and rename to `Magisk-*.zip`
2. Boot into custom recovery
3. Flash the ZIP file
4. Reboot

#### Method 4: ADB Sideload (Stock Recovery)

```
adb reboot recovery
adb sideload Magisk-*.apk
```

---

## Magisk Manager App UI

### Home Screen

The home screen shows Magisk's current status:

| Item | Description |
|------|-------------|
| **Magisk Version** | Currently installed Magisk version (e.g., v28.0) |
| **Ramdisk** | Whether boot image has ramdisk (Yes/No) |
| **ABI** | Device architecture (arm64-v8a, etc.) |
| **Zygisk** | Whether Zygisk is enabled |
| **DenyList** | Whether DenyList is configured |
| **App Version** | Manager app version |
| **Superuser** | Number of apps with root permissions |
| **Update Buttons** | Check for updates for Magisk and Manager |

### Install Section

- **Direct Install** - Install/upgrade Magisk on current device
- **Select and Patch a File** - Patch a boot image file for manual flashing
- **Install to Inactive Slot** - For OTAs on A/B devices

### Modules Section

- List of installed modules
- Toggle modules on/off
- Install modules from storage
- Module action buttons (run module scripts)

### Superuser Section

- List of apps that have requested root
- Per-app permission management (Allow/Deny/Query)
- View and manage root access logs

### DenyList Section

- List of apps to hide root from
- Search/filter apps
- Toggle system apps visibility
- Toggle OS apps visibility

### Log Section

- **Magisk Log** - Daemon and module logs
- **Superuser Log** - SU access history

### Settings Section

| Setting | Options | Description |
|---------|---------|-------------|
| Update Channel | Stable/Beta/Debug/Custom | Update check channel |
| Dark Theme | System/Light/Dark | UI theme |
| Zygisk | On/Off | Enable Zygisk (requires reboot) |
| DenyList | Configure | Hide root from apps |
| Superuser Access | Disabled/ADB Only/Apps Only/Apps + ADB | Root access scope |
| Mount Namespace | Global/Requester/Isolate | Mount namespace mode for root shells |
| Multiuser Mode | Owner Only/Owner Managed/User | Multi-user SU policy |
| Biometric Authentication | On/Off | Require fingerprint for SU |
| Re-authentication | On/Off | Re-ask for SU permissions |
| Notification | None/Toast/Dialog | SU notification style |
| Timeout | 0/10/20/30/60 sec | SU request timeout |
| Automatic Response | Prompt/Deny/Allow | Default SU response |
| Tapjacking Protection | On/Off | Prevent overlay attacks on SU dialog |
| SafetyNet Notice | On/Off | Show SafetyNet status tips |
| DNS-over-HTTPS | On/Off | Use DoH for update checks |
| Custom Channel URL | Text | Custom update JSON URL |
| Download Path | Path | Custom download directory |
| Randomize Names | On/Off | Randomize package name for hiding |

---

## Superuser Management

### How Root Access Works

1. App or shell executes `su` command
2. `su` binary connects to `magiskd` (magisk daemon) via Unix socket
3. `magiskd` checks its policy database for the requesting app
4. If policy is **Allow** → root shell is spawned
5. If policy is **Deny** → access is rejected
6. If policy is **Query** → notification sent to Manager app for user decision
7. User chooses Allow/Deny, optionally with a timeout
8. Decision is written to FIFO, daemon proceeds accordingly

### Managing Permissions

In **Superuser** section:
- Tap an app to change its policy (Allow/Deny/Query)
- Long-press to see details
- Swipe to delete from list

### SU Policy Options

| Policy | Behavior |
|--------|----------|
| **Allow** | Grant root immediately without asking |
| **Deny** | Reject root immediately without asking |
| **Query** | Ask every time (or after timeout) |
| **Restrict** | Allow but restrict capabilities |
| **Allow with timeout** | Grant for N minutes/hours then ask again |

### Mount Namespace Modes

| Mode | Description |
|------|-------------|
| **Global** | Root shell runs in global mount namespace (can see all mounts) |
| **Requester** | Root shell runs in the calling app's namespace (default) |
| **Isolate** | Root shell runs in a completely isolated namespace |

### Root Logs

The Superuser Log records:
- App requesting root
- Target UID
- Command executed
- Timestamp
- Allow/Deny decision

---

## Modules

### What are Modules?

Magisk modules are ZIP files that contain systemless modifications. They can:
- Replace system files (apps, sounds, fonts, boot animations)
- Add new features or tweaks
- Modify system behavior via scripts
- Provide Zygisk native libraries

### Module Structure

```
module.zip
├── module.prop         # Module metadata
├── system/             # Files overlaid on /system
│   ├── app/
│   ├── etc/
│   ├── framework/
│   ├── lib/
│   └── ...
├── vendor/             # Files overlaid on /vendor
├── product/            # Files overlaid on /product
├── system_ext/         # Files overlaid on /system_ext
├── sepolicy.rule       # SELinux policy rules
├── post-fs-data.sh     # Script run during post-fs-data stage
├── service.sh          # Script run during late_start service stage
├── uninstall.sh        # Script run when module is removed
├── mods.sh             # Mod helper functions
├── update.json         # Online update metadata
├── action.sh           # Script run from module action button
├── zygisk/             # Zygisk native libraries
│   ├── arm64-v8a.so
│   └── armeabi-v7a.so
└── customize.sh        # Install-time customization script
```

### module.prop Format

```
id=my_module
name=My Module
version=1.0
versionCode=1
author=MyName
description=Does awesome things
```

### Installing Modules

1. Download a module ZIP
2. In Magisk Manager, go to **Modules** section
3. Tap **Install from storage**
4. Select the ZIP file
5. Wait for installation
6. Reboot

### Managing Modules

- **Toggle** - Enable/disable via switch
- **Action** - Run module's `action.sh` (if available)
- **Remove** - Uninstall module (runs `uninstall.sh` if available)
- **Update** - Check for online updates via `update.json`

### Module Mounting Process

1. At `post-fs-data` boot stage, `magiskd` scans `/data/adb/modules/*/`
2. For each enabled module, builds a virtual filesystem tree
3. Injects Magisk binaries into PATH
4. Injects Zygisk binaries into /system/lib*
5. Applies bind mounts to overlay module files on real system
6. Executes `post-fs-data.sh` scripts
7. At `late_start` stage, executes `service.sh` scripts

---

## DenyList

### What is DenyList?

DenyList prevents selected apps from detecting Magisk by:
- Unmounting Magisk temp filesystem from the app's mount namespace
- Removing Magisk-related entries from mount info
- Patching logcat to filter Magisk entries

### How DenyList Works

1. App is added to DenyList in Magisk Manager
2. When Zygote forks a new process for the app:
   - Zygisk intercepts `nativeForkAndSpecialize()`
   - Checks if the process name is on DenyList
   - If yes, sets `DENY` flag
   - After fork, calls `revert_unmount()` to remove all Magisk mounts
3. The app process runs without seeing any Magisk traces

### Configuring DenyList

1. Go to **DenyList** section in Magisk Manager
2. Check **Enable DenyList** (requires Zygisk enabled)
3. Search for apps
4. Check the boxes next to apps to hide root from
5. Use settings menu to show system/OS apps

### Recommendations

- Add Google apps (Google Pay, Wallet, etc.)
- Add banking apps
- Add games with root detection
- Do NOT add system UI or launcher apps

---

## Zygisk

### What is Zygisk?

Zygisk is a modified Zygote implementation that:
- Injects code into every app process via native bridge
- Enables module code to run inside app processes
- Provides the DenyList functionality
- Allows Zygisk modules (AdGuard, LSPosed, etc.)

### How Zygisk Works

1. `magiskinit` sets `ro.dalvik.vm.native.bridge=libzygisk.so`
2. When Zygote starts, it loads `libzygisk.so` as the native bridge
3. Zygisk performs PLT hooking on Zygote's loaded libraries
4. JNI hooks replace `nativeForkAndSpecialize()` and related methods
5. When Zygote forks a new app process, Zygisk intercepts the call
6. Zygisk can load module code into the app process
7. If DenyList matches, Magisk mounts are hidden from the process

### Enabling Zygisk

1. Go to **Settings** in Magisk Manager
2. Toggle **Zygisk** ON
3. Reboot device

### Zygisk Modules

Zygisk modules provide native `.so` files that are loaded into app processes:

```
module.zip/zygisk/arm64-v8a.so
module.zip/zygisk/armeabi-v7a.so
```

These implement the Zygisk module API:
- `onLoad(platform_info)` - Called when module is loaded
- `onProcessList(processes)` - Filter app list
- `onAppSpecialize(app_info)` - Called before app process specializes
- `onSystemServerSpecialize(server_info)` - Called for system server

---

## Installation Methods

### Direct Install

Magisk Manager patches the current boot image and flashes it directly. Requires:
- Currently rooted device
- Boot image extraction capability

### Select and Patch a File

Creates a patched boot image file for manual flashing. Use when:
- Device has locked bootloader
- Need to flash via fastboot
- Want to verify patched image before flashing

### Install to Inactive Slot

For A/B partition devices after OTA:
1. Install OTA update (do NOT reboot)
2. Open Magisk Manager
3. Tap **Install to Inactive Slot**
4. Reboot to updated slot with Magisk preserved

### Emulator/AVD Setup

Special mode for Android emulators:
1. In Magisk Manager, go to Settings
2. Enable Zygisk
3. Install
4. Reboot emulator

---

## Command Line Interface

### magisk (Main CLI)

```
Usage: magisk [applet [arguments]...]
   or: magisk [options]...

Options:
  -c                        Print current version info
  -v                        Print daemon version info
  -V                        Print daemon version code
  --list                    Print list of applets
  --remove-modules [-n]     Remove all modules (no reboot with -n)
  --install-module <ZIP>    Install a module ZIP file
  --daemon                  Start magisk daemon
  --stop                    Stop magisk daemon
  --post-fs-data            Post-fs-data stage trigger
  --service                 Late_start service stage trigger
  --boot-complete           Boot complete trigger
  --zygote-restart          Zygote restart callback
  --unlock-blocks           Unlock block device operations
  --restorecon              Restore SELinux contexts
  --clone-attr SRC DEST     Clone file attributes
  --clone SRC DEST          Clone file
  --sqlite SQL              Execute SQLite command
  --path                    Print Magisk tmpfs path
  --denylist ARGS...        DenyList management
  --preinit-device          Print preinit partition device
```

### su (Superuser)

```
Usage: su [options] [-] [user [command [args]...]]

Options:
  -c, --command COMMAND   Pass command to shell
  -h, --help              Show help
  -i, --interactive       Force interactive mode
  -l, --login             Login shell (prefixed with `-`)
  -m, -p, --mount-master  Preserve mount namespace master
  -d, --drop              Drop capabilities
  -s, --shell SHELL       Specify shell binary
  -V                      Print version code
  -v                      Print version string
  -Z, --context CONTEXT   Specify SELinux context
  -M, --mount-master      Skip mount namespace switching
  -t, --target PID        Target process PID for namespace
  -g, --gid GID           Primary group ID
  -G, --ggid GID          Supplementary group IDs
```

### resetprop (Property Manipulation)

```
Usage: resetprop [options] [NAME [VALUE]]

Options:
  -h, --help       Show help
  -v, --verbose    Verbose output
  -w, --watch      Watch property changes
  -p, --file FILE  Load props from file (persistent)
  -d, --delete NAME    Delete property
  --init           Initialize property area
  -Z, --context CTX   Set SELinux context
```

### magiskboot (Boot Image Tool)

```
Usage: magiskboot <action> [args...]

Actions:
  unpack [-n] [-h] <bootimg>    Unpack boot image
  repack [-n] <bootimg> [out]   Repack boot image
  verify <bootimg> [cert]       Verify boot signature
  sign <bootimg> [name] [cert] [key]    Sign boot image
  extract <payload> <partition> [out]   Extract from payload.bin
  hexpatch <file> <from> <to>   Hex patch file
  cpio <cpio> <commands...>     CPIO operations
  dtb <dtb> <action>            DTB operations
  split [-n] <file>             Split file by format
  sha1 <file>                   Compute SHA1 hash
  cleanup                       Cleanup temp files
  compress[=format] <in> [out]  Compress file
  decompress <in> [out]         Decompress file
```

### magiskpolicy (SELinux Policy)

```
Usage: magiskpolicy [options] [statements...]

Options:
  --live                    Apply to live policy
  --load <file>             Load policy from file
  --save <file>             Save policy to file
  --magisk                  Apply Magisk rules
  --compile-split           Compile split CIL policy
  --load-split              Load split CIL policy
  --apply <file>            Apply rules from file
  --print-rules             Print all rules

Statements:
  allow source target class permission
  deny source target class permission
  permissive type
  type type_name
  attribute attr_name
  type_transition source target class default
  genfscon fs_name path context
```

### DenyList Management

```
Usage: magisk --denylist [action [args...]]

Actions:
  status             Print DenyList status
  enable             Enable DenyList
  disable            Disable DenyList
  add pkg [process]  Add package/process to list
  rm pkg [process]   Remove package/process from list
  ls                 List all entries
  clear              Clear all entries
```

---

## Boot Image Patching

### Manual Patching with magiskboot

```
# Unpack boot image
magiskboot unpack boot.img

# Modify ramdisk
magiskboot cpio ramdisk.cpio:
  - add init magiskinit
  - add overlay.d/sbin/magisk.xz
  - add overlay.d/sbin/stub.xz
  - add overlay.d/sbin/init-ld.xz

# Patch device tree (remove verify/encrypt)
magiskboot dtb dtb patch

# Repack boot image
magiskboot repack boot.img new-boot.img
```

### boot_patch.sh Environment Variables

| Variable | Description |
|----------|-------------|
| `KEEPVERITY` | Keep dm-verity (don't disable) |
| `KEEPFORCEENCRYPT` | Keep forced encryption |
| `PATCHVBMETAFLAG` | Patch vbmeta flags |
| `RECOVERYMODE` | Patch recovery image instead of boot |
| `LEGACYSAR` | Force legacy system-as-root mode |

---

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| **Magisk not installed** | Reinstall via Direct Install or re-patch boot image |
| **Zygisk not working** | Ensure Zygisk is enabled in settings, reboot |
| **DenyList not hiding root** | Check Zygisk is enabled, verify app is checked in DenyList |
| **Module not mounting** | Check module structure, disable/enable, reboot |
| **Bootloop after module** | Boot to safe mode (hold volume down) to auto-disable all modules |
| **SU not granting** | Check Superuser settings, clear SU database |
| **App detects root** | Check DenyList, try hiding Magisk Manager (repack) |
| **OTA update lost root** | Install to Inactive Slot before rebooting |

### Safe Mode

If Magisk causes a bootloop:
1. Power off device
2. Hold **Volume Down** button
3. Power on while holding volume down
4. Magisk will detect bootloop and disable all modules automatically
5. Reboot normally, then manage/remove problematic modules

### Hiding Magisk Manager (Stub Mode)

1. Open Magisk Manager
2. Go to Settings
3. Tap **Hide Magisk Manager**
4. App will be repackaged with a random package name
5. Original stub APK (~10KB) is installed to hide the real app

### Log Collection

For debugging:

```
# Collect Magisk logs via app
Log section -> Magisk Log -> Save

# Collect via ADB shell
adb shell
su
magisk --sqlite "SELECT * FROM strings"
dmesg | grep -i magisk
logcat -d | grep -i magisk
```

### Uninstalling Magisk

**Method 1**: Via Magisk Manager
1. Open Magisk Manager
2. Tap **Uninstall Magisk**
3. Choose **Complete Uninstall**

**Method 2**: Manual via ADB
```
adb shell
su
magisk --remove-modules
# Flash stock boot image via fastboot
fastboot flash boot stock_boot.img
fastboot reboot
```

**Method 3**: Flash uninstaller ZIP in custom recovery

---

> **Note**: Magisk requires an unlocked bootloader on most devices. Modifying system files may void warranty. Always backup your data before modifying system partitions.
