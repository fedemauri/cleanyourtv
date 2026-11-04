package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Fork casa: one short weather line beside the date ("13° Nuvoloso · min 11° max 13°"), from the first package in
 * game_day that names a provider in its {@value #META} meta-data (the Smart Home add-on, from Home Assistant). The
 * launcher stays offline: it reads what the add-on publishes, off the main thread, when Home shows and every two
 * minutes. No such package: nothing is shown.
 */
final class HeaderWeather {
    static final String META = "com.gathin.tvhome.weather";

    private final Context ctx;
    private final Uri uri;
    private final Handler main;
    private final Consumer<String> onText;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private HeaderWeather(Context ctx, Uri uri, Handler main, Consumer<String> onText) {
        this.ctx = ctx.getApplicationContext();
        this.uri = uri;
        this.main = main;
        this.onText = onText;
    }

    static HeaderWeather create(Context ctx, String gameDayValue, Handler main, Consumer<String> onText) {
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : PanelSources.packages(gameDayValue)) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
                String authority = info.metaData == null ? null : info.metaData.getString(META);
                if (authority == null || authority.trim().isEmpty()) continue;
                ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
                if (provider == null || !pkg.equals(provider.packageName)) continue;
                Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority.trim())
                        .appendPath("weather").build();
                return new HeaderWeather(ctx, uri, main, onText);
            } catch (PackageManager.NameNotFoundException e) {
                // not installed: try the next one
            }
        }
        return null;
    }

    void read() {
        try {
            io.execute(() -> {
                String text = "";
                try (Cursor c = ctx.getContentResolver().query(uri, new String[] {"text"}, null, null, null)) {
                    if (c != null && c.moveToFirst()) text = c.getString(0);
                } catch (RuntimeException gone) {
                    return;   // the add-on was removed or crashed: keep what is on screen
                }
                final String shown = text == null ? "" : text;
                main.post(() -> onText.accept(shown));
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    void shutdown() {
        io.shutdownNow();
    }
}
