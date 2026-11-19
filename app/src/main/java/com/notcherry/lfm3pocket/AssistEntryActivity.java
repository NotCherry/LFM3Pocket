package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Default-assistant / ACTION_ASSIST entry. Routes the system assistant gesture
 * into Hanami's single chat entry and requests a voice session without
 * authorizing tools. Consent/confirmation stays in the existing tool flow.
 */
public final class AssistEntryActivity extends Activity {
    private float attachedInterfaceScale = InterfaceSize.DEFAULT;

    @Override protected void attachBaseContext(Context newBase) {
        attachedInterfaceScale = InterfaceSize.read(newBase);
        super.attachBaseContext(InterfaceSize.wrap(newBase));
    }

    @Override protected void onResume() {
        super.onResume();
        InterfaceSize.recreateIfChanged(this, attachedInterfaceScale);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_FRESH_CHAT, true)
                .putExtra(MainActivity.EXTRA_ASSISTANT_VOICE_SESSION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
