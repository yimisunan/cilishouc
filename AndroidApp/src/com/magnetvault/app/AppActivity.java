package com.magnetvault.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Shared lifecycle, motion transitions, and serialized background work for local data operations. */
public class AppActivity extends Activity {
    public static final String EXTRA_FADE = "mv_fade";
    public static final String EXTRA_NO_ANIM = "mv_no_anim";

    static final ExecutorService DATA = Executors.newSingleThreadExecutor();
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private int appliedMode;
    private boolean finishWithFade;
    private boolean finishNoAnim;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ThemePrefs.themedContext(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        ThemePrefs.apply(this);
        appliedMode = ThemePrefs.getMode(this);
        Intent in = getIntent();
        if (in != null) {
            finishWithFade = in.getBooleanExtra(EXTRA_FADE, false);
            finishNoAnim = in.getBooleanExtra(EXTRA_NO_ANIM, false);
        }
        super.onCreate(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedMode != ThemePrefs.getMode(this)) recreate();
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        super.startActivityForResult(intent, requestCode, options);
        if (intent == null || !ValueAnimator.areAnimatorsEnabled()) return;
        if (intent.getBooleanExtra(EXTRA_NO_ANIM, false)) {
            overridePendingTransition(0, 0);
        } else if (intent.getBooleanExtra(EXTRA_FADE, false)) {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else {
            overridePendingTransition(R.anim.activity_enter, R.anim.activity_exit);
        }
    }

    @Override
    public void finish() {
        super.finish();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        if (finishNoAnim) {
            overridePendingTransition(0, 0);
        } else if (finishWithFade) {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else {
            overridePendingTransition(R.anim.activity_pop_enter, R.anim.activity_pop_exit);
        }
    }

    protected void finishQuietFade() {
        finishWithFade = true;
        finish();
    }

    protected static void smoothTransition(ViewGroup root) {
        Design.animate(root);
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
