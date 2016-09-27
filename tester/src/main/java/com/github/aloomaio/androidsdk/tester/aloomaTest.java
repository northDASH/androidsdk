package com.github.aloomaio.androidsdk.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import com.github.aloomaio.androidsdk.aloomametrics.AloomaAPI;

public class AloomaTest extends Activity {

    AloomaAPI api = null;
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

    public String getToken() {
        EditText tokenPicker = (EditText) findViewById(R.id.tokenPicker);
        return tokenPicker.getText().toString();
    }

    public String getHost() {
        EditText hostPicker = (EditText) findViewById(R.id.hostPicker);
        return hostPicker.getText().toString();
    }

    public void getAPI(View view) {
        String token = getToken();
        String host = getHost();

        if ((host.isEmpty()) || token.isEmpty()) {
            AlertDialog alertDialog = new AlertDialog.Builder(AloomaTest.this).create();
            alertDialog.setTitle("SDK Not initialized");
            String message = String.format("To initialize an SDK, you must set the token and the " +
                    "host first.\nCurrent host: \"%s\"\nCurrent token: \"%s\"", host, token);
            alertDialog.setMessage(message);
            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            alertDialog.show();
        } else {
            api = AloomaAPI.getInstance(this, token, host, true);
        }
        if (null == api) {
            AlertDialog alertDialog = new AlertDialog.Builder(AloomaTest.this).create();
            alertDialog.setTitle("SDK Not initialized");
            String message = "SDK constructor returned null";
            alertDialog.setMessage(message);
            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            alertDialog.show();
        }
    }

    public void sendEvent(View view) {
        String message = "Test message " + Integer.toString(sendIndex);

        // If part of batch, add batch number
        if (null == view) {
            message = "Batch " + Integer.toString(batchIndex) + ":" + message;
        }
        api.track(message, null);
        sendIndex++;

        // Flush on each send only when not part of a batch
        if (null != view) {
            api.flush();
        }
    }

    public void sendMany(View view) {
        EditText text = (EditText) findViewById(R.id.messageCount);
        int messageCount = Integer.parseInt(text.getText().toString());
        for (int i = 0 ; i < messageCount ; i++) {
            sendEvent(null);
        }
        api.flush();
        batchIndex++;
    }
}
