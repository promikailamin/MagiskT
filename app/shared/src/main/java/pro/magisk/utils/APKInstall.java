/**
 * Utilities for streaming data and performing APK installations via the
 * {@link android.content.pm.PackageInstaller} API.
 *
 * Provides a simple session-based install flow: create a session, open a write
 * stream, transfer the APK bytes, commit the session, and optionally wait for
 * the user to confirm the installation.
 */
package pro.magisk.utils;

import static android.content.pm.PackageInstaller.EXTRA_SESSION_ID;
import static android.content.pm.PackageInstaller.EXTRA_STATUS;
import static android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID;
import static android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION;
import static android.content.pm.PackageInstaller.STATUS_SUCCESS;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller.SessionParams;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class APKInstall {

    /** Buffer size for IO stream copying. */
    private static final int TRANSFER_BUFFER_SIZE = 8192;

    /**
     * Copies all data from the input stream to the output stream using an 8 KB buffer.
     * Does not close either stream.
     */
    public static void transfer(InputStream in, OutputStream out) throws IOException {
        var buffer = new byte[TRANSFER_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer, 0, TRANSFER_BUFFER_SIZE)) >= 0) {
            out.write(buffer, 0, read);
        }
    }

    /**
     * Registers a BroadcastReceiver safely across API levels.
     * On API 26+ uses {@link Context#RECEIVER_NOT_EXPORTED} for security.
     */
    public static void registerReceiver(
            Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // noinspection InlinedApi
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /** Starts a PackageInstaller session without tracking a specific package. */
    public static Session start_session(Context context) {
        return start_session(context, null, null, null);
    }

    /**
     * Starts a PackageInstaller session and optionally monitors for a package added event.
     *
     * @param context   the context
     * @param pkg       if non-null, the receiver will also listen for ACTION_PACKAGE_ADDED
     *                  for this package and invoke onSuccess on that event
     * @param onFailure runnable invoked on installation failure
     * @param onSuccess runnable invoked on successful installation completion
     * @return a Session for writing APK data and waiting for the result
     */
    public static Session start_session(Context context, String pkg,
                                        Runnable on_failure, Runnable on_success) {
        var receiver = new InstallReceiver(pkg, on_success, on_failure);
        context = context.getApplicationContext();
        if (pkg != null) {
            var filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            registerReceiver(context, receiver, filter);
        }
        registerReceiver(context, receiver, new IntentFilter(receiver.session_id));
        return receiver;
    }

    /** Represents an ongoing PackageInstaller session. */
    public interface Session {
        /** Opens an OutputStream to write the APK content into the install session. */
        OutputStream open_stream(Context context) throws IOException;
        /**
         * Waits (up to 5 seconds) for the installation result and returns a
         * PendingIntent for user confirmation, or null if not needed.
         */
        Intent wait_intent();
    }

    /**
     * BroadcastReceiver that doubles as a Session implementation.
     * Listens for PackageInstaller status broadcasts and optionally for
     * ACTION_PACKAGE_ADDED to detect when a specific package was installed.
     */
    private static class InstallReceiver extends BroadcastReceiver implements Session {
        private final String packageName;
        private final Runnable on_success;
        private final Runnable on_failure;
        private final CountDownLatch latch = new CountDownLatch(1);
        private Intent user_action = null;

        /** Unique identifier used as the custom broadcast action for this session. */
        final String session_id = UUID.randomUUID().toString();

        private InstallReceiver(String packageName, Runnable on_success, Runnable on_failure) {
            this.packageName = packageName;
            this.on_success = on_success;
            this.on_failure = on_failure;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                Uri data = intent.getData();
                if (data == null)
                    return;
                String pkg = data.getSchemeSpecificPart();
                if (pkg.equals(packageName)) {
                    on_success(context);
                }
            } else if (session_id.equals(intent.getAction())) {
                int status = intent.getIntExtra(EXTRA_STATUS, STATUS_FAILURE_INVALID);
                switch (status) {
                    case STATUS_PENDING_USER_ACTION ->
                            user_action = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    case STATUS_SUCCESS -> {
                        if (packageName == null) {
                            on_success(context);
                        }
                    }
                    default -> {
                        int id = intent.getIntExtra(EXTRA_SESSION_ID, 0);
                        var installer = context.getPackageManager().getPackageInstaller();
                        try {
                            installer.abandonSession(id);
                        } catch (SecurityException ignored) {
                        }
                        if (on_failure != null) {
                            on_failure.run();
                        }
                        try {
                            context.getApplicationContext().unregisterReceiver(this);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                latch.countDown();
            }
        }

        private void on_success(Context context) {
            if (on_success != null)
                on_success.run();
            try {
                context.getApplicationContext().unregisterReceiver(this);
            } catch (IllegalArgumentException ignored) {
            }
        }

        /** Waits up to 5 seconds for the install result broadcast. */
        @Override
        public Intent wait_intent() {
            try {
                // noinspection ResultOfMethodCallIgnored
                latch.await(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            return user_action;
        }

        /**
         * Creates a PackageInstaller session, opens a write stream, and returns
         * a FilterOutputStream that commits the session on close.
         */
        @Override
        public OutputStream open_stream(Context context) throws IOException {
            // noinspection InlinedApi
            var flag = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
            var intent = new Intent(session_id).setPackage(context.getPackageName());
            var pending = PendingIntent.getBroadcast(context, 0, intent, flag);

            var installer = context.getPackageManager().getPackageInstaller();
            var params = new SessionParams(SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            var session = installer.openSession(installer.createSession(params));
            var out = session.openWrite(session_id, 0, -1);
            return new FilterOutputStream(out) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }
                @Override
                public void close() throws IOException {
                    super.close();
                    session.commit(pending.getIntentSender());
                    session.close();
                }
            };
        }
    }
}
