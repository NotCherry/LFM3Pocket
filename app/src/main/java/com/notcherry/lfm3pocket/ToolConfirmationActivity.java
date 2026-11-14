package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.*;

/** Internal foreground consent UI. Intents carry only a random proposal ID, never editable tool arguments. */
public final class ToolConfirmationActivity extends Activity {
    private ToolCoordinator coordinator;private String id;
    private TextView details,status;private Button approve,reject;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        coordinator=((Lfm3PocketApplication)getApplication()).toolCoordinator();
        id=getIntent().getStringExtra("proposal_id");
        int surface=Color.parseColor("#0E1116");
        int raised=Color.parseColor("#151A21");
        int ink=Color.parseColor("#E8EAED");
        int inkMuted=Color.parseColor("#9AA3AD");
        int border=Color.parseColor("#2A3340");
        int accent=Color.parseColor("#5B8FA8");
        int danger=Color.parseColor("#C45B5B");

        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        int padding=(int)(20*getResources().getDisplayMetrics().density);root.setPadding(padding,padding,padding,padding);
        TextView heading=new TextView(this);heading.setText("Review Android action");heading.setTextSize(22);
        heading.setTextColor(ink);heading.setTypeface(null,1);root.addView(heading);
        TextView subtitle=new TextView(this);
        subtitle.setText("Approve only the exact action and arguments shown below.");
        subtitle.setTextColor(inkMuted);subtitle.setTextSize(13);
        subtitle.setPadding(0,padding/3,0,padding/2);root.addView(subtitle);

        ScrollView scroll=new ScrollView(this);
        GradientDrawable panel=new GradientDrawable();
        panel.setColor(raised);panel.setCornerRadius(8*getResources().getDisplayMetrics().density);
        panel.setStroke(Math.max(1,(int)getResources().getDisplayMetrics().density),border);
        details=new TextView(this);details.setId(R.id.tool_details);
        details.setTextSize(15);details.setTextColor(ink);details.setTypeface(android.graphics.Typeface.MONOSPACE);
        details.setPadding(padding/2,padding/2,padding/2,padding/2);
        details.setBackground(panel);
        scroll.addView(details);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        status=new TextView(this);status.setId(R.id.tool_status);status.setTextColor(inkMuted);
        status.setPadding(0,padding/2,0,padding/2);root.addView(status);
        LinearLayout buttons=new LinearLayout(this);
        reject=new Button(this);reject.setText("Reject");reject.setAllCaps(false);reject.setId(R.id.tool_reject);
        reject.setTextColor(ink);reject.setBackgroundColor(danger);
        approve=new Button(this);approve.setText("Approve");approve.setAllCaps(false);approve.setId(R.id.tool_approve);
        approve.setTextColor(surface);approve.setBackgroundColor(accent);
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
