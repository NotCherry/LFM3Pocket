package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.Intent;
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
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(20*getResources().getDisplayMetrics().density);root.setPadding(padding,padding,padding,padding);
        TextView heading=new TextView(this);heading.setText("Review Android action");heading.setTextSize(22);root.addView(heading);
        ScrollView scroll=new ScrollView(this);details=new TextView(this);details.setId(R.id.tool_details);
        details.setTextSize(17);scroll.addView(details);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=new TextView(this);status.setId(R.id.tool_status);root.addView(status);
        LinearLayout buttons=new LinearLayout(this);
        reject=new Button(this);reject.setText("Reject");reject.setAllCaps(false);reject.setId(R.id.tool_reject);
        approve=new Button(this);approve.setText("Approve");approve.setAllCaps(false);approve.setId(R.id.tool_approve);
        buttons.addView(reject);buttons.addView(approve);root.addView(buttons);setContentView(root);
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
