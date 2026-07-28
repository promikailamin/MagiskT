# Magisk Project — Agent Guide

## Build
- **Primary**: `python build.py [all|native|app|app-ng|stub]`
- **Android**: Gradle multi-module under `app/` (Kotlin DSL + custom `MagiskPlugin`)
- **Config**: `config.prop` (version/ABI/keystore), `app/gradle.properties` (magisk.* prefix)
- **Native**: Rust (Cargo workspace `native/src/`) + legacy C++ (NDK via `Android.mk`)
- **Build pipeline**: `build.py` → `load_config()` (reads config.prop + gradle.properties + git commit) → `dump_flags_native()` (generates `native/out/generated/flags.{h,rs}`) → `dump_flags_app()` (generates `app/build/flags.prop`) → Gradle (`MagiskPlugin` loads flags.prop and exposes via `Config` object)
- **Build commit**: `git rev-parse --short=8 HEAD` is stored as `config["buildCommit"]` → written to `flags.prop` → exposed as `BuildConfig.BUILD_COMMIT`. Falls back to `"local"` if git unavailable.
- **BuildConfig**: In `app/core/build.gradle.kts`, defines `APP_PACKAGE_NAME`, `APP_VERSION_CODE`, `APP_VERSION_NAME`, `BUILD_COMMIT`, `STUB_VERSION`

## Module Tree (`app/`)

| Module | Type | UI | Purpose |
|--------|------|----|---------|
| `:core` | lib | — | Shared logic, DI (`ServiceLocator`), DB, networking, flash/install, SU handlers |
| `:apk` | app | DataBinding + MVVM (Fragment-based) | Current production app |
| `:apkT` | app | Jetpack Compose + MVVM | Next-gen app (replacing apk) |
| `:shared` | lib | — | Java-only, shared between stub+core (StubApk, APKInstall, DynamicClassLoader) |
| `:stub` | app | — | Thin proxy APK (dynamic class loading + APK download) |
| `:stub-res` | app | — | Stub resources only (XML/drawables) |
| `:build_logic` | lib | — | Gradle plugin + setup helpers (Plugin.kt, Setup.kt, Stub.kt, TransformApkTask.kt) |

Deps: `apk/apkT → core → shared`, `stub → shared`

## Key Entry Points

- **App start**: `App.kt` → `AppContext` → `MainActivity` (apk: extends `NavigationActivity` with bottom nav + fragments, apkT: extends `ComponentActivity` + Compose)
- **Stub path**: `StubApplication` → `DynLoad` → downloads real APK via `DownloadActivity` → classloads it with `DynamicClassLoader` → `DelegateComponentFactory` intercepts component creation
- **Native daemon**: `native/src/core/daemon.rs` — Unix domain socket server (`magisk.sock`), handles SU requests, boot stages (PostFsData, LateStart, BootComplete), denylist, module management
- **Init**: `native/src/init/init.rs` (`magiskinit`) — Boot-time init replacement, hijacks early boot, patches SELinux policy, mounts overlay, sets up rootfs
- **Boot image**: `native/src/boot/cli.rs` (`magiskboot`) — Unpack/repack boot images, patch ramdisk, handle payload, DTB, compression
- **Flash**: `FlashZip` (Kotlin) → Shell job → terminal output to `FlashScreen`/`FlashFragment`
- **SU**: `SuRequestHandler` (native Rust in daemon) → `SuRequestActivity` (Android UI) → `PolicyDao` (MagiskDB shell-backed)
- **Root shell**: `ShellInit` builds env, `Config.kt` stores prefs

## Architecture Patterns

- **DI**: Manual service locator (`ServiceLocator.kt`) — no Hilt/Dagger/Koin
- **State**: `MutableLiveData` + `StateFlow` (apkT: `collectAsState` in Compose)
- **Events**: Sealed `ViewEvent` class, dispatched via LiveData/publish pattern
- **DB**: Room (SU access logs in `sulogs.db`) + custom shell-backed `MagiskDB` (Policy/Settings/String DAOs via `settingsDB`, `stringDB`, `policyDB`)
- **Networking**: OkHttp + Retrofit + Moshi (`NetworkService.kt` in di/ — though most networking removed)
- **Download**: `DownloadEngine` (foreground service — update system removed but engine remains)
- **Native FFI**: CXX bridge (Rust ↔ C++ via `cxx` crate), `build.rs` per crate generates bindings, JNI via `jni_hooks.hpp` for Zygisk
- **UI patterns**:
  - apk: DataBinding with `BaseFragment<ViewBinding>`, `BaseViewModel`, `AsyncLoadViewModel`, `UIActivity`/`NavigationActivity`
  - apkT: Jetpack Compose with `BaseViewModel`, `StateFlow<UiState>`, `Routes` sealed class navigation

## Key Packages (`app/core/src/main/java/pro/magisk/core/`)

- `Config.kt` — SharedPreferences + MagiskDB delegate properties (all user settings)
- `Info.kt` — Runtime device/env info (root, A/B slot, SAR, crypto, daemon version)
- `Const.kt` — App-wide constants (paths, version guards, nav keys, URLs)
- `App.kt` — Application class + stub constructor
- `AppContext.kt` — Static context holder
- `Receiver.kt` — BroadcastReceiver (package changes, locale changes)
- `Provider.kt` — ContentProvider for app init
- `Hacks.kt` — Reflection-based workarounds for Android API quirks
- `model/module/` — `Module.kt`, `LocalModule.kt` (local module model — update/repo data removed)
- `model/su/` — `SuPolicy.kt`, `SuLog.kt` (Room entities for SU access logs)
- `di/ServiceLocator.kt` — DI container (PolicyDao, SettingsDao, StringDao, sulogDB, Markwon)
- `tasks/` — `FlashZip.kt`, `MagiskInstaller.kt` (FixEnv/Restore/Reinstall), `AppMigration.kt` (hide/restore app), `ExtractImage.kt`, `Payload.kt`
- `su/` — `SuRequestHandler.kt`, `SuCallbackHandler.kt`, `SuEvents.kt`
- `repository/` — `DBConfig.kt`, `PreferenceConfig.kt`, `LogRepository.kt`
- `base/` — `SplashScreen.kt`, `BaseActivity.kt`, `BaseService.kt`, `BaseReceiver.kt`, `BaseProvider.kt`, `DebugActivity.kt`
- `ktx/` — `XAndroid.kt`, `XJVM.kt`, `XSU.kt` (Kotlin extension functions)
- `utils/` — `ShellInit.kt`, `RootUtils.kt`, `LocaleSetting.kt`, `CrashHandler.kt`, `Keygen.kt`, `AXML.kt`, `DummyList.kt`, `MediaStoreUtils.kt`, `RequestInstall.kt`, `RequestAuthentication.kt`, `TextHolder.kt`, `DataSourceChannel.java`, `Desugar.java`
- `view/` — `Notifications.kt`, `Shortcuts.kt`
- `data/` — `SuLogDao.kt` (Room DAO), `magiskdb/MagiskDB.kt`, `magiskdb/PolicyDao.kt`, `magiskdb/SettingsDao.kt`, `magiskdb/StringDao.kt`
- `signing/` — `ApkSignerV2.java`, `SignApk.java`, `JarMap.java`, `ZipUtils.java`, `ByteArrayStream.java` (APK signing utilities)

## Key Packages (`app/apkT/src/`)

- `ui/MainActivity.kt` + `ui/MainScreen.kt` — Entry + tab scaffold with TopAppBar + bottom nav
- `ui/MagiskTheme.kt` — Material3 theme (dark/light, dynamic colors)
- `ui/navigation/Routes.kt` — Sealed Route classes per tab
- `ui/navigation/Navigator.kt` — Navigation helper
- `ui/home/` — `HomeScreen.kt`, `HomeViewModel.kt` (Magisk card, status, install/uninstall, reboot)
- `ui/module/` — `ModuleScreen.kt`/`ViewModel`, `ActionScreen.kt`/`ViewModel`
- `ui/settings/` — `SettingsScreen.kt`/`ViewModel`
- `ui/flash/` — `FlashScreen.kt`/`ViewModel`, `FlashUtils.kt`
- `ui/install/` — `InstallBottomSheet.kt`/`ViewModel`
- `ui/log/` — `LogScreen.kt`/`ViewModel`, `MagiskLogParser.kt`
- `ui/superuser/` — `SuperuserScreen.kt`/`ViewModel`, `SuperuserDetailScreen.kt`
- `ui/surequest/` — `SuRequestActivity.kt`, `SuRequestScreen.kt`, `SuRequestViewModel.kt`
- `ui/deny/` — `DenyListScreen.kt`, `DenyListViewModel.kt`, `AppProcessInfo.kt`
- `ui/terminal/` — `TerminalScreen.kt`, `TerminalRenderer.kt`
- `terminal/` — `TerminalEmulator.kt`, `TerminalBuffer.kt`, `TerminalRow.kt`, `TerminalStyle.kt`, `TerminalProcess.kt`, `WcWidth.kt`
- `ui/component/` — `Dialog.kt`, `SettingsComponents.kt`
- `utils/Compose.kt` — Compose utility extensions

## Key Packages (`app/apk/src/`)

- `ui/MainActivity.kt` — Entry + bottom nav with fragment host
- `ui/home/HomeFragment.kt` — Home screen (DataBinding, `include_home_magisk.xml` layout)
- `ui/home/HomeViewModel.kt` — Version state, env check, links
- `ui/home/DeveloperItem.kt` — Developer credits sealed classes
- `ui/home/RebootMenu.kt` — Reboot options popup
- `ui/module/ModuleFragment.kt`, `ActionFragment.kt`, `ModuleViewModel.kt`, `ModuleRvItem.kt`
- `ui/settings/SettingsFragment.kt`, `SettingsViewModel.kt`, `SettingsItems.kt`, `BaseSettingsItem.kt`
- `ui/flash/FlashFragment.kt`, `FlashViewModel.kt`, `ConsoleItem.kt`
- `ui/install/InstallFragment.kt`, `InstallViewModel.kt`
- `ui/log/LogFragment.kt`, `LogViewModel.kt`, `LogRvItem.kt`, `SuLogRvItem.kt`
- `ui/superuser/SuperuserFragment.kt`, `SuperuserViewModel.kt`, `PolicyRvItem.kt`
- `ui/surequest/SuRequestActivity.kt`, `SuRequestViewModel.kt`
- `ui/deny/DenyListFragment.kt`, `DenyListViewModel.kt`, `DenyListRvItem.kt`, `AppProcessInfo.kt`
- `ui/theme/ThemeFragment.kt`, `ThemeViewModel.kt`, `Theme.kt`
- `dialog/` — `DarkThemeDialog.kt`, `EnvFixDialog.kt`, `LocalModuleInstallDialog.kt`, `MarkDownDialog.kt`, `SecondSlotWarningDialog.kt`, `UninstallDialog.kt`, `SuperuserRevokeDialog.kt`
- `arch/` — `BaseFragment.kt`, `BaseViewModel.kt`, `AsyncLoadViewModel.kt`, `NavigationActivity.kt`, `UIActivity.kt`, `ViewEvent.kt`, `ViewModelHolder.kt`
- `databinding/` — `DataBindingAdapters.kt`, `DiffObservableList.kt`, `MergeObservableList.kt`, `ObservableHost.kt`, `RecyclerViewItems.kt`, `RvItemAdapter.kt`
- `events/ViewEvents.kt` — Shared ViewEvent types
- `utils/` — `AccessibilityUtils.kt`, `MotionRevealHelper.kt`
- `view/` — `MagiskDialog.kt`, `TappableHeadlineItem.kt`, `TextItem.kt`
- `widget/ConcealableBottomNavigationView.java` — Bottom nav with reveal/hide animation

## Important Files for Common Tasks

| Task | Files |
|------|-------|
| Add setting | `Config.kt` (key+field), `SettingsItems.kt` (apk), `SettingsViewModel.kt`, `SettingsScreen.kt` (apkT) |
| Add UI screen | `Routes.kt` (apkT), nav_graph (apk), Screen+ViewModel files |
| New module | `app/` directory, `settings.gradle.kts`, build.gradle.kts with `setupAppCommon`/`setupCoreLib` |
| Modify networking | `NetworkService.kt`, `RetrofitInterfaces.kt`, `Networking.kt`, `ServiceLocator.kt` |
| Add native feature | Rust crate under `native/src/` + `Cargo.toml`; CXX bridge in `build.rs` + `*-rs.{hpp,cpp}` |
| Add BuildConfig field | `app/build_logic/src/main/java/Plugin.kt` (add `val` to `Config`), `app/core/build.gradle.kts` (add `buildConfigField`), `build.py` `dump_flags_app()` (add to `flags.prop`) |
| Flash/install logic | `FlashZip.kt`, `ExtractImage.kt` (native), `Payload.kt`, `MagiskInstaller.kt` |

## Build System Details

- `build.py` loads config from: (1) command-line `-c` flag, (2) `config.prop`, (3) `app/gradle.properties` with `magisk.` prefix
- `Plugin.kt` (Gradle plugin) loads in order: (1) gradle properties with `magisk.` prefix, (2) `config.prop` or custom path, (3) `app/build/flags.prop` (generated by build.py)
- `flags.prop` contains: `abiList`, `version`, `versionCode`, `buildCommit`
- APK signing config in `config.prop`: `keyStore`, `keyStorePass`, `keyAlias`, `keyPass`
- Stub APK is built first, then embedded as an asset in the main APK
- Native binaries are built per-ABI, renamed to `lib*.so`, and bundled as JNI libs
- `TransformApkTask` post-processes the APK to embed version metadata in the ZIP comment

## Removed Features (no-op references safe to skip)

- **Update system**: No update checking, GitHub API, update notifications, update settings
- **Online repo**: No module repo fetching, online install dialogs, update.json checking
- **Home manager**: No app version card on home screen
- Stub code (`app/stub/`, `app/stub-res/`, `app/shared/`) is mostly untouched from upstream — core logic is in `app/core/` and native binaries

## Native Modules

| Binary | Crate | Path | Purpose |
|--------|-------|------|---------|
| `magisk` | `core` | `native/src/core/daemon.rs` | Main daemon (socket server, SU, modules, denylist, boot stages, Zygisk) |
| `magiskinit` | `init` | `native/src/init/init.rs` | Init replacement (first/second stage, SAR, rootfs patching, SELinux) |
| `magiskboot` | `boot` | `native/src/boot/cli.rs` | Boot image unpack/repack, DTB, cpio, compression, payload extraction, signing |
| `magiskpolicy` | `sepolicy` | `native/src/sepolicy/` | SELinux policy loading, compilation, rule manipulation (allow/deny/audit/type) |
| `resetprop` | `core` | `native/src/core/resetprop/` | System property manipulation (persistent props, read/write/delete) |

## Native Architecture

- **Rust crates**: `core`, `init`, `boot`, `sepolicy`, `base` (shared utilities)
- **C++**: JNI bridge files (`*-rs.cpp`/`*.hpp` auto-generated by CXX), plus hand-written C++: `bootimg.cpp`, `mount.cpp`, `rootdir.cpp`, `su.cpp`, `base.cpp`, `sepolicy.cpp`, `policydb.cpp`, `api.cpp`, Zygisk hooks (`hook.cpp`, `entry.cpp`, `module.cpp`), denylist (`logcat.cpp`, `utils.cpp`, `cli.cpp`), `resetprop/sys.cpp`, `scripting.cpp`, `sqlite.cpp`, `applets.cpp`
- **FFI**: CXX bridge connects Rust ↔ C++; each crate has `build.rs` that generates `*-rs.{hpp,cpp}`; the `base` crate provides `base.hpp` with cross-cutting utilities
- **Zygisk**: Loaded into Zygote process via `entry.cpp`; `jni_hooks.hpp` defines JNI hook structs; `hook.cpp` installs JNI function hooks; `module.cpp` manages Zygisk module loading; communicates with daemon via socket
- **Denylist**: `deny/` directory with `cli.cpp` (process management), `utils.cpp` (mount namespace operations), `logcat.cpp` (log hiding)
- **SU**: `su/daemon.rs` (Rust, handles IPC), `su/connect.rs` (client connection), `su/pts.rs` (PTY allocation), `su/db.rs` (policy DB queries), `su/su.cpp` (C++, getopt, namespace management, capability dropping, SELinux context switch)

## Zygisk Architecture

- `native/src/core/zygisk/` — Zygisk subsystem
  - `daemon.rs` — Zygisk daemon socket handler (Rust)
  - `mod.rs` — Module loading logic
  - `entry.cpp` — Zygote entry point (fork handler)
  - `hook.cpp` — JNI function hooking (method replacement)
  - `jni_hooks.hpp` — JNI hook structs for various Android classes
  - `module.cpp` — Zygisk module (.so) loading and management
  - `module.hpp` — Module metadata structures
  - `zygisk.hpp` — Zygisk constants and declarations
  - `api.hpp` — Public Zygisk module API (for third-party modules)
  - `gen_jni_hooks.py` — Python script to generate JNI hook code
