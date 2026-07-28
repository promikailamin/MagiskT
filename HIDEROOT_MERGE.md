# HideRoot Merge — Complete Change Log

This document lists every file changed or created to merge the
[HideRoot](https://github.com/snake-4/Zygisk-Assistant) Zygisk module
into the Magisk source tree as a **built-in feature** with an on/off
toggle in the Magisk Manager settings.

---

## Files Created

| # | File | Purpose |
|---|------|---------|
| 1 | `native/src/core/zygisk/hideroot.cpp` | C++ implementation of all hide-root operations (unmount, remount, hide Zygisk, reset properties). Called from the Zygisk context during app specialization. |
| 2 | `native/src/core/zygisk/hideroot.hpp` | Header declaring `exec_hideroot()`. |

## Files Modified

### Rust (native daemon)

| # | File | Change |
|---|------|--------|
| 3 | `native/src/core/lib.rs` | Added `HiderootConfig` to the `DbEntryKey` enum (persistent DB setting). Added `RootHiderEnabled = 0x00000004` to `ZygiskStateFlags` enum (passed from daemon to Zygisk C++ code). Added `hideroot_enabled()` FFI function. |
| 4 | `native/src/core/db.rs` | Added `HiderootConfig => "hideroot"` mapping in `DbEntryKey::to_str()`. Added default value `HiderootConfig => 0` in `get_db_setting()`. |
| 5 | `native/src/core/daemon.rs` | Added `pub hideroot_enabled: AtomicBool` field to `MagiskD` struct. |
| 6 | `native/src/core/bootstages.rs` | In `post_fs_data()`: reads `DbEntryKey::HiderootConfig` from the DB and stores it in `self.hideroot_enabled`. |
| 7 | `native/src/core/zygisk/daemon.rs` | Added `hideroot_enabled()` method to the FFI `impl MagiskD` block. In `get_process_info()`: if `hideroot_enabled` is true, sets `ZygiskStateFlags::RootHiderEnabled` in the response flags. |

### C++ (native Zygisk)

| # | File | Change |
|---|------|--------|
| 8 | `native/src/core/zygisk/module.cpp` | Added `#include "hideroot.hpp"`. In `app_specialize_pre()`: after the denylist check, if `RootHiderEnabled` is set and `ProcessGrantedRoot` is NOT set, calls `exec_hideroot()`. |
| 9 | `native/src/Android.mk` | Added `core/zygisk/hideroot.cpp` to the `magisk` module's `LOCAL_SRC_FILES`. |

### Kotlin (Android app)

| # | File | Change |
|---|------|--------|
| 10 | `app/core/src/main/java/pro/magisk/core/Config.kt` | Added `HIDEROOT = "hideroot"` key and `var hideroot by dbSettings(...)` property (default: `false`). |
| 11 | `app/core/src/main/res/values/resources.xml` | Added `<string name="hideroot" translatable="false">Hide Root</string>`. |
| 12 | `app/core/src/main/res/values/strings.xml` | Added `<string name="settings_hideroot_summary">Hide Magisk, Zygisk, and modules from apps through mount namespace isolation</string>`. |
| 13 | `app/apk/src/main/java/pro/magisk/ui/settings/SettingsItems.kt` | Added `object Hideroot : BaseSettingsItem.Toggle()` bound to `Config.hideroot`. |
| 14 | `app/apk/src/main/java/pro/magisk/ui/settings/SettingsViewModel.kt` | Added `Hideroot` to the settings list in `createItems()` (inside the `Version.atLeast_24_0()` block). |
| 15 | `app/apkT/src/main/java/pro/magisk/ui/settings/SettingsScreen.kt` | Added `SettingsSwitch` for hideroot between Zygisk and DenyList switches. |

---

## What the "Hide Root" Feature Does

When enabled, `exec_hideroot()` runs inside every forked app process
(except those granted root access) and performs four operations:

1. **`doUnmount()`** — Unmounts all filesystems rooted at `/data/adb` or
   `/debug_ramdisk`, plus any `overlay` or `tmpfs` whose source name is
   `KSU`, `APatch`, `magisk`, or `worker` (the common root-hiding mount
   names used by all major root solutions).

2. **`doRemount()`** — Remounts `/data` with the correct `errors=`
   behaviour from the ext4 superblock, covering up a common detection
   heuristic.

3. **`doHideZygisk()`** — Resets the `had_error` flag in
   `libnativebridge.so`'s `.bss` section, hiding the fact that a Zygisk
   native bridge was ever loaded.

4. **`doMrProp()`** — Resets the serial numbers on every `ro.*` system
   property whose value has been modified, undoing a common detection
   method that scans for tampered read-only properties.

Each of these runs inside a **fresh mount namespace** (created via
`unshare(CLONE_NEWNS)`) so no side-effects leak into other processes.

---

## How to Revert (Full Uninstall)

To completely undo this merge, reverse each change above:

```bash
# 1. Delete created files
rm native/src/core/zygisk/hideroot.cpp
rm native/src/core/zygisk/hideroot.hpp

# 2. Revert native/src/core/lib.rs (DbEntryKey + ZygiskStateFlags + FFI)
git checkout native/src/core/lib.rs

# 3. Revert native/src/core/db.rs
git checkout native/src/core/db.rs

# 4. Revert native/src/core/daemon.rs
git checkout native/src/core/daemon.rs

# 5. Revert native/src/core/bootstages.rs
git checkout native/src/core/bootstages.rs

# 6. Revert native/src/core/zygisk/daemon.rs
git checkout native/src/core/zygisk/daemon.rs

# 7. Revert native/src/core/zygisk/module.cpp
git checkout native/src/core/zygisk/module.cpp

# 8. Revert native/src/Android.mk
git checkout native/src/Android.mk

# 9. Revert app/ files
git checkout app/core/src/main/java/pro/magisk/core/Config.kt
git checkout app/core/src/main/res/values/resources.xml
git checkout app/core/src/main/res/values/strings.xml
git checkout app/apk/src/main/java/pro/magisk/ui/settings/SettingsItems.kt
git checkout app/apk/src/main/java/pro/magisk/ui/settings/SettingsViewModel.kt
git checkout app/apkT/src/main/java/pro/magisk/ui/settings/SettingsScreen.kt
```

Or, if you haven't made other changes, simply:

```bash
git diff --name-only  # confirm only merge files are modified
git checkout -- .     # discard everything
```

---

## Build Verification

Both native (`python build.py native`) and app (`python build.py app`,
`python build.py -t app`) compile without errors. The setting appears in
both the DataBinding (``:apk``) and Jetpack Compose (``:apkT``) app
variants.
