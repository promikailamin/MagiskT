# Termux PATH/LD_LIBRARY_PATH injection for root shell

## File to patch

`native/src/core/su/su.cpp` — function `exec_root_shell()`

## Location

After the environment setup block (line ~444), before `// Config privileges` (line ~462).

## Constants defined

`native/src/include/consts.hpp` (line ~19-21):

```cpp
#define TERMUX_PREFIX   "/data/data/com.termux/files/usr"
#define TERMUX_BIN      TERMUX_PREFIX "/bin"
#define TERMUX_LIB      TERMUX_PREFIX "/lib"
```

## Patch

`native/src/core/su/su.cpp`, inside `exec_root_shell()`, after the `!req.keep_env` block:

```cpp
if (req.target_uid == AID_ROOT) {
    if (access(TERMUX_BIN, F_OK) == 0) {
        char new_path[4096];
        const char *opath = getenv("PATH");
        ssprintf(new_path, sizeof(new_path), TERMUX_BIN ":%s",
                opath ? opath : "");
        setenv("PATH", new_path, 1);
    }
    if (access(TERMUX_LIB, F_OK) == 0) {
        char new_ld_path[4096];
        const char *old_ld = getenv("LD_LIBRARY_PATH");
        ssprintf(new_ld_path, sizeof(new_ld_path), TERMUX_LIB ":%s",
                old_ld ? old_ld : "");
        setenv("LD_LIBRARY_PATH", new_ld_path, 1);
    }
}
```

## What it does

- Only applies when `su` targets **root** (`target_uid == AID_ROOT`), not `su shell` or other UIDs.
- Checks if `/data/data/com.termux/files/usr/bin` exists → prepends it to `PATH`.
- Checks if `/data/data/com.termux/files/usr/lib` exists → prepends it to `LD_LIBRARY_PATH`.
- Runs **after** the calling process's environ is loaded (lines 426–434) but **before** privilege dropping and `execvp()`.
- Is **independent** of `--preserve-environment` (`keep_env`), so Termux paths are always available in root shells.

## Why this location

`exec_root_shell()` (line ~395–480) is the **only** place where the su shell environment is assembled:

1. Lines 426–434: Reads `/proc/<pid>/environ` from the calling process, populates env with `putenv`.
2. Lines 435–444: If `!keep_env`, overwrites `HOME`, `USER`, `LOGNAME`, `SHELL` from passwd.
3. **→ Patch goes here ←** (after general env, only for root).
4. Lines 462+: Configures SELinux context, drops caps, sets identity, unblocks signals, `execvp()`.

No other file in the native code needs modification.
