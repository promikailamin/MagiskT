# Hide Root — Implementation Guide

## Concept

Hide Root automatically runs whenever **DenyList enforcement is ON**.
There is no separate toggle — enabling Enforce DenyList also enables root hiding.
The Enforce DenyList toggle itself requires a reboot (like Zygisk).

---

## What it does

Inside every denylisted app process (via Zygisk hook `app_specialize_pre`):

1. Unshares mount namespace
2. Unmounts `/data/adb` and `/debug_ramdisk` mounts (Magisk, KSU, APatch, worker)
3. Remounts `/data` with correct `errors=` from ext4 superblock
4. Resets `libnativebridge.so` `had_error` flag
5. Resets all `ro.*` system properties to original values

---

## Files

### Created

| File | Purpose |
|------|---------|
| `native/src/core/zygisk/hideroot.cpp` | All hiding logic |
| `native/src/core/zygisk/hideroot.hpp` | Header: `void exec_hideroot()` |

### Modified

| # | File | Change |
|---|------|--------|
| 1 | `native/src/Android.mk` | Add `core/zygisk/hideroot.cpp` to `LOCAL_SRC_FILES` |
| 2 | `native/src/core/zygisk/module.cpp` | `#include "hideroot.hpp"`; call `exec_hideroot()` inside UNMOUNT_MASK check; set `DENYLIST_ENFORCED` env var for manager |
| 3 | `native/src/core/zygisk/daemon.rs` | Set `RootHiderEnabled` flag when `DenyListEnforced` is set |
| 4 | `native/src/core/lib.rs` | Add `RootHiderEnabled = 0x00000004` to `ZygiskStateFlags` |
| 5 | `app/core/.../Config.kt` | (No hideroot key — uses `denyList`) |
| 6 | `app/core/.../Info.kt` | Add `isDenylistEnforced` from `DENYLIST_ENFORCED` env var |
| 7 | `app/apk/.../SettingsItems.kt` | DenyList toggle: save to DB only, add mismatch + reboot message |
| 8 | `app/apk/.../SettingsViewModel.kt` | Handle DenyList mismatch snackbar |
| 9 | `app/apkT/.../SettingsScreen.kt` | DenyList toggle: require reboot, mismatch check |
| 10 | `app/apkT/.../SettingsViewModel.kt` | `denylistMismatch`, `toggleDenyList` saves to DB only |

---

## Data Flow

```
User toggles Enforce DenyList ON
        │
        ▼
Config.denyList = true  (saves to magisk.db: settings.denylist = 1)
        │
  [reboot required — shown in UI]
        │
        ▼
Daemon boot: initialize_denylist()
  reads denylist from DB → denylist_enforced = true (C++ atomic)
        │
        ▼
Zygisk: get_process_info() for launched app
  update_deny_flags() → sets ProcessOnDenyList + DenyListEnforced
  DenyListEnforced → sets RootHiderEnabled automatically
        │
        ▼
module.cpp: app_specialize_pre()
  (info_flags & UNMOUNT_MASK) == UNMOUNT_MASK  → denylisted
  info_flags & RootHiderEnabled                → auto-set from DenyListEnforced
  → exec_hideroot()
```

---

## Step-by-step for a fresh fork

### Step 1 — Create `hideroot.hpp`

```cpp
#pragma once
void exec_hideroot();
```

### Step 2 — Create `hideroot.cpp`

Full 263-line implementation (see source). Key functions:

| Function | What it does |
|----------|-------------|
| `parse_mountinfo()` | Parse `/proc/self/mountinfo` |
| `doUnmount()` | Unmount `/data/adb`/`/debug_ramdisk` mounts, KSU/APatch/magisk/worker overlays |
| `doRemount()` | Fix `/data` `errors=` from ext4 superblock |
| `doHideZygisk()` | Reset `libnativebridge.so` `had_error` byte |
| `doMrProp()` | Reset all `ro.*` properties |
| `exec_hideroot()` | Entry: `unshare(CLONE_NEWNS)`, `MS_SLAVE`, fork child to run all above |

### Step 3 — `Android.mk`

Add to `LOCAL_SRC_FILES`:

```
    core/zygisk/hideroot.cpp \
```

### Step 4 — `module.cpp`

Add include:

```cpp
#include "hideroot.hpp"
```

In `app_specialize_pre()`, add inside the UNMOUNT_MASK block:

```cpp
if (info_flags & +ZygiskStateFlags::RootHiderEnabled) {
    exec_hideroot();
}
```

In `app_specialize_post()`, set env var:

```cpp
if (info_flags & +ZygiskStateFlags::DenyListEnforced) {
    setenv("DENYLIST_ENFORCED", "1", 1);
}
```

### Step 5 — `daemon.rs`

In `get_process_info()`, auto-enable root hider:

```rust
if flags & ZygiskStateFlags::DenyListEnforced.repr != 0 {
    flags |= ZygiskStateFlags::RootHiderEnabled.repr
}
```

### Step 6 — `lib.rs`

Add to `ZygiskStateFlags`:

```rust
RootHiderEnabled = 0x00000004,
```

### Step 7 — `Info.kt`

```kotlin
@JvmField val isDenylistEnforced = System.getenv("DENYLIST_ENFORCED") == "1"
```

### Step 8 — Settings UI (apk `SettingsItems.kt`)

Toggle saves to DB only (no `magisk --denylist`), shows reboot message on mismatch:

```kotlin
object DenyList : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_denylist_title.asText()
    override val description get() =
        if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_denylist_summary.asText()
    override var value
        get() = Config.denyList
        set(value) {
            Config.denyList = value
            notifyPropertyChanged(BR.description)
        }
    val mismatch get() = value != Info.isDenylistEnforced
}
```

### Step 9 — Settings UI (apkT `SettingsScreen.kt`)

```kotlin
val denyListEnabled by viewModel.denyListEnabled.collectAsState()
SettingsSwitch(
    title = stringResource(CoreR.string.settings_denylist_title),
    summary = stringResource(
        if (denyListEnabled != Info.isDenylistEnforced) CoreR.string.reboot_apply_change
        else CoreR.string.settings_denylist_summary
    ),
    checked = denyListEnabled,
    onCheckedChange = { viewModel.toggleDenyList(it) }
)
```

### Step 10 — apkT `SettingsViewModel.kt`

```kotlin
val denylistMismatch get() = Config.denyList != Info.isDenylistEnforced

fun toggleDenyList(enabled: Boolean) {
    _denyListEnabled.value = enabled
    Config.denyList = enabled
    if (denylistMismatch) showSnackbar(R.string.reboot_apply_change)
}
```

---

## Requirements at runtime

Hide Root runs when **all** of these are true:

1. **Zygisk enabled** → reboot
2. **Enforce DenyList ON** → reboot (saved to DB)
3. **App on DenyList** (Configure DenyList)
4. **Reboot** after toggling Enforce DenyList
