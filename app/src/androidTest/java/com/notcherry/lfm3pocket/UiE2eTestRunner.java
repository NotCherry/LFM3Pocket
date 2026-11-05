package com.notcherry.lfm3pocket;

import android.app.Application;
import android.content.Context;

import androidx.test.runner.AndroidJUnitRunner;

/** Test APK only: replace native app dependencies with deterministic fakes. */
public final class UiE2eTestRunner extends AndroidJUnitRunner {
    @Override public Application newApplication(ClassLoader cl, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.newApplication(cl, TestLfm3PocketApplication.class.getName(), context);
    }
}
