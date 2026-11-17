package com.magnetvault.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Shared lifecycle and serialized background work for local data operations. */
public class AppActivity extends Activity {
    static final ExecutorService DATA = Executors.newSingleThreadExecutor();
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private int appliedMode;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ThemePrefs.themedContext(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        ThemePrefs.apply(this);
        appliedMode = ThemePrefs.getMode(this);
        super.onCreate(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedMode != ThemePrefs.getMode(this)) recreate();
    }

    protected <T> void load(Callable<T> work, Consumer<T> done) {
        load(work, done, error -> Ui.toast(this, "操作未完成，请重试。"));
    }

    protected <T> void load(Callable<T> work, Consumer<T> done, Consumer<Exception> failed) {
        DATA.execute(
                () -> {
                    try {
                        T result = work.call();
                        MAIN.post(
                                () -> {
                                    if (!isFinishing() && !isDestroyed()) done.accept(result);
                                });
                    } catch (Exception ex) {
                        android.util.Log.e("MagnetVault", "Local operation failed", ex);
                        MAIN.post(
                                () -> {
                                    if (!isFinishing() && !isDestroyed()) failed.accept(ex);
                                });
                    }
                });
    }
}
