package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Default-assistant / ACTION_ASSIST entry. Opens chat on a fresh conversation
 * without authorizing tools. Voice interaction is not implemented yet.
 */
public final class AssistEntryActivity extends Activity {
    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(InterfaceSize.wrap(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_FRESH_CHAT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
