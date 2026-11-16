package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.*;

/** Internal foreground consent UI. Intents carry only a random proposal ID, never editable tool arguments. */
public final class ToolConfirmationActivity extends Activity {
    private ToolCoordinator coordinator;private String id;
    private TextView details,status;private Button approve,reject;
    private float attachedInterfaceScale = InterfaceSize.DEFAULT;

    @Override protected void attachBaseContext(Context newBase) {
        attachedInterfaceScale = InterfaceSize.read(newBase);
        super.attachBaseContext(InterfaceSize.wrap(newBase));
    }

    @Override protected void onResume() {
        super.onResume();
        InterfaceSize.recreateIfChanged(this, attachedInterfaceScale);
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        coordinator=((Lfm3PocketApplication)getApplication()).toolCoordinator();
        id=getIntent().getStringExtra("proposal_id");
        int surface=StillwaterUi.SURFACE;
        int raised=StillwaterUi.RAISED;
        int ink=StillwaterUi.INK;
        int inkMuted=StillwaterUi.MUTED;
        int border=StillwaterUi.LINE;

        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        int padding=(int)(20*getResources().getDisplayMetrics().density);root.setPadding(padding,padding,padding,padding);
        TextView heading=new TextView(this);heading.setText(R.string.android_action);heading.setTextSize(28);
        heading.setTextColor(ink);heading.setTypeface(StillwaterUi.serif(this));
        StillwaterReadable.prepareWrappingLabel(heading);root.addView(heading);
        TextView subtitle=new TextView(this);
        subtitle.setText(R.string.approve_exact_action);
        subtitle.setTextColor(inkMuted);subtitle.setTextSize(13);
        StillwaterReadable.prepareWrappingLabel(subtitle);
        subtitle.setPadding(0,padding/3,0,padding/2);root.addView(subtitle);

        ScrollView scroll=new ScrollView(this);
        GradientDrawable panel=new GradientDrawable();
        panel.setColor(raised);panel.setCornerRadius(8*getResources().getDisplayMetrics().density);
        panel.setStroke(Math.max(1,(int)getResources().getDisplayMetrics().density),border);
        details=new TextView(this);details.setId(R.id.tool_details);
        details.setTextSize(15);details.setTextColor(ink);details.setTypeface(StillwaterUi.mono(this));
        details.setPadding(padding/2,padding/2,padding/2,padding/2);
        details.setBackground(panel);
        scroll.addView(details);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        status=new TextView(this);status.setId(R.id.tool_status);status.setTextColor(inkMuted);
        status.setPadding(0,padding/2,0,padding/2);root.addView(status);
        LinearLayout buttons=new LinearLayout(this);
        reject=new Button(this);reject.setText(R.string.reject);reject.setId(R.id.tool_reject);
        StillwaterUi.dangerQuiet(reject);
        StillwaterReadable.prepareWrappingControl(reject, (int)(44*getResources().getDisplayMetrics().density));
        android.graphics.drawable.GradientDrawable rejectStroke=new android.graphics.drawable.GradientDrawable();
        rejectStroke.setColor(surface);rejectStroke.setStroke(Math.max(1,(int)getResources().getDisplayMetrics().density),StillwaterUi.DANGER);
        reject.setBackground(rejectStroke);
        approve=new Button(this);approve.setText(R.string.approve);approve.setId(R.id.tool_approve);
        StillwaterUi.primary(approve);
        StillwaterReadable.prepareWrappingControl(approve, (int)(44*getResources().getDisplayMetrics().density));
        buttons.addView(reject,new LinearLayout.LayoutParams(0,-2,1f));
        buttons.addView(approve,new LinearLayout.LayoutParams(0,-2,1f));
        root.addView(buttons);setContentView(root);
        ToolCoordinator.Outcome outcome=coordinator.lookup(id);
        if(outcome.proposal!=null)details.setText(ToolCoordinator.describe(outcome.proposal.request));
        else details.setText("This action is no longer pending.");
        show(outcome);
        approve.setOnClickListener(v->resolve(coordinator.approve(id)));
        reject.setOnClickListener(v->resolve(coordinator.reject(id)));
    }
    private void show(ToolCoordinator.Outcome outcome){
        boolean pending=outcome.status.equals("PENDING_CONFIRMATION");approve.setEnabled(pending);reject.setEnabled(pending);
        status.setText(pending?"Approve only if you want the exact action and arguments shown above."
                :"Action result: "+outcome.status+" — "+(outcome.result==null?"":outcome.result.message));
    }
    private void resolve(ToolCoordinator.Outcome outcome){
        show(outcome);setResult(RESULT_OK,new Intent().putExtra("proposal_id",id));
    }
    @Override protected void onDestroy(){
        if(isFinishing() && coordinator!=null && coordinator.lookup(id).status.equals("PENDING_CONFIRMATION"))coordinator.reject(id);
        super.onDestroy();
    }
}
