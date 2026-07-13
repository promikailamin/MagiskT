# Magisk - Frequently Asked Questions

## General

### Q: What is Magisk?
**A:** Magisk is a suite of open-source tools for Android that enables systemless root access, system modifications via modules, and code injection into app processes (Zygisk). It works by modifying the boot image to load a custom init that sets up the Magisk environment without altering system partitions.

### Q: How is Magisk different from other root solutions?
**A:** Unlike traditional root solutions (SuperSU, CF-Auto-Root) that modify system partitions, Magisk is **systemless**. It uses boot image patching and mount overlays to apply modifications without touching `/system`. This allows:
- OTA updates to work (system partition remains pristine)
- Modules can be enabled/disabled without permanent changes
- Safe mode to recover from bootloops
- Android Pay/Safetynet compatibility (with DenyList)

### Q: What is MagiskSU?
**A:** MagiskSU is Magisk's built-in `su` implementation. It manages root access with per-app permissions (Allow/Deny/Query), supports mount namespace separation, capability dropping, and SELinux context setting.

### Q: What is the difference between Zygisk and Riru?
**A:** Riru was the older method for injecting code into Zygote. Zygisk is Magisk's built-in alternative that:
- Does not require a separate module (Riru was a module itself)
- Integrates natively with Magisk
- Provides the DenyList functionality
- Is actively maintained by topjohnwu
- Riru is deprecated and no longer compatible with newer Magisk versions

### Q: Is Magisk open source?
**A:** Yes. Magisk is fully open source under the GNU General Public License v3. The source code is available at https://github.com/topjohnwu/Magisk

### Q: Does Magisk pass SafetyNet?
**A:** With Zygisk and DenyList configured properly, Magisk can pass SafetyNet/Play Integrity on most devices. However, Google continuously updates their detection methods, so there is no guarantee.

---

## Installation & Setup

### Q: Does Magisk require an unlocked bootloader?
**A:** Yes. Magisk requires an unlocked bootloader to flash the patched boot image on most devices. Some devices (like Samsung Knox) may have additional restrictions.

### Q: Will Magisk void my warranty?
**A:** Unlocking the bootloader and rooting typically voids the manufacturer's warranty. However, since Magisk is systemless, you can often unroot by flashing the stock boot image back.

### Q: How do I install Magisk for the first time?
**A:** See the [Installation section in usage.md](usage.md#installation). The two main methods are:
1. **Direct Install** (requires existing root)
2. **Patch Boot Image** (extract boot.img from firmware, patch in Magisk Manager, flash via fastboot)

### Q: Can I install Magisk on an emulator?
**A:** Yes. Magisk supports Android emulators (AVD, Cuttlefish). Use the emulator-specific install mode in Magisk Manager.

### Q: What is the stub APK?
**A:** The stub APK is a minimal (~10KB) application that downloads and loads the full Magisk Manager dynamically. It's used for the "Hide Magisk Manager" feature to make the app less detectable.

### Q: How do I update Magisk?
**A:** Open Magisk Manager → tap Install on the Magisk card → select Direct Install. The app will download and flash the updated version.

### Q: Can I update Magisk Manager independently of Magisk?
**A:** Yes. Magisk and Magisk Manager have separate version codes. You can update the Manager app without updating Magisk and vice versa.

---

## Root Access

### Q: How do I grant root access to an app?
**A:** When an app requests root for the first time, Magisk Manager shows a dialog. Tap **Allow** to grant. You can also pre-configure permissions in the **Superuser** section.

### Q: How do I revoke root access from an app?
**A:** Go to **Superuser** section in Magisk Manager, tap the app, and select **Deny**. Or swipe to delete the entry entirely.

### Q: What does "Query" mode mean?
**A:** "Query" mode means the user is prompted every time the app requests root (unless a timeout is set). This is the default setting.

### Q: How do I completely disable root?
**A:** In Settings, set **Superuser Access** to **Disabled**. All root requests will be rejected system-wide.

### Q: What is mount namespace isolation?
**A:** It controls whether the root shell can see the host's mount points or is isolated. The default is **Requester** mode, meaning root shells run in the calling app's namespace.

### Q: My su command returns immediately without granting access
**A:** Check your Superuser settings. If **Automatic Response** is set to **Deny**, all requests are rejected. Change it to **Prompt** or **Allow**.

---

## Modules

### Q: How do I install a Magisk module?
**A:** Download a `.zip` file, open Magisk Manager → Modules → **Install from storage**, select the ZIP.

### Q: How do I create my own module?
**A:** Create a directory with `module.prop` and a `system/` folder containing files you want to overlay. Zip it up and install. See the [Module section in usage.md](usage.md#modules).

### Q: How do modules work technically?
**A:** At boot, `magiskd` scans `/data/adb/modules/*/`, builds a virtual filesystem tree, and applies bind mounts to overlay module files over the real system files. The system partition is never modified.

### Q: How do I disable all modules at once?
**A:** Boot into **Safe Mode**: hold Volume Down during boot. Magisk detects the bootloop counter and disables all modules. Boot normally again to manage modules.

### Q: Can modules access the network or run background services?
**A:** Yes. Modules can include `post-fs-data.sh` and `service.sh` scripts that run as root during boot.

### Q: My module causes a bootloop. How do I recover?
**A:** Reboot while holding **Volume Down**. Magisk enters safe mode and disables all modules. Then re-enable modules one by one to find the culprit.

### Q: Can I install multiple modules that modify the same file?
**A:** The last module to be mounted takes priority. Module mounting order is determined by module ID (alphabetical).

---

## DenyList

### Q: What does DenyList do?
**A:** DenyList prevents selected apps from detecting Magisk by unmounting the Magisk tmpfs and all module mounts from the app's mount namespace.

### Q: Do I need Zygisk for DenyList?
**A:** Yes. DenyList requires Zygisk to be enabled.

### Q: How do I add an app to DenyList?
**A:** Go to DenyList section in Magisk Manager, search for the app, and check the box next to it.

### Q: Does DenyList guarantee root detection bypass?
**A:** No. Some apps use advanced detection methods. DenyList is a best-effort approach. You may need additional modules (like Shamiko) for stubborn apps.

### Q: What apps should I add to DenyList?
**A:** Common apps that check for root: Google Wallet, banking apps, streaming apps (Netflix, Disney+), games (Pokemon GO, Fate/Grand Order).

### Q: Should I add system apps to DenyList?
**A:** Be careful with system apps. Adding critical system apps can cause instability. Only add if you know what you're doing.

### Q: Can I use DenyList without hiding root?
**A:** DenyList is specifically for hiding root. If you don't need to hide root, you can leave it disabled.

---

## Zygisk

### Q: What is Zygisk used for?
**A:** Zygisk enables:
- DenyList (hide root)
- Zygisk modules (LSPosed, Zygisk-Assistant, etc.)
- Code injection into app processes

### Q: Does Zygisk affect performance?
**A:** Minimally. Zygisk only hooks Zygote's fork methods. The overhead is negligible unless you have many Zygisk modules loaded.

### Q: How do I enable Zygisk?
**A:** Settings → toggle Zygisk ON → reboot.

### Q: Can I use Riru modules with Zygisk?
**A:** No. Riru is incompatible with Zygisk. Modules must be updated to support Zygisk. Many popular modules (like LSPosed) support both.

### Q: Is Zygisk compatible with all devices?
**A:** Zygisk works on Android 6.0+ (API 23+). Some custom ROMs or kernels may have compatibility issues with the native bridge mechanism.

### Q: Does Zygisk conflict with other native bridges?
**A:** Zygisk replaces the native bridge. If another app uses `ro.dalvik.vm.native.bridge` (like Vulkan emulation), there may be conflicts.

---

## Troubleshooting

### Q: Magisk Manager says "Magisk is not installed"
**A:** Possible causes:
- Magisk was uninstalled or corrupted
- Boot image was overwritten by OTA update
- Incorrect installation method
  Solutions: Reinstall via Direct Install or re-patch boot image.

### Q: Zygisk is enabled but not working
**A:** Check `ro.dalvik.vm.native.bridge` property:
```
adb shell getprop ro.dalvik.vm.native.bridge
```
Should return `libzygisk.so`. If not, reboot. If still not working, reinstall Magisk.

### Q: Apps still detect root despite DenyList
**A:** Try:
1. Check if the app is actually checked in DenyList
2. Use Hide Magisk Manager (repack with random name)
3. Install Shamiko module for enhanced hiding
4. Clear the app's data (app may have cached detection results)
5. Try Zygisk Assistant module

### Q: My device won't boot after installing a module
**A:** Boot into **Safe Mode**:
1. Power off
2. Hold **Volume Down** + Power
3. Release when device vibrates
4. Magisk disables all modules automatically
5. Reboot normally and remove the problematic module

### Q: OTA update removed Magisk
**A:** For A/B devices: install the OTA but **do not reboot**. Open Magisk Manager and tap **Install to Inactive Slot**. Then reboot normally.
For non-A/B devices: you'll need to re-patch and flash the boot image after the OTA.

### Q: How do I collect logs for bug reports?
**A:**
```
adb shell
su
magisk --sqlite "SELECT * FROM strings" > /sdcard/magisk_db.txt
dmesg | grep -i magisk > /sdcard/magisk_dmesg.txt
logcat -d | grep -iE "(magisk|zygisk)" > /sdcard/magisk_logcat.txt
```
Also include the **Magisk Log** from the app's Log section.

### Q: "Device is corrupt" message on boot
**A:** This is normal for devices with unlocked bootloaders and custom init. It's a cosmetic message from the bootloader. Press power button to continue booting.

### Q: Can I use Magisk with custom ROMs?
**A:** Yes, Magisk works with most custom ROMs (LineageOS, Pixel Experience, etc.). However, some ROMs may have pre-installed root solutions that conflict with Magisk.

### Q: How do I completely uninstall Magisk?
**A:** In Magisk Manager, tap **Uninstall Magisk** → **Complete Uninstall**. This restores the stock boot image and removes all Magisk files. Alternatively, flash the stock boot image via fastboot.

### Q: Does Magisk work on Android 14+?
**A:** Yes. Magisk supports Android 6.0 (API 23) and above. The latest versions are tested with Android 14 and 15.

### Q: Can I use Magisk on Samsung devices?
**A:** Yes, but with caveats:
- Samsung Knox will trip (Samsung Pay, Secure Folder may not work)
- You must not flash Magisk to the recovery partition on newer Samsung devices
- Use the AP (boot image) patching method with Odin
- Keys are encrypted, so modules may have issues on stock Samsung firmware

### Q: What is "magiskinit"?
**A:** `magiskinit` replaces the stock `/init` in the boot image. It handles:
- Parsing kernel command line
- Detecting boot mode (2SI, SAR, RootFS)
- Setting up Magisk tmpfs
- Injecting Magisk services into init.rc
- Loading SELinux policy
- Eventually executing the real init

### Q: What is the difference between magisk32 and magisk64?
**A:** These are 32-bit and 64-bit variants of the magisk binary. The init process chooses the appropriate variant based on the device architecture.

### Q: Why does Magisk use Rust?
**A:** Rust provides memory safety without garbage collection, making it ideal for system-level programming. Magisk's native code is a hybrid of Rust (high-level logic) and C++ (low-level syscall wrappers), linked via the CXX crate.

### Q: How do I report a bug?
**A:** Report bugs at https://github.com/topjohnwu/Magisk/issues with:
- Device model
- Android version
- Magisk version
- Logs (Magisk log, logcat, dmesg)
- Steps to reproduce
- Expected vs actual behavior

---

## Security

### Q: Is Magisk safe?
**A:** Magisk is widely used and actively maintained by topjohnwu. However, rooting any device carries inherent security risks. Only install modules from trusted sources.

### Q: Can malware use Magisk to gain root?
**A:** If malware already has device administrator privileges, it could potentially use Magisk. This is why Magisk shows a su request dialog for every new app requesting root.

### Q: How does Magisk protect against tapjacking?
**A:** The su request dialog has tapjacking protection (enabled by default in settings) that prevents overlay attacks.

### Q: Can Magisk be detected by antivirus apps?
**A:** The Magisk Manager app may be flagged by some antivirus apps because it manages root access. This is a false positive.

### Q: Is it safe to use Magisk on my daily driver?
**A:** Millions of users use Magisk on their daily drivers. The systemless approach minimizes risk. Safe mode provides recovery from bootloops. Always backup important data before making system modifications.
