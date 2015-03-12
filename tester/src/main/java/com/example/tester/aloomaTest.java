package com.example.tester;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import com.alooma.android.aloomametrics.AloomaAPI;
import com.mixpanel.android.mpmetrics.MixpanelAPI;


public class aloomaTest extends Activity {

    AloomaAPI api = null;
    MixpanelAPI mpapi = null;
    int sendIndex = 0, batchIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alooma_test);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_alooma_test, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


    public void getAPI(View view) {
        api = AloomaAPI.getInstance(this, "asadsa", "queen.alooma.io");
        mpapi = MixpanelAPI.getInstance(this, "3ab30a61045ae164416305f49b780df6");
    }

    public void sendEvent(View view) {
        String message = "Test message " + Integer.toString(sendIndex);
        if (null == view) {
            message = "Batch " + Integer.toString(batchIndex) + ":" + message;
        }
        api.track(message, null);
        mpapi.track(message + " mixpanel", null);
        sendIndex++;
    }

    public void sendMany(View view) {
        EditText text = (EditText) findViewById(R.id.messageCount);
        int messageCount = Integer.parseInt(text.getText().toString());
        for (int i = 0 ; i < messageCount ; i++) {
            sendEvent(null);
        }
        batchIndex++;
    }
}
