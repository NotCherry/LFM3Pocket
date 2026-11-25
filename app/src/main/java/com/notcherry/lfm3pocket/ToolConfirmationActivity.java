package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Internal foreground consent UI. Intents carry only a random proposal ID, never editable tool arguments. */
public final class ToolConfirmationActivity extends Activity {
    private ToolCoordinator coordinator;
    private String id;
    private TextView details;
    private TextView status;
    private Button approve;
    private Button reject;
    private Button done;
    private float attachedInterfaceScale = InterfaceSize.DEFAULT;

    @Override protected void attachBaseContext(Context newBase) {
        attachedInterfaceScale = InterfaceSize.read(newBase);
        super.attachBaseContext(InterfaceSize.wrap(newBase));
    }

    @Override protected void onResume() {
        super.onResume();
        InterfaceSize.recreateIfChanged(this, attachedInterfaceScale);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        coordinator = ((Lfm3PocketApplication) getApplication()).toolCoordinator();
        id = getIntent().getStringExtra("proposal_id");
        int surface = StillwaterUi.SURFACE;
        int ink = StillwaterUi.INK;
        int inkMuted = StillwaterUi.MUTED;
        int border = StillwaterUi.LINE;
        int minTouch = Math.max(dp(48), InterfaceSize.minTouchPx(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        StillwaterUi.applySystemAndImeInsets(this, root, dp(20), dp(16), dp(20), dp(16));

        TextView heading = new TextView(this);
        heading.setText(R.string.android_action);
        heading.setTextSize(28);
        heading.setTextColor(ink);
        heading.setTypeface(StillwaterUi.serif(this));
        StillwaterReadable.prepareWrappingLabel(heading);
        root.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.approve_exact_action);
        subtitle.setTextColor(inkMuted);
        subtitle.setTextSize(13);
        subtitle.setTypeface(StillwaterUi.sans(this));
        StillwaterReadable.prepareWrappingLabel(subtitle);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(StillwaterUi.CODE_BG);
        panel.setCornerRadius(dp(2));
        panel.setStroke(Math.max(1, dp(1)), border);
        details = new TextView(this);
        details.setId(R.id.tool_details);
        details.setTextSize(14);
        details.setTextColor(ink);
        details.setTypeface(StillwaterUi.mono(this));
        details.setTextIsSelectable(true);
        details.setPadding(dp(14), dp(12), dp(14), dp(12));
        details.setBackground(panel);
        scroll.addView(details);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = new TextView(this);
        status.setId(R.id.tool_status);
        status.setTextSize(13);
        status.setTypeface(StillwaterUi.sans(this));
        status.setTextColor(inkMuted);
        StillwaterReadable.prepareWrappingLabel(status);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        reject = new Button(this);
        reject.setText(R.string.reject);
        reject.setId(R.id.tool_reject);
        StillwaterUi.dangerOutlined(reject);
        StillwaterReadable.prepareWrappingControl(reject, minTouch);

        approve = new Button(this);
        approve.setText(R.string.approve);
        approve.setId(R.id.tool_approve);
        StillwaterUi.primary(approve);
        StillwaterReadable.prepareWrappingControl(approve, minTouch);

        LinearLayout.LayoutParams rejectParams = new LinearLayout.LayoutParams(0, -2, 1f);
        rejectParams.rightMargin = dp(8);
        buttons.addView(reject, rejectParams);
        buttons.addView(approve, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(buttons);

        done = new Button(this);
        done.setId(R.id.tool_done);
        done.setText(R.string.done);
        StillwaterUi.outlined(done);
        StillwaterReadable.prepareWrappingControl(done, minTouch);
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, -2);
        doneParams.topMargin = dp(8);
        done.setVisibility(View.GONE);
        root.addView(done, doneParams);

        setContentView(root);
        ToolCoordinator.Outcome outcome = coordinator.lookup(id);
        String pickedCalendarLabel = "";
        try { pickedCalendarLabel = new RuntimePreferences(this).calendarWriteLabel(); } catch (RuntimeException ignored) {}
        if (outcome.proposal != null) details.setText(ToolCoordinator.formatForDisplay(outcome.proposal.request, pickedCalendarLabel));
        else details.setText(R.string.tool_not_pending);
        show(outcome);
        approve.setOnClickListener(v -> resolve(coordinator.approve(id)));
        reject.setOnClickListener(v -> resolve(coordinator.reject(id)));
    }

    private void show(ToolCoordinator.Outcome outcome) {
        boolean pending = outcome.status.equals("PENDING_CONFIRMATION");
        approve.setEnabled(pending);
        reject.setEnabled(pending);
        done.setVisibility(pending ? View.GONE : View.VISIBLE);
        if (pending) {
            status.setText(R.string.tool_confirm_prompt);
            status.setTextColor(StillwaterUi.MUTED);
        } else {
            String msg = outcome.result == null ? "" : ToolCoordinator.stripUntrustedPrefix(outcome.result.message);
            status.setText(getString(R.string.tool_action_result_format, outcome.status, msg));
            boolean ok = outcome.result != null && outcome.result.succeeded;
            status.setTextColor(ok ? StillwaterUi.OK : StillwaterUi.DANGER);
        }
    }

    private void resolve(ToolCoordinator.Outcome outcome) {
        show(outcome);
        setResult(RESULT_OK, new Intent().putExtra("proposal_id", id));
    }

    @Override protected void onDestroy() {
        if (isFinishing() && coordinator != null && coordinator.lookup(id).status.equals("PENDING_CONFIRMATION")) {
            coordinator.reject(id);
        }
        super.onDestroy();
    }
}
