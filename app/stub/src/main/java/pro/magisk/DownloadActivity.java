/**
 * Activity that handles downloading and installing the full Magisk APK.
 *
 * Two modes:
 * - <b>Dynamic load mode</b> (hidden/renamed package): downloads the APK to internal storage
 *   and restarts the process so the new APK is picked up by DynLoad.
 * - <b>Direct install mode</b> (stock package name): downloads the APK and installs it via
 *   the PackageInstaller API for a standard upgrade.
 *
 * Resources (strings) are embedded encrypted in the stub APK (via the Bytes class) and
 * decrypted here to support localised UI without the full resources.arsc.
 */
package pro.magisk;

import static android.R.string.no;
import static android.R.string.ok;
import static android.R.string.yes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.ContextThemeWrapper;

import pro.magisk.net.Networking;
import pro.magisk.net.Request;
import pro.magisk.utils.APKInstall;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class DownloadActivity extends Activity {

    private static final String APP_NAME = "Magisk";
    /** Package name under which the encrypted resources are stored in the stub APK. */
    private static final String RES_PKG_NAME = "pro.magisk";

    /** Whether the app is running under a hidden (non-stock) package name and needs dynamic loading. */
    private boolean dyn_load;

    /** Resource ID for the "downloading" string. */
    private int dling;
    /** Resource ID for the "no internet" string. */
    private int no_internet_msg;
    /** Resource ID for the "upgrade available" string. */
    private int upgrade_msg;

    @Override
    protected void onCreate(Bundle saved_instance_state) {
        super.onCreate(saved_instance_state);
        getTheme().applyStyle(android.R.style.Theme_DeviceDefault_Dialog_NoActionBar, true);

        dyn_load = !getPackageName().equals(BuildConfig.APPLICATION_ID);

        try {
            load_resources();
        } catch (Exception e) {
            error(e);
            return;
        }

        ProviderInstaller.install(this);

        if (Networking.check_network_status(this)) {
            show_dialog();
        } else {
            new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(APP_NAME)
                    .setMessage(getString(no_internet_msg))
                    .setNegativeButton(ok, (d, w) -> finish())
                    .show();
        }
    }

    /** Finish the activity and exit the process immediately. */
    @Override
    public void finish() {
        super.finish();
        Runtime.getRuntime().exit(0);
    }

    /** Log the error and exit. */
    private void error(Throwable e) {
        Log.e(getClass().getSimpleName(), Log.getStackTraceString(e));
        finish();
    }

    /** Convenience to create a GET request with the error handler wired up. */
    private Request request(String url) {
        return Networking.get(url).set_error_handler((conn, e) -> error(e));
    }

    /** Show the upgrade confirmation dialog. */
    private void show_dialog() {
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(APP_NAME)
                .setMessage(getString(upgrade_msg))
                .setPositiveButton(yes, (d, w) -> dl_a_p_k())
                .setNegativeButton(no, (d, w) -> finish())
                .show();
    }

    /**
     * Download the new APK.
     *
     * In dynLoad mode: save as current.apk and restart the process.
     * In direct mode: install via PackageInstaller session and launch the install confirmation intent.
     */
    private void dl_a_p_k() {
        ProgressDialog.show(this, getString(dling), getString(dling) + " " + APP_NAME, true);
        var request = request(BuildConfig.APK_URL).set_executor(AsyncTask.THREAD_POOL_EXECUTOR);
        if (dyn_load) {
            request.get_as_file(StubApk.current(this), file -> StubApk.restart_process(this));
        } else {
            request.get_as_input_stream(input -> {
                var session = APKInstall.start_session(this);
                try (input; var out = session.open_stream(this)) {
                    if (out != null)
                        APKInstall.transfer(input, out);
                } catch (IOException e) {
                    error(e);
                }
                Intent intent = session.wait_intent();
                if (intent != null)
                    startActivity(intent);
            });
        }
    }

    /**
     * Decrypt and decompress the embedded resources into the given output stream.
     *
     * Uses AES/CBC decryption followed by Inflater decompression on the embedded
     * resource blob (from the Bytes class).
     */
    private void decrypt_resources(OutputStream out) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKey key = new SecretKeySpec(Bytes.key(), "AES");
        IvParameterSpec iv = new IvParameterSpec(Bytes.iv());
        cipher.init(Cipher.DECRYPT_MODE, key, iv);
        var is = new InflaterInputStream(new CipherInputStream(
                new ByteArrayInputStream(Bytes.res()), cipher));
        try (is; out) {
            APKInstall.transfer(is, out);
        }
    }

    /**
     * Load encrypted string resources into the Activity's Resources.
     *
     * On R+: uses ResourcesLoader with a memfd-backed resources table.
     * On older: creates a temporary APK with AndroidManifest.xml + decrypted resources.arsc
     * and injects it via AssetManager.addAssetPath.
     *
     * After loading, resolves the string resource IDs used by the UI.
     */
    private void load_resources() throws Exception {
        var res = getResources();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var fd = Os.memfd_create("res", 0);
            try {
                decrypt_resources(new FileOutputStream(fd));
                Os.lseek(fd, 0, OsConstants.SEEK_SET);
                var loader = new ResourcesLoader();
                try (var pfd = ParcelFileDescriptor.dup(fd)) {
                    loader.addProvider(ResourcesProvider.loadFromTable(pfd, null));
                    res.addLoaders(loader);
                }
            } finally {
                Os.close(fd);
            }
        } else {
            File apk = new File(getCodeCacheDir(), "res.apk");
            try (var out = new ZipOutputStream(new FileOutputStream(apk))) {
                // AndroidManifest.xml is required on Android 6-, directory support is broken on 9-10
                out.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                try (var stub_apk = new ZipFile(getPackageCodePath())) {
                    APKInstall.transfer(stub_apk.getInputStream(stub_apk.getEntry("AndroidManifest.xml")), out);
                }
                out.putNextEntry(new ZipEntry("resources.arsc"));
                decrypt_resources(out);
            }
            StubApk.add_asset_path(res, apk.getPath());
        }
        dling = res.getIdentifier("dling", "string", RES_PKG_NAME);
        no_internet_msg = res.getIdentifier("no_internet_msg", "string", RES_PKG_NAME);
        upgrade_msg = res.getIdentifier("upgrade_msg", "string", RES_PKG_NAME);
    }
}
