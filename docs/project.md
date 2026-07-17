# Magisk - Complete Code Explanation

This document provides an in-depth explanation of every major source file, covering data structures, function signatures, control flow, and how each piece fits together. Use this alongside the workflow diagram for a complete picture.

---

## 1. NATIVE CODE (Rust + C++)

### 1.1 `native/src/core/applets.cpp` — Main Entry Point

**Purpose:** The single entry point for all native binaries (magisk, su, resetprop, zygisk).

**Key Data Structures:**
```cpp
struct Applet {
    string_view name;
    int (*fn)(int, char *[]);
};

// Public applets (accessible via symlink)
constexpr Applet applets[] = {
    { "su", su_client_main },
    { "resetprop", resetprop_main },
};

// Private applets (accessible only via magisk <name>)
constexpr Applet private_applets[] = {
    { "zygisk", zygisk_main },
};
```

**Functions:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `main` | `int main(int argc, char *argv[])` | Dispatches to correct applet based on argv[0] |

**Control Flow:**
1. `cmdline_logging()` — Initialize logging
2. `init_argv0(argc, argv)` — Extract binary name from path
3. `umask(0)` — Allow all file permissions
4. If argv[0] is empty → iterate `private_applets[]`
5. If argv[0] is "magisk"/"magisk32"/"magisk64" → call `magisk_main()`
6. If argv[0] matches an applet → call that function
7. Otherwise → error "applet not found"

**How it links:**
- `applets.cpp` is the sole source listed in `Android.mk` for the `magisk` module
- All other `.cpp` files (su.cpp, zygisk/entry.cpp, deny/cli.cpp, etc.) are compiled into the same binary
- Rust functions are linked via static libraries (`libmagisk-rs.a`)

---

### 1.2 `native/src/core/magisk.rs` — CLI Command Parser

**Purpose:** Parses and dispatches all `magisk` CLI commands. Uses the `argh` crate for argument parsing.

**Key Data Structure:**
```rust
#[derive(FromArgs)]
struct Cli {
    #[argh(subcommand)]
    action: MagiskAction,
}

#[argh(subcommand)]
enum MagiskAction {
    LocalVersion(LocalVersion),    // -c: local version
    Version(Version),              // -v: daemon version
    VersionCode(VersionCode),      // -V: daemon version code
    List(ListApplets),             // --list: list applets
    RemoveModules(RemoveModules),  // --remove-modules [-n]
    InstallModule(InstallModule),  // --install-module <ZIP>
    Daemon(StartDaemon),           // --daemon
    Stop(StopDaemon),              // --stop
    PostFsData(PostFsData),        // --post-fs-data
    Service(ServiceCmd),           // --service (late_start)
    BootComplete(BootComplete),    // --boot-complete
    ZygoteRestart(ZygoteRestart),  // --zygote-restart
    UnlockBlocks(UnlockBlocks),    // --unlock-blocks
    RestoreCon(RestoreCon),        // --restorecon
    CloneAttr(CloneAttr),          // --clone-attr SRC DEST
    CloneFile(CloneFile),          // --clone SRC DEST
    Sqlite(Sqlite),                // --sqlite SQL
    Path(PathCmd),                 // --path
    DenyList(DenyList),            // --denylist ARGS
    PreInitDevice(PreInitDevice),  // --preinit-device
}
```

**Key Functions:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `magisk_main` | `pub fn magisk_main(argc: i32, argv: *mut *mut c_char) -> i32` | CLI entry point |
| `exec` (on each variant) | Returns `LoggedResult<i32>` | Execute the command |

**Control Flow:**
1. `magisk_main()` converts C args to `CmdArgs`, inserts `"--"`, parses via `Cli::from_args()`
2. Calls `action.exec()` which matches the variant
3. Each variant either:
   - Prints info directly (LocalVersion, List)
   - Connects to daemon via `connect_daemon(code, create)` and reads response (Version, VersionCode, etc.)
   - Calls C++ FFI functions (InstallModule → `install_module()`, UnlockBlocks → `unlock_blocks()`)
   - Calls other Rust modules (DenyList → `denylist_cli()`, Sqlite → daemon handler)

**How it links:**
- `magisk.rs` is part of the `magisk` crate (libmagisk-rs.a)
- Uses `connect_daemon()` from `daemon.rs` for IPC
- Uses FFI to call C++ functions from `base.cpp`, `utils.cpp`
- C++ code calls back into Rust via CXX bridges

---

### 1.3 `native/src/core/daemon.rs` — The Magisk Daemon

**Purpose:** The core daemon (`magiskd`) that runs as a background process, managing a Unix socket server for all IPC.

**Key Data Structure:**
```rust
pub static MAGISKD: OnceLock<MagiskD> = OnceLock::new();

pub struct MagiskD {
    pub sql_connection: Mutex<Option<Sqlite3>>,     // SQLite DB connection
    pub manager_info: Mutex<ManagerInfo>,            // Manager APK info
    pub boot_stage_lock: Mutex<BootState>,           // Boot stage tracking
    pub module_list: OnceLock<Vec<ModuleInfo>>,       // Loaded modules
    pub zygisk_enabled: AtomicBool,                  // Zygisk state
    pub zygisk: Mutex<ZygiskState>,                  // Zygisk companion sockets
    pub cached_su_info: AtomicArc<SuInfo>,            // SU info cache
    pub sdk_int: i32,                                 // Android SDK version
    pub is_emulator: bool,                            // Emulator detection
    is_recovery: bool,                                // Recovery mode flag
    exe_attr: FileAttr,                               // Self executable attributes
}
```

**Key Functions:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `daemon_entry` | `pub fn daemon_entry()` | Bootstrap the daemon (setsid, socket bind, main loop) |
| `connect_daemon` | `pub fn connect_daemon(code: RequestCode, create: bool) -> LoggedResult<UnixStream>` | Client-side: connect to daemon, optionally start it |
| `handle_requests` | `fn handle_requests(&'static self, client: UnixStream)` | Main dispatch loop for client connections |
| `handle_request_sync` | `fn handle_request_sync(...)` | Handle synchronous requests (version, start/stop) |
| `handle_request_async` | `fn handle_request_async(...)` | Handle async requests (SU, denylist, sqlite, zygisk) |
| `boot_stage_handler` | `fn boot_stage_handler(...)` | Handle boot stage requests (post-fs-data, late_start, boot_complete) |

**Request Codes (enum):**
```rust
pub enum RequestCode {
    CHECK_VERSION,      // Query daemon version
    START_DAEMON,       // Start daemon
    STOP_DAEMON,        // Stop daemon
    POST_FS_DATA,       // Boot stage 1
    LATE_START,         // Boot stage 2
    BOOT_COMPLETE,      // Boot stage 3
    SUPERUSER,          // SU request
    SQLITE_CMD,         // Database command
    DENYLIST,           // DenyList management
    ZYGOTE_RESTART,     // Zygote restart notification
    REMOVE_MODULES,     // Remove all modules
    ZYGISK,             // Zygisk companion comm
    CHECK_VERSION_CODE, // Query version code
}
```

**Control Flow (daemon_entry):**
1. Sets nice name to "magiskd"
2. Enables android logging, blocks signals
3. Swaps stdio to /dev/null, becomes session leader (`setsid()`)
4. Writes SELinux context to `/proc/self/attr/current`
5. Starts log daemon, detects emulator & SDK version
6. Escapes cgroups, cleans up pre-init mounts
7. Creates Unix socket at `$MAGISK_TMP/$MAIN_SOCKET`
8. Main loop: `for client in sock.incoming() { self.handle_requests(client) }`

**Control Flow (handle_requests):**
1. Gets peer credentials and SELinux context
2. Permission check: root/shell/zygote required based on code
3. Reads request code, validates range
4. Writes OK respond code
5. Dispatches:
   - Sync requests → handled immediately in thread
   - Async requests → sent to thread pool
   - Boot stage requests → sent to thread pool (serialized by boot_stage_lock)

**How it links:**
- Called from `magisk.rs` via `Daemon` variant → `connect_daemon(RequestCode::START_DAEMON)`
- Calls into `bootstages.rs` for boot stage handling
- Calls into `su/daemon.rs` for SU request handling
- Calls into `module.rs` for module mounting
- Calls into `deny/` for DenyList operations
- Calls into `zygisk/` for Zygisk companion communication
- Uses `socket.rs` for Encodable/Decodable serialization
- Uses `thread.rs` for thread pool management

---

### 1.4 `native/src/core/bootstages.rs` — Boot Lifecycle

**Purpose:** Manages the three boot stages that Magisk services execute.

**Key Data Structure:**
```rust
bitflags! {
    pub struct BootState : u32 {
        const PostFsDataDone = 1 << 0;
        const LateStartDone  = 1 << 1;
        const BootComplete   = 1 << 2;
        const SafeMode       = 1 << 3;
    }
}
```

**Key Functions on MagiskD:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `setup_magisk_env` | `fn setup_magisk_env(&self) -> bool` | Copy binaries to DATABIN, set up tmpfs |
| `post_fs_data` | `fn post_fs_data(&self) -> bool` | Post-fs-data stage: setup env, modules, scripts |
| `late_start` | `fn late_start(&self)` | Service stage: execute service scripts |
| `boot_complete` | `fn boot_complete(&self)` | Boot complete: reset counter, check manager |
| `boot_stage_handler` | `fn boot_stage_handler(&self, client, code)` | Serialized boot stage dispatcher |

**Control Flow (post_fs_data):**
1. Sets up log file
2. Preserves stub APK
3. Checks/creates secure directory
4. Prunes su access database
5. `setup_magisk_env()` — copies binaries, busybox
6. Safe mode check: bootloop counter >= 2 or volume key pressed → disable modules
7. `exec_common_scripts("post-fs-data")` — run scripts in `/data/adb/post-fs-data.d/`
8. Stores zygisk enabled state from DB
9. `initialize_denylist()` — set up DenyList
10. `self.handle_modules()` — mount all modules (see module.rs)
11. `clean_mounts()` — clean up stale mounts
12. Returns false (no abort)

**Control Flow (late_start):**
1. Sets up log file
2. `exec_common_scripts("service")` — run `/data/adb/service.d/` scripts
3. `exec_module_scripts("service")` — run each module's `service.sh`

**Control Flow (boot_complete):**
1. Resets bootloop counter in DB
2. `setup_preinit_dir()` — prepare preinit device directory
3. `ensure_manager()` — verify manager APK is installed
4. Resets zygisk state if enabled

**How it links:**
- Called from `daemon.rs` `boot_stage_handler` when boot stage request codes arrive
- `post_fs_data` calls `handle_modules()` in `module.rs`
- `setup_magisk_env` calls C++ functions for file operations
- Uses `db.rs` for reading/writing settings
- Uses `package.rs` for manager APK detection

---

### 1.5 `native/src/core/module.rs` — Module Mounting System

**Purpose:** The most complex component — builds a virtual filesystem tree from module directories and applies bind mounts.

**Key Data Structures:**
```rust
enum FsNode {
    Directory { children: FsNodeMap },   // Directory node with children
    File { src: Utf8CString },           // File to bind mount
    Symlink { target: Utf8CString },     // Symbolic link
    MagiskLink,                          // Special symlink to ./magisk or ./magiskpolicy
    Whiteout,                            // File removal marker
}

struct ModuleInfo {
    name: String,            // Module directory name
    zgisk_fds: Vec<RawFd>,  // Zygisk file descriptors
    // ... other fields
}
```

**Key Functions:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `bind_mount` | `fn bind_mount(reason, src, dest, rec)` | Core mount helper |
| `upgrade_modules` | `fn upgrade_modules() -> LoggedResult<()>` | Process pending module upgrades |
| `for_each_module` | `fn for_each_module(func)` | Iterate modules in MODULEROOT |
| `disable_modules` | `pub fn disable_modules()` | Create disable flag in all modules |
| `remove_modules` | `pub fn remove_modules()` | Remove all modules (run uninstall.sh) |
| `collect_modules` | `fn collect_modules(...) -> Vec<ModuleInfo>` | Scan and collect module info |
| `inject_magisk_bins` | `fn inject_magisk_bins(system, is_emulator)` | Inject magisk/su/resetprop into system tree |
| `inject_zygisk_bins` | `fn inject_zygisk_bins(name, system)` | Inject Zygisk libs into system tree |
| `handle_modules` | `impl MagiskD::handle_modules(&self)` | Main module handler |
| `apply_modules` | `impl MagiskD::apply_modules(&self, module_list)` | 4-step module application |

**FsNode Methods:**

| Method | Purpose |
|--------|---------|
| `collect(paths)` | Reads module directory, builds FsNode tree |
| `commit(paths)` | Commits virtual tree to real filesystem via bind mounts |
| `commit_tmpfs(paths)` | Creates tmpfs mirror directory for complex replacements |
| `parent_should_be_tmpfs(path)` | Determines if parent directory needs tmpfs overlay |

**Control Flow (handle_modules → 4-step apply_modules):**

Step 1 — Build virtual filesystem tree:
- For each active module:
  - Read module.prop
  - Load system.prop
  - Check skip_mount flag
  - Walk system/ directory → build FsNode tree

Step 2 — Inject custom files:
- Call `inject_magisk_bins()` to add magisk, su, resetprop
- Call `inject_zygisk_bins()` to add Zygisk .so files

Step 3 — Extract secondary partitions:
- Split /vendor, /product, /system_ext from main /system tree

Step 4 — Convert to mount operations:
- For each partition root:
  - Call `root.commit(paths, true)`
  - Creates tmpfs mirror if needed
  - Applies bind mounts for file replacements

**How it links:**
- Called from `bootstages.rs` `post_fs_data()` → `self.handle_modules()`
- Calls C++ mount functions via FFI
- Reads module directories from `/data/adb/modules/`
- Writes to `$MAGISK_TMP/.magisk/modules/` for the virtual mount tree
- Module info is stored in `MagiskD.module_list` for later use

---

### 1.6 `native/src/core/su/su.cpp` — SU Client

**Purpose:** The `su` command client that parses arguments, connects to the daemon, and manages the PTY/shell.

**Key Constants:**
```cpp
#define DEFAULT_SHELL "/system/bin/sh"
#define ATTY_IN    (1 << 0)   // Stdin is a TTY
#define ATTY_OUT   (1 << 1)   // Stdout is a TTY
#define ATTY_ERR   (1 << 2)   // Stderr is a TTY
```

**Key Functions:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `su_client_main` | `int su_client_main(int argc, char *argv[])` | SU client entry |
| `exec_root_shell` | `void exec_root_shell(int client, int pid, SuRequest &req, MntNsMode mode)` | Execute root shell (called from Rust) |

**Control Flow (su_client_main):**
1. Create `SuRequest` with defaults
2. Parse options with `getopt_long`:
   - `-c CMD` — set command to execute
   - `-s SHELL` — set shell binary
   - `-t PID` — set target PID for namespace
   - `-Z CONTEXT` — set SELinux context
   - `-g GID` — set primary group
   - `-G GIDS` — set supplementary groups
   - `-M` — mount master mode
   - `-m`/`-p` — preserve environment
   - `-l` — login shell
   - `-i` — interactive mode
   - `-d` — drop capabilities
3. Connect to daemon via `connect_daemon(SUPERUSER)`
4. Write `SuRequest` to daemon socket
5. Read ack — if non-zero, access denied
6. Determine TTY state
7. Send stdin/stdout/stderr FDs to daemon
8. If interactive: receive PTY master fd, set up sighandlers
9. Pump PTY data between local TTY and remote PTY
10. Read and return exit code from daemon

**Control Flow (exec_root_shell):**
1. Become session leader (`setsid()`)
2. Receive stdin/stdout/stderr FDs from client
3. If PTY mode: create/use PTY, send PTY master back to client
4. Dup2 FDs to stdin/stdout/stderr
5. Handle mount namespace:
   - Global: no ns switch
   - Requester: `switch_mnt_ns(target_pid)`
   - Isolate: `switch_mnt_ns` + `unshare(CLONE_NEWNS)` + private remount
6. Set up argv, environment (HOME, USER, LOGIN, SHELL)
7. Set SELinux context if specified
8. Drop capabilities if requested or restricted
9. Set identity (UID/GID) if non-root
10. `execvp(shell, argv)`

**How it links:**
- Entry point from `applets.cpp` via the `applets[]` table
- Calls `connect_daemon()` from `daemon.rs` (via CXX bridge)
- `exec_root_shell()` is called from `su/daemon.rs` via CXX FFI
- Uses `base.cpp` utilities: `switch_mnt_ns()`, `fork()`, etc.

---

### 1.7 `native/src/core/su/daemon.rs` — SU Daemon Handler

**Purpose:** Server-side SU authorization logic — checks permissions, communicates with manager app, forks root shell.

**Key Data Structure:**
```rust
pub struct SuInfo {
    pub uid: i32,
    pub eval_uid: i32,
    pub mgr_pkg: String,
    pub mgr_uid: i32,
    cfg: DbSettings,
    access: Mutex<AccessInfo>,
}
```

**Key Functions on MagiskD:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `su_daemon_handler` | `pub fn su_daemon_handler(&self, client, cred)` | Handle SU request |
| `get_su_info` | `fn get_su_info(&self, uid) -> Arc<SuInfo>` | Get cached/current SU info |
| `build_su_info` | `fn build_su_info(&self, uid) -> Arc<SuInfo>` | Build SU info from DB |

**Control Flow (su_daemon_handler):**
1. Read `SuRequest` from client
2. `get_su_info(cred.uid)` — fetch cached or build new SuInfo
3. Lock access info, create `SuAppContext`
4. Call `app.connect_app()` — if policy is Query, show dialog to user via manager app
5. Refresh timestamp cache
6. If Restrict policy: set drop_cap = true
7. If Deny policy: write Deny response, return
8. Fork child:
   - Child: `exec_root_shell(client, pid, req, mode)` (C++ FFI)
   - Parent: `waitpid(child)`, write exit code to client

**Control Flow (build_su_info):**
1. If AID_ROOT → return allow-all info
2. Get DB settings
3. Apply multiuser filtering (OwnerOnly, OwnerManaged)
4. Get root settings from DB
5. If caller is manager itself → allow silently
6. Apply root access policy (Disabled, AdbOnly, AppsOnly)
7. If policy still Query and no manager → deny
8. Return SuInfo

**How it links:**
- Called from `daemon.rs` `handle_request_async` when code is `SUPERUSER`
- Calls `connect.rs` functions to communicate with manager app
- Calls `exec_root_shell()` (C++ from su.cpp) via FFI
- Uses `db.rs` for policy database queries

---

### 1.8 `native/src/core/su/connect.rs` — SU Manager IPC

**Purpose:** Communicates with the Magisk Manager app for SU policy decisions, logging, and notifications.

**Key Functions on SuAppContext:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `exec_cmd` | `fn exec_cmd(&self, action, extras, use_provider)` | Execute app_process to contact manager |
| `app_request` | `fn app_request(&mut self)` | Show SU dialog to user via FIFO |
| `app_notify` | `fn app_notify(&self)` | Send notification to manager |
| `app_log` | `fn app_log(&self)` | Send log entry to manager |
| `connect_app` | `pub(super) fn connect_app(&mut self)` | Main entry for manager IPC |

**Control Flow (app_request):**
1. Create FIFO at `$MAGISK_TMP/.magisk/su_request_{PID}`
2. Set FIFO owner to manager UID
3. Call `exec_cmd("request", ...)` to start manager activity via `am start` or content provider
4. Open FIFO for reading (O_RDWR to prevent blocking)
5. Poll with 70-second timeout
6. Read policy decision (big-endian i32)
7. Return Allow/Deny

**Control Flow (connect_app):**
1. If policy is Query → call `app_request()` to show dialog
2. If logging or notification needed:
   - Fork child (fire-and-forget via `fork_dont_care()`)
   - Child: `app_log()` or `app_notify()`, then exit

**How it links:**
- Called from `su/daemon.rs` `su_daemon_handler()`
- Uses `am` (ActivityManager) shell command to launch manager intents
- Uses content provider URIs for newer Android versions
- FIFO path is shared with the Java app's `SuRequestHandler.kt`

---

### 1.9 `native/src/core/zygisk/entry.cpp` — Zygisk Native Bridge Entry

**Purpose:** Exposes the `NativeBridgeItf` struct that Android's Zygote loads as a native bridge.

**Key Data:**
```cpp
extern "C" [[maybe_unused]] NativeBridgeCallbacks NativeBridgeItf {
    .version = 2,
    .isCompatibleWith = [](auto) {
        zygisk_logging();
        hook_entry();          // Enter hook.cpp
        ZLOGD("load success\n");
        return false;          // CRITICAL: tell system bridge is incompatible
    },
};
```

**Key Functions:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `zygisk_main` | `int zygisk_main(int argc, char *argv[])` | Zygisk applet entry |
| `zygiskd` | `static void zygiskd(int socket)` | Companion daemon loop |
| `NativeBridgeItf` | `extern "C" NativeBridgeCallbacks` | Native bridge interface struct |

**Control Flow (zygisk_main):**
1. If argv == "companion": call `zygiskd(parse_int(socket_fd))`

**Control Flow (zygiskd):**
1. Validate root uid and socket
2. Set nice name ("zygiskd64" or "zygiskd32")
3. Receive module .so FDs via `recv_fds()`
4. `android_dlopen_ext()` each FD → look up `zygisk_companion_entry`
5. Store companion entries in vector
6. Send ack (0)
7. Main loop: poll socket → receive client FD → read module_id → call companion entry

**Control Flow (NativeBridgeItf.isCompatibleWith):**
1. `zygisk_logging()` — set up Zygisk logging
2. `hook_entry()` — enter PLT hook initialization (hook.cpp)
3. Return false — causes system to dlclose this library, triggering the dlclose hook

**How it links:**
- Exposed as a global symbol for Android's native bridge loader
- Calls into `hook.cpp` for PLT/JNI hook setup
- `zygiskd()` is the companion daemon spawned by `daemon.rs`
- Companions are module-provided functions for persistent background work

---

### 1.10 `native/src/core/zygisk/hook.cpp` — Zygisk PLT & JNI Hooks

**Purpose:** The core of Zygisk — hooks PLT functions in Zygote and replaces JNI methods.

**Key Data Structures:**
```cpp
struct HookContext : JniHookDefinitions {
    vector<tuple<dev_t, ino_t, const char *, void **>> plt_backup;
    const NativeBridgeRuntimeCallbacks *runtime_callbacks = nullptr;
    void *self_handle = nullptr;
    bool should_unmap = false;

    void hook_plt();
    void hook_unloader();
    void restore_plt_hook();
    void hook_zygote_jni();
    void restore_zygote_hook(JNIEnv *env);
    void post_native_bridge_load(void *handle);
};

struct ZygiskContext {
    JNIEnv *env;
    void *args;
    void *process;
    pid_t pid;
    int flags;
    int info_flags;
    vector<int> allowed_fds;
    vector<ZygiskModule> modules;
    pthread_mutex_t hook_info_lock;
};
```

**Hooked PLT Functions:**

| Function | Trigger | Purpose |
|----------|---------|---------|
| `strdup` | When str == "ZygoteInit" | Detects Zygote init → call `hook_zygote_jni()` |
| `fork` | Always | Caches PID for post-fork context |
| `unshare` | When CLONE_NEWNS | Triggers `revert_unmount()` for DenyList |
| `selinux_android_setcontext` | Always | Pre-fetches logd before SELinux transition |
| `android_log_close` | Always | Prevents logd from closing |
| `dlclose` | First call on self | Sets `self_handle`, calls `post_native_bridge_load()` |
| `pthread_attr_destroy` | Always | Triggers self-unload (musttail dlclose) |

**JNI Hooks (replaced methods):**

| Method | Class | Purpose |
|--------|-------|---------|
| `nativeForkAndSpecialize` | `com.android.internal.os.Zygote` | Intercept app process fork |
| `nativeSpecializeAppProcess` | `com.android.internal.os.Zygote` | Intercept app specialization |
| `nativeForkSystemServer` | `com.android.internal.os.Zygote` | Intercept system server fork |

**Control Flow (hook_entry → hook_plt):**
1. Creates `HookContext` on heap (`default_new`)
2. Scans memory maps for `libandroid_runtime.so` and `libnativebridge.so`
3. Registers PLT hooks via `lsplt` for: dlclose, fork, unshare, selinux_android_setcontext, strdup, android_log_close
4. Calls `lsplt::CommitHook()` to apply all hooks
5. Removes entries from backup that weren't hooked

**Control Flow (post_native_bridge_load):**
1. Store self_handle
2. Unwind call stack with `_Unwind_Backtrace` to find LoadNativeBridge
3. Find `NativeBridgeRuntimeCallbacks` in libart's writable memory
4. Reload the real native bridge if `ro.dalvik.vm.native.bridge` is set to a real bridge
5. Store runtime_callbacks

**Control Flow (strdup hook → hook_zygote_jni):**
1. `strdup("com.android.internal.os.ZygoteInit")` detected
2. `hook_zygote_jni()`:
   a. Get JavaVM via `JNI_GetCreatedJavaVMs` or dlopen `libnativehelper.so`
   b. Get JNIEnv
   c. Find Zygote class, get native methods
   d. `hook_jni_methods()` on `nativeForkAndSpecialize`, `nativeSpecializeAppProcess`, `nativeForkSystemServer`
   e. If any fails: restore already-hooked methods

**Control Flow (post-fork in app process):**
1. `ZygiskContext` created before fork
2. After fork, in child:
   - If DenyList matches: `revert_unmount()` → unmount all Magisk mounts
   - If not denylisted: load Zygisk modules, call their `onAppSpecialize()`
3. `pthread_attr_destroy` → cleanup → unmap self

**How it links:**
- Entry from `entry.cpp` NativeBridgeItf.isCompatibleWith → `hook_entry()`
- Uses `lsplt` library for PLT hooking
- Uses JNI to replace Java native methods
- Calls `revert_unmount()` from denylist utilities
- Module API defined in `api.hpp`, implemented in `module.cpp`

---

### 1.11 `native/src/init/init.rs` — magiskinit Init Replacement

**Purpose:** Replaces /init in the boot image ramdisk. Detects boot mode and sets up Magisk environment.

**Key Data Structure:**
```rust
impl MagiskInit {
    fn new(argv: *mut *mut c_char) -> Self;
    fn first_stage(&self);
    fn second_stage(&mut self);
    fn legacy_system_as_root(&mut self);
    fn rootfs(&mut self);
    fn recovery_or_charger(&self);
    fn restore_ramdisk_init(&self);
    fn start(&mut self) -> LoggedResult<()>;
}
```

**Control Flow (main / start):**
```
main():
  if argv0 == "magisk" → magisk_proxy_main()
  if PID == 1 → MagiskInit::new(argv).start()

start():
  1. Mount /proc, /sys
  2. setup_klog() — kernel logging
  3. config.init() — parse cmdline, detect device tree
  4. Decision tree:
     argv[1] == "selinux_setup" → SECOND STAGE
     config.skip_initramfs     → LEGACY SAR
     config.force_normal_boot  → FIRST STAGE
     is_recovery_or_charger()  → RECOVERY/CHARGER
     check_two_stage()         → FIRST STAGE (2SI)
     else                      → RootFS (old devices)
  5. exec_init() — execute real init
```

**Boot Path Details:**

| Path | Method | Description |
|------|--------|-------------|
| 2-Stage Init (2SI) | `first_stage()` | Hijacks SwitchRoot via /sdcard bind trick |
| Legacy SAR | `legacy_system_as_root()` | Hexpatches /init to re-exec as magiskinit |
| RootFS | `rootfs()` | Directly patches rw root filesystem |
| Recovery | `recovery_or_charger()` | Restores original init, skips patching |

**first_stage flow:**
1. `prepare_data()` — mount partitions, find preinit device
2. If /sdcard doesn't exist → `hijack_init_with_switch_root()`:
   - Binds magiskinit as /sdcard
   - Android's SwitchRoot mounts /sdcard as /system/bin/init
   - Cleanup → `restore_ramdisk_init()`
3. If /sdcard exists → `restore_ramdisk_init()` + `hexpatch_init_for_second_stage(true)`

**second_stage flow:**
1. Unmount /init and /system/bin/init
2. Remove /data/init
3. Overwrite argv[0] to "/system/bin/init"
4. If legacy rootfs: create /init → /system/bin/init symlink, `patch_rw_root()`
5. Else: `patch_ro_root()` — setup overlay filesystem structure

**How it links:**
- Compiled as a static binary (`magiskinit`)
- Replaces `/init` in the boot image ramdisk
- Calls C++ functions from `rootdir.cpp`, `mount.cpp`, `getinfo.cpp` via CXX bridge
- `preload.c` (libinit-ld.so) is an LD_PRELOAD library that intercepts `security_load_policy()`
- Extracts compressed `magisk.xz`, `stub.xz`, `init-ld.xz` from ramdisk overlay

---

### 1.12 `native/src/boot/cli.rs` — magiskboot CLI

**Purpose:** Boot image manipulation tool — unpack, repack, patch, sign boot images.

**CLI Structure:**
```rust
#[derive(FromArgs)]
struct Cli {
    #[argh(subcommand)]
    action: Action,
}

enum Action {
    Unpack(Unpack),        // Extract boot image components
    Repack(Repack),        // Rebuild boot image from components
    Verify(Verify),        // Verify boot signature
    Sign(Sign),            // Sign boot image (AVB 1.0)
    Extract(Extract),      // Extract boot from payload.bin
    HexPatch(HexPatch),    // Binary hex search/replace
    Cpio(Cpio),            // CPIO archive operations
    Dtb(Dtb),              // Device tree blob patching
    Split(Split),          // Split file by format boundaries
    Sha1(Sha1),            // Compute SHA1 hash
    Cleanup(Cleanup),      // Clean temporary files
    Compress(Compress),    // Compress file
    Decompress(Decompress), // Decompress file
}
```

**Key Functions:**

| Function | Signature | Purpose |
|----------|-----------|---------|
| `boot_main` | `fn boot_main(cmds: CmdArgs) -> LoggedResult<i32>` | Main dispatch |
| `verify_cmd` | `fn verify_cmd(image, cert) -> bool` | Verify boot signature |
| `sign_cmd` | `fn sign_cmd(image, name, cert, key) -> LoggedResult<()>` | Sign boot image |

**Control Flow (unpack):**
1. Open boot image file
2. Detect format (AOSP, ChromeOS, DHTB, BLOB, MTK)
3. Parse header (page size, kernel/ramdisk offsets/sizes)
4. Extract: kernel, ramdisk.cpio, dtb, second, extra, recovery_dtbo
5. Optionally decompress ramdisk
6. Optionally dump header info

**Control Flow (repack):**
1. Read components from working directory
2. Rebuild header with original format
3. Compress ramdisk if needed
4. Write output boot image
5. Optional: AVB sign, ChromeOS sign

**Control Flow (cpio):**
1. Open CPIO archive
2. Parse CPIO entries
3. Execute commands: add, remove, extract, test, backup, restore
4. Write modified archive

**Control Flow (dtb):**
1. Open DTB file
2. Parse flattened device tree
3. Find fstab nodes
4. Patch: remove verify flag, remove encrypt flag, etc.

**How it links:**
- Standalone static binary
- Uses Rust for most logic (cli.rs, cpio.rs, compress.rs, dtb.rs, payload.rs, sign.rs)
- Uses C++ for boot image header parsing (bootimg.cpp)
- Links with liblz4 for LZ4 compression
- Links with libbase for utilities

---

### 1.13 `native/src/sepolicy/cli.rs` — magiskpolicy CLI

**Purpose:** SELinux policy loading, modification, and compilation tool.

**CLI Structure:**
```rust
struct Cli {
    live: bool,             // --live: apply to running kernel
    magisk: bool,           // --magisk: apply Magisk rules
    compile_split: bool,    // --compile-split
    load_split: bool,       // --load-split
    print_rules: bool,      // --print-rules
    load: Option<String>,   // --load FILE
    save: Option<String>,   // --save FILE
    apply: Vec<String>,     // --apply FILE (multiple)
    polices: Vec<String>,   // Positional statements
}
```

**Key Functions:**

| Function | Purpose |
|----------|---------|
| `main()` | Parse args, load policy, apply rules, save/apply |

**Control Flow:**
1. Parse CLI arguments
2. Load sepolicy from one of:
   - `--load FILE` → `SePolicy::from_file(file)`
   - `--load-split` → `SePolicy::from_split()`
   - `--compile-split` → `SePolicy::compile_split()`
   - Default → `/sys/fs/selinux/policy`
3. If `--magisk`: apply `sepol.magisk_rules()`
4. If `--apply FILE`: `sepol.load_rule_file(file)` for each
5. For positional statements: `sepol.load_rules(statement)`
6. If `--live`: `sepol.to_file("/sys/fs/selinux/load")`
7. If `--save FILE`: `sepol.to_file(file)`

**Statements Supported:**
`allow`, `deny`, `auditallow`, `dontaudit`, `allowxperm`, `permissive`, `type`, `attribute`, `type_transition`, `genfscon`

**How it links:**
- Standalone executable
- Uses `libpolicy` (C++ sepolicy.cpp + api.cpp + policydb.cpp)
- Uses `libsepol` for low-level policy database operations
- Rust code handles CLI parsing and delegates to C++ for policy operations

---

## 2. ANDROID APP CODE (Kotlin/Java)

### 2.1 `app/core/src/main/java/pro/magisk/core/App.kt` — Application Entry

**Purpose:** Entry point of the Magisk Manager app.

```kotlin
class App : Application() {
    // Secondary constructor for stub mode
    constructor(o: Any) : this() {
        val data = StubApk.Data(o)
        // Map root service class name
        RootUtils::class.java.name in data.classToComponent
        data.rootService = RootUtils::class.java
        Info.stub = data
    }

    override fun attachBaseContext(context: Context) {
        when (context) {
            is Application -> AppContext.attachApplication(context)
            else -> {
                super.attachBaseContext(context)
                AppContext.attachApplication(this)
            }
        }
    }
}
```

**Flow:**
1. Normal mode: `Application.onCreate()` → `attachBaseContext()` → `AppContext.attachApplication()`
2. Stub mode: `constructor(o)` called by StubApplication → maps stub component names → sets `Info.stub`

---

### 2.2 `app/core/src/main/java/pro/magisk/core/AppContext.kt` — Context & Shell Init

**Purpose:** Global application context wrapper, initializes root shell and app lifecycle.

```kotlin
object AppContext : ContextWrapper(null), Application.ActivityLifecycleCallbacks {
    fun attachApplication(app: Application) {
        // 1. Set application reference
        // 2. Attach base context
        // 3. Register lifecycle callbacks
        // 4. Determine APK path (stub vs normal)
        // 5. Patch resources
        // 6. Build root shell with Shell.Builder (libsu)
        // 7. Set up root service binding (RootUtils)
        // 8. Pre-heat shell
        // 9. Initialize NetworkObserver
    }
}
```

---

### 2.3 `app/core/src/main/java/pro/magisk/core/Info.kt` — Environment Info

**Purpose:** Reads and stores all Magisk environment information.

```kotlin
object Info {
    var stub: StubApk.Data? = null
    var update: UpdateInfo = EMPTY_UPDATE
    var isRooted = false
    lateinit var env: Env
    var isSAR = false
    var isAB = false
    var isZygiskEnabled = false
    var ramdisk = false
    // ... more properties

    suspend fun fetchUpdate(svc: NetworkService): UpdateInfo?
    fun resetUpdate()

    // init(shell: Shell) — executes magisk commands to detect environment
    init {
        // Runs: magisk -v, magisk --path, and app_init shell commands
        // Parses: SYSTEM_AS_ROOT, RAMDISKEXIST, ISAB, SLOT, etc.
    }
}
```

---

### 2.4 `app/core/src/main/java/pro/magisk/core/Config.kt` — Settings

**Purpose:** Type-safe access to all Magisk and Manager settings (backed by SharedPreferences and Room DB).

```kotlin
object Config : PreferenceConfig, DBConfig {
    // Preference properties:
    var updateChannel: Int       // RELEASE_CHANNEL
    var darkTheme: Int           // DARK_THEME
    var checkUpdate: Boolean     // CHECK_UPDATES
    var zygisk: Boolean          // ZYGISK
    var suManager: String        // SU_MANAGER (hidden)
    var rootMode: Int            // ROOT_ACCESS
    var suAutoResponse: Int      // SU_AUTO_RESPONSE
    var suNotification: Int      // SU_NOTIFICATION
    var suMntNamespaceMode: Int  // SU_MNT_NS
    var suMultiuserMode: Int     // SU_MULTIUSER_MODE
    var suAuth: Boolean          // Biometric auth
    // ... many more

    @JvmField var keepVerity: Boolean = false
    @JvmField var keepEnc: Boolean = false
    @JvmField var recovery: Boolean = false
    @JvmField var denyList: Boolean = false

    fun toBundle(): Bundle       // Export to Bundle for migration
    fun init(bundle: Bundle?)     // Initialize from Bundle or defaults
}
```

---

### 2.5 `app/core/src/main/java/pro/magisk/core/su/SuRequestHandler.kt` — SU Request Handler

**Purpose:** Handles incoming SU request intents from the native daemon.

```kotlin
class SuRequestHandler(private val pm: PackageManager, private val policyDB: PolicyDao) {
    lateinit var output: File
    lateinit var policy: SuPolicy
    lateinit var pkgInfo: PackageInfo

    suspend fun start(intent: Intent): Boolean {
        // 1. init(intent) — validate uid, pid, fifo path
        // 2. Fetch policy from DB (PolicyDao.fetch(uid))
        // 3. Check auto-response (SU_AUTO_DENY / SU_AUTO_ALLOW → respond)
        // 4. Return true if user needs to decide
    }

    suspend fun respond(action: Int, time: Long) {
        // 1. Update policy in DB (allow/deny/restrict + timeout)
        // 2. Write action int to FIFO (native daemon reads this)
    }

    private suspend fun init(intent: Intent): Boolean {
        // Extract uid, pid, fifo from intent extras
        // Validate (uid > 0, pid > 0, fifo writable)
        // Fetch or create SuPolicy from DB
    }
}
```

---

### 2.6 `app/core/src/main/java/pro/magisk/core/tasks/MagiskInstaller.kt` — Install Logic

**Purpose:** Handles all Magisk installation methods.

**Class Hierarchy:**
```
MagiskInstallImpl (abstract base)
  ├── ConsoleInstaller (for UI with console output)
  │     ├── MagiskInstaller.Patch        (patch boot image file)
  │     ├── MagiskInstaller.Direct       (direct install)
  │     ├── MagiskInstaller.SecondSlot   (inactive slot install)
  │     ├── MagiskInstaller.Emulator     (emulator setup)
  │     └── MagiskInstaller.Uninstall    (uninstall)
  └── CallBackInstaller (for background operations)
        ├── MagiskInstaller.Restore      (restore stock boot)
        └── MagiskInstaller.FixEnv       (fix environment)
```

**Key Methods:**
```kotlin
protected suspend fun direct()    // findImage -> extractFiles -> patchBoot -> flashBoot
protected suspend fun secondSlot()// findSecondary -> extractFiles -> patchBoot -> flashBoot -> postOTA
protected suspend fun patchFile(file: Uri)  // extractFiles -> processFile -> patchBoot
protected fun uninstall()        // findImage -> restore images
protected fun restore()          // findImage -> restore_imgs
```

**Control Flow (direct):**
1. `findImage()` — locate boot partition via shell commands
2. `extractFiles()` — extract binaries (magisk, magiskinit, magiskboot, etc.) and scripts from APK assets
3. `patchBoot()` — run `boot_patch.sh` with env vars
4. `flashBoot()` — write patched image to boot partition

**Control Flow (patchFile):**
1. `extractFiles()` — same as above
2. `processFile(uri)` — detect file type (tar, zip, payload.bin, raw image):
   - tar → `processTar()` → extract boot.img
   - zip → `processZip()` → find payload.bin or boot.img
   - payload.bin → `processPayload()` → extract boot via magiskboot
   - raw → use directly
3. `patchBoot()` — run boot_patch.sh

---

### 2.7 `app/apk/src/main/java/pro/magisk/ui/` — UI Fragments

**Fragment Architecture:**
```
BaseFragment<T : ViewBinding>  (from core/base/)
├── HomeFragment        → HomeViewModel      → Dashboard
├── InstallFragment     → InstallViewModel   → Install/Upgrade
├── ModuleFragment      → ModuleViewModel    → Module list
├── SuperuserFragment   → SuperuserViewModel → SU permissions
├── DenyListFragment    → DenyListViewModel  → Hide root from apps
├── LogFragment         → LogViewModel       → Magisk + SU logs
├── SettingsFragment    → SettingsViewModel  → Settings
├── SuRequestActivity   → (dialog)           → SU request popup
├── FlashFragment       → (flash console)    → Install progress
└── ThemeFragment       → (theme picker)     → Theme selection
```

Each fragment:
- Declares `viewModel` via `by viewModel<XxxViewModel>()`
- Sets title in `onStart()`
- Configures RecyclerView in `onViewCreated()`
- Implements `MenuProvider` for menu actions
- Observes ViewModel LiveData for UI updates

---

### 2.8 `app/stub/src/main/java/pro/magisk/StubApplication.java` — Stub APK

**Purpose:** Minimal (~10KB) APK that downloads and loads the full Magisk Manager.

```java
public class StubApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        DynLoad.loadAndInitializeApp(this);
    }
}
```

**Flow:**
1. `StubApplication.attachBaseContext()` → `DynLoad.loadAndInitializeApp()`
2. DynLoad downloads full APK from configured URL
3. DynamicClassLoader loads real APK classes
4. DelegateComponentFactory maps stub components to real implementations
5. App continues as if it was the full version

---

### 2.9 `app/shared/src/main/java/pro/magisk/StubApk.java` — APK Loading

**Purpose:** Manages dynamic APK loading for the stub system and hide/restore functionality.

```java
public class StubApk {
    public static File current(Context c);          // Path to current full APK
    public static File update(Context c);           // Path to downloaded update APK
    public static void addAssetPath(Resources res, String path);  // Add APK resources

    public static class Data {
        int getVersion();
        Map<String, String> getClassToComponent();  // Stub class -> real class mapping
        Class<?> getRootService();                  // Root service class
    }
}
```

**Flow (addAssetPath):**
- SDK >= 30: uses `ResourcesLoader` + `ResourcesProvider`
- SDK < 30: reflection on `AssetManager.addAssetPath(String)`

---

### 2.10 `app/core/src/main/java/pro/magisk/core/tasks/AppMigration.kt` — App Hiding

**Purpose:** Handles the "Hide Magisk Manager" feature by repackaging the stub APK with random package/class names.

```kotlin
object AppMigration {
    suspend fun patchAndHide(context: Context, label: String, pkg: String? = null): Boolean {
        // 1. Extract stub.apk from assets
        // 2. Generate random package name
        // 3. Patch AndroidManifest.xml (replace package name, class names, label)
        // 4. Generate random class names for all components
        // 5. Re-sign APK with generated key
        // 6. Install via adb_pm_install
        // 7. Set Config.suManager to new package name
        // 8. Launch hidden app
    }

    suspend fun restoreApp(context: Context): Boolean {
        // Install original APK
        // Clear Config.suManager
        // Launch original app
    }
}
```

---

## 3. BUILD SYSTEM

### 3.1 `build.py` — Master Build Script

**Language:** Python 3.8+
**Purpose:** Orchestrates the entire build process across Rust, C++, and Gradle.

**Commands:**
```
python build.py all       # Full build: native → app → stub
python build.py native    # Build native binaries (Rust + C++ via NDK)
python build.py app       # Build Android APKs via Gradle
python build.py stub      # Build stub APK only
python build.py clean     # Clean all outputs
python build.py ndk       # Download custom ONDK
python build.py clippy    # Run Rust clippy lints
python build.py cargo     # Run arbitrary cargo commands
```

**Supported ABIs:**
```python
support_abis = {
    "armeabi-v7a": "thumbv7neon-linux-androideabi",
    "x86": "i686-linux-android",
    "arm64-v8a": "aarch64-linux-android",
    "x86_64": "x86_64-linux-android",
    "riscv64": "riscv64-linux-android",
}
```

**Build Flow (native):**
1. Parse args (targets, ABIs, release/debug)
2. For each ABI:
   - Run `cargo build` for each Rust crate (magisk, magiskboot, magiskinit, magiskpolicy)
   - Run `ndk-build` for C++ code (links Rust .a files)
   - Run `elf-cleaner` to strip unnecessary sections
3. Collect all binaries into `native/out/{abi}/`

**Build Flow (app):**
1. Run Gradle `assembleRelease`
2. MagiskPlugin (build_logic):
   - Load config from `config.prop` and `gradle.properties`
   - Copy native binaries from `native/out/{abi}/` → JNI libs `lib/`
   - Download busybox
   - Copy scripts → `assets/`
   - Embed `stub.apk` → `assets/`
   - Add version comment to APK
   - Sign APK

### 3.2 `app/build_logic/src/main/java/Plugin.kt` — Gradle Plugin

**Purpose:** Custom Gradle plugin that configures the Magisk APK build.

```kotlin
class MagiskPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // 1. Load config.prop properties
        // 2. Load gradle.properties (magisk.*)
        // 3. Initialize random seed (for reproducible builds)
        // 4. Set up native library synchronization
        // 5. Configure APK signing
        // 6. Add version comment task
    }
}
```

### 3.3 `app/build_logic/src/main/java/Stub.kt` — Stub APK Generation

**Purpose:** Generates the stub APK with randomized component names.

```kotlin
object Stub {
    fun generate(context: Project) {
        // 1. Generate random package name
        // 2. Generate random Activity/Service/Provider/Receiver class names
        // 3. Build AndroidManifest.xml with encrypted resources
        // 4. Build stub APK with LSParanoid obfuscation
    }
}
```

### 3.4 `native/src/Android.mk` — NDK Build

**Purpose:** NDK build script for all C++ code and linking Rust static libs.

**Modules built:**
| Module | Type | Source Files |
|--------|------|-------------|
| `magisk` | executable | applets.cpp, scripting.cpp, sqlite.cpp, su.cpp, zygisk/*, deny/* |
| `magiskinit` | static exe | mount.cpp, rootdir.cpp, getinfo.cpp, init-rs.cpp |
| `magiskboot` | static exe | bootimg.cpp, boot-rs.cpp |
| `magiskpolicy` | executable | sepolicy.cpp, api.cpp, policydb.cpp |
| `resetprop` | executable | applet_stub.cpp, sys.cpp |
| `init-ld` | shared lib | preload.c |
| `libpolicy` | static lib | sepolicy/api.cpp, sepolicy/sepolicy.cpp, sepolicy/policydb.cpp |

### 3.5 `native/src/Android-rs.mk` — Rust Library Linking

**Purpose:** Links prebuilt Rust static libraries into the NDK build.

Modules: `magisk-rs`, `boot-rs`, `init-rs`, `policy-rs`
Each maps to a `.a` file in `native/out/{abi}/`

### 3.6 `native/src/Application.mk` — NDK Config

```
APP_PLATFORM := android-23        // Minimum API 23
APP_STL := none                    // No C++ STL
APP_CFLAGS := -Wall -Oz -fomit-frame-pointer  // Optimize for size
APP_CPPFLAGS := -std=c++23          // C++23 standard
// LTO: thin for debug, fat for release
// crt0 mode: disables security features for static binaries
```

---

## 4. EXTERNAL DEPENDENCIES

### Native (built from source via NDK)

| Library | Purpose | Used By |
|---------|---------|---------|
| `xz-embedded` | XZ decompression | magiskinit |
| `lz4` | LZ4 compression | magiskboot |
| `selinux/libsepol` | SELinux policy library | magiskpolicy |
| `lsplt` | PLT hooking | Zygisk |
| `system_properties` | Direct property area access | resetprop |
| `libcxx` | Minimal C++ runtime | Various |
| `crt0` | Custom C runtime | Static binaries |

### Rust (via Cargo)

| Crate | Purpose |
|-------|---------|
| `cxx` | Rust/C++ FFI bridge |
| `libc` | Raw libc bindings |
| `nix` | Safe POSIX API |
| `sha1`, `sha2` | SHA hashing (AVB signing) |
| `rsa`, `p256`, etc. | RSA/EC crypto (AVB) |
| `flate2`, `bzip2`, `lz4`, `lzma-rust2`, `zopfli` | Compression |
| `quick-protobuf` | Protobuf (OTA payload) |
| `fdt` | Flattened Device Tree parsing |

### Java (via Gradle/Maven)

| Library | Purpose |
|---------|---------|
| `libsu (topjohnwu)` | Root shell + IPC |
| `Retrofit2 + Moshi` | GitHub API |
| `OkHttp3` | HTTP client |
| `Room` | SQLite ORM |
| `Navigation` | UI navigation |
| `Markwon` | Markdown rendering |
| `Material/AppCompat` | UI components |
| `LSParanoid` | Stub APK obfuscation |

---

## 5. CROSS-CUTTING PATTERNS

### Error Handling
- **Rust:** `LoggedResult<T>` (alias for `Result<T, Box<dyn Error>>`), `.log_ok()` to log and ignore, `log_err!()` to return error
- **C++:** Integer return codes, `fprintf(stderr)`, `exit()` for fatal
- **Daemon IPC:** `RespondCode` enum (OK/ACCESS_DENIED/ROOT_REQUIRED) over Unix sockets

### IPC Mechanisms
1. **Unix domain sockets** — magiskd client/server communication
2. **FD passing (SCM_RIGHTS)** — SU PTY, Zygisk module injection
3. **FIFO (named pipe)** — SU manager request/response
4. **Content providers** — SU logging from native to Java
5. **CXX FFI** — Rust ↔ C++ function calls within same binary

### Concurrency
- **magiskd:** Thread pool (core threads + timeout threads)
- **Boot stages:** Serialized by `boot_stage_lock` Mutex
- **SU cache:** Cached for 3 seconds, refreshed on miss
- **Zygiskd:** Synchronous poll loop for companion communication
- **Java app:** Coroutines + LiveData for async UI updates
