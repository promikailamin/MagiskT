# Magisk Project — Agent Guide

## Build
- **Primary**: `python build.py [all|native|app|app-ng|stub]`
- **Android**: Gradle multi-module under `app/` (Kotlin DSL + custom MagiskPlugin)
- **Config**: `config.prop` (version/ABI), `app/gradle.properties`
- **Native**: Rust (Cargo workspace `native/src/`) + legacy C++ (NDK `Android.mk`)

## Module Tree (`app/`)

| Module | Type | UI | Purpose |
|--------|------|----|---------|
| `:core` | lib | — | Shared logic, DI (`ServiceLocator`), DB, networking |
| `:apk` | app | DataBinding + MVVM | Current app |
| `:apkT` | app | Jetpack Compose + MVVM | Next-gen app |
| `:shared` | lib | — | Java-only, shared between stub+core |
| `:stub` | app | — | Thin proxy APK (dynamic class loading) |
| `:stub-res` | app | — | Stub resources only |

Deps: `apk/apkT → core → shared`, `stub → shared`

## Key Entry Points

- **App start**: `App.kt` → `MainActivity` (apk: NavigationActivity, apkT: ComponentActivity+Compose)
- **Stub**: `StubApplication` → `DynLoad` → downloads real APK, classloads it
- **Native daemon**: `native/src/core/daemon.rs` (socket server)
- **Flash**: `FlashZip` (core) → Shell job → terminal output
- **SU**: `SuRequestHandler` → `SuRequestActivity` → `PolicyDao` (magiskdb)
- **Root shell**: `ShellInit` builds env, `Config.kt` stores prefs (sharedPrefs + magiskdb)

## Architecture Patterns

- **DI**: Manual service locator (`ServiceLocator.kt`) — no Hilt/Dagger/Koin
- **State**: `MutableLiveData` + `StateFlow` (apkT: `collectAsState`)
- **Events**: Sealed `ViewEvent` class, dispatched via LiveData
- **DB**: Room (sulogs) + custom shell-backed `MagiskDB` (Policy/Settings/String DAOs)
- **Networking**: OkHttp + Retrofit + Moshi (`NetworkService.kt`)
- **Download**: `DownloadEngine` (foreground service, no update/repo — removed)

## Key Packages (`app/core/src/main/java/pro/magisk/core/`)

- `Config.kt` — SharedPreferences wrapper (all user settings)
- `Info.kt` — Runtime device/env info (root, A/B slot, SAR, etc.)
- `Const.kt` — App-wide constants
- `App.kt` — Application class
- `Receiver.kt` — BroadcastReceiver (package changes, locale)
- `Service.kt` — Download foreground service
- `model/module/LocalModule.kt` — Local module model (no update/repo data removed)
- `model/UpdateInfo.kt` — `ModuleJson` + `DateTimeAdapter` only
- `di/ServiceLocator.kt` — DI container
- `di/Networking.kt` — OkHttp/Retrofit setup
- `download/` — `DownloadEngine`, `DownloadProcessor`, `Subject`, `Interfaces`
- `repository/` — `NetworkService`, `DBConfig`, `PreferenceConfig`, `LogRepository`
- `tasks/` — `FlashZip`, `MagiskInstaller`, `AppMigration`, `ExtractImage`
- `su/` — `SuRequestHandler`, `SuCallbackHandler`
- `base/` — `SplashScreen`, `BaseActivity`, `BaseService`, `BaseReceiver`

## Key Packages (`app/apkT/src/`)

- `ui/MainActivity.kt` + `ui/MainScreen.kt` — Entry + tab scaffold
- `ui/navigation/Routes.kt` — Sealed Route classes
- `ui/home/` — HomeScreen/ViewModel
- `ui/module/` — ModuleScreen/ViewModel, ActionScreen/ViewModel
- `ui/settings/` — SettingsScreen/ViewModel
- `ui/flash/` — FlashScreen/ViewModel + FlashUtils
- `ui/install/` — InstallBottomSheet/ViewModel

## Key Packages (`app/apk/src/`)

- `ui/MainActivity.kt` — Entry + bottom nav
- `ui/home/HomeFragment.kt` — Home screen (DataBinding)
- `ui/module/ModuleFragment.kt` — Module list
- `ui/settings/SettingsFragment.kt` — Settings
- `ui/flash/FlashFragment.kt` — Flash console
- `dialog/` — DialogFragments (LocalModuleInstall, Uninstall, etc.)
- `arch/` — BaseFragment, BaseViewModel, NavigationActivity, UIActivity

## Important Files for Common Tasks

| Task | Files |
|------|-------|
| Add setting | `Config.kt` (key+field), `SettingsItems.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt` |
| Add UI screen | `Routes.kt` (apkT), nav_graph (apk), Screen+ViewModel files |
| New module | `app/` directory, `settings.gradle.kts`, build.gradle.kts with `setupAppCommon/setupCoreLib` |
| Modify networking | `NetworkService.kt`, `RetrofitInterfaces.kt`, `Networking.kt`, `ServiceLocator.kt` |
| Add native feature | Rust crate under `native/src/` + `Cargo.toml` |
| Flash/install logic | `FlashZip.kt`, `ExtractImage.kt` (native), `Payload.kt` |

## Removed Features (no-op references safe to skip)

- **Update system**: No update checking, GitHub API, update notifications, update settings
- **Online repo**: No module repo fetching, online install dialogs, update.json checking
- **Home manager**: No app version card on home screen
- Stub code (`app/stub/`, `StubApk`) is untouched

## Native Binaries

| Binary | Crate | Path | Purpose |
|--------|-------|------|---------|
| `magisk` | `core` | `native/src/core/daemon.rs` | Main daemon |
| `magiskinit` | `init` | `native/src/init/init.rs` | Init replacement |
| `magiskboot` | `boot` | `native/src/boot/cli.rs` | Boot image patching |
| `magiskpolicy` | `sepolicy` | `native/src/sepolicy/` | SELinux policy |
| `resetprop` | `core` | `native/src/core/resetprop/` | Property manip |
