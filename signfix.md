# Signing System Fixes

## Root Cause Analysis

The signing system was failing due to two issues:

### Issue 1: Invalid `abiList` format in `config.prop` (Primary cause)

**File**: `config.prop`

The `abiList` value contained literal square brackets:
```
abiList=[armeabi-v7a, arm64-v8a]
```

The upstream `config.prop.sample` uses `[string]` as documentation notation meaning "list of strings", not as actual config values. The brackets were incorrectly carried over as literal characters.

**Impact**:
- `build.py` uses `re.split("\\s*,\\s*", config["abiList"])` which produced `{"[armeabi-v7a", "arm64-v8a]"}` — ABIs with embedded brackets
- `set_build_abis()` then rejected these as unknown ABIs: `Unknown ABI: [armeabi-v7a`
- Build aborted before any signing could occur
- In Gradle directly, `Config.abiList` (in Plugin.kt) splits on `,` producing `["[armeabi-v7a", " arm64-v8a]"]`, causing sync tasks to look for nonexistent `native/out/[armeabi-v7a/` directories

### Issue 2: Missing explicit `storeType` in signing config

**File**: `app/build_logic/src/main/java/Setup.kt` (line 207)

The `signingConfigs.create("config")` block never set `storeType`. When the `TransformApkTask` calls `KeystoreHelper.getCertificateInfo(config.storeType, ...)`, the `storeType` is `null`, causing `KeystoreHelper` to fall back to `KeyStore.getDefaultType()`. While this works on JDK 21 (which returns `"pkcs12"` by default), relying on the JDK default is fragile and could break on different JDK versions.

## Changes Made

### 1. `config.prop`

- Removed literal brackets from `abiList` value:
  - **Before**: `abiList=[armeabi-v7a, arm64-v8a]`
  - **After**: `abiList=armeabi-v7a,arm64-v8a`
- Fixed version spelling:
  - **Before**: `version=Vanila`
  - **After**: `version=Vanilla`

### 2. `app/build_logic/src/main/java/Setup.kt`

- Added explicit `storeType = "PKCS12"` to the signing config creation block (line 207):
  ```kotlin
  signingConfigs {
      Config["keyStore"]?.also {
          create("config") {
              storeFile = rootFile(it)
              storePassword = Config["keyStorePass"]
              keyAlias = Config["keyAlias"]
              keyPassword = Config["keyPass"]
              storeType = "PKCS12"  // <-- added
          }
      }
  }
  ```
