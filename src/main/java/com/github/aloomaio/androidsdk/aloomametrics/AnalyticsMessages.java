package com.github.aloomaio.androidsdk.aloomametrics;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.github.aloomaio.androidsdk.util.Base64Coder;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Manage communication of events with the internal database and the Mixpanel servers.
 *
 * <p>This class straddles the thread boundary between user threads and
 * a logical Mixpanel thread.
 */
/* package */ class AnalyticsMessages {

    private String mAloomaHost;

    public void forceSSL(boolean mForceSSL) {
        mSchema = mForceSSL? "https" : "http";
    }

    private String mSchema;

    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(final Context context) {
        this(context, null, false);
    }

    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(final Context context, String aloomaHost, boolean forceSSL) {
        mContext = context;
        mAloomaHost = (null == aloomaHost) ? DEFAULT_ALOOMA_HOST : aloomaHost;
        forceSSL(forceSSL);
        mConfig = getConfig(context);
        mWorker = new Worker();
    }

    public static AnalyticsMessages getInstance(final Context messageContext) {
        return getInstance(messageContext, null, true);
    }

    public static AnalyticsMessages getInstance(final Context messageContext,
                                                String aloomaHost) {
        return getInstance(messageContext, aloomaHost, true);
    }

    /**
     * Returns an AnalyticsMessages instance with configurable forceSSL attribute
     */
    public static AnalyticsMessages getInstance(final Context messageContext,
                                                String aloomaHost,
                                                boolean forceSSL) {
        synchronized (sInstances) {
            final Context appContext = messageContext.getApplicationContext();
            AnalyticsMessages ret;
            if (! sInstances.containsKey(appContext)) {
                ret = new AnalyticsMessages(appContext, aloomaHost, forceSSL);
                sInstances.put(appContext, ret);
            }
            else {
                ret = sInstances.get(appContext);
            }
            return ret;
        }
    }

    public void eventsMessage(final EventDescription eventDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventDescription;
        mWorker.runMessage(m);
    }

    // Must be thread safe.
    public void peopleMessage(final JSONObject peopleJson) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_PEOPLE;
        m.obj = peopleJson;

        mWorker.runMessage(m);
    }

    public void postToServer() {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;

        mWorker.runMessage(m);
    }

    public void installDecideCheck(final DecideMessages check) {
        final Message m = Message.obtain();
        m.what = INSTALL_DECIDE_CHECK;
        m.obj = check;

        mWorker.runMessage(m);
    }

    public void registerForGCM(final String senderID) {
        final Message m = Message.obtain();
        m.what = REGISTER_FOR_GCM;
        m.obj = senderID;

        mWorker.runMessage(m);
    }

    public void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;

        mWorker.runMessage(m);
    }

    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }

    protected ADbAdapter makeDbAdapter(Context context) {
        return new ADbAdapter(context);
    }

    protected AConfig getConfig(Context context) {
        return AConfig.getInstance(context);
    }

    protected ServerMessage getPoster() {
        return new ServerMessage();
    }

    ////////////////////////////////////////////////////

    static class EventDescription {
        public EventDescription(String eventName, JSONObject properties, String token) {
            this.eventName = eventName;
            this.properties = properties;
            this.token = token;
        }

        public String getEventName() {
            return eventName;
        }

        public JSONObject getProperties() {
            return properties;
        }

        public String getToken() {
            return token;
        }

        private final String eventName;
        private final JSONObject properties;
        private final String token;
    }

    // Sends a message if and only if we are running with alooma Message log enabled.
    // Will be called from the alooma thread.
    private void logAboutMessageToAlooma(String message) {
        if (AConfig.DEBUG) {
            Log.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
        }
    }

    private void logAboutMessageToAlooma(String message, Throwable e) {
        if (AConfig.DEBUG) {
            Log.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")", e);
        }
    }

    // Worker will manage the (at most single) IO thread associated with
    // this AnalyticsMessages instance.
    // XXX: Worker class is unnecessary, should be just a subclass of HandlerThread
    private class Worker {
        public Worker() {
            mHandler = restartWorkerThread();
        }

        public boolean isDead() {
            synchronized(mHandlerLock) {
                return mHandler == null;
            }
        }

        public void runMessage(Message msg) {
            synchronized(mHandlerLock) {
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    logAboutMessageToAlooma("Dead alooma worker dropping a message: " + msg.what);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        // NOTE that the returned worker will run FOREVER, unless you send a hard kill
        // (which you really shouldn't)
        private Handler restartWorkerThread() {
            final HandlerThread thread = new HandlerThread("com.alooma.android.AnalyticsWorker", Thread.MIN_PRIORITY);
            thread.start();
            final Handler ret = new AnalyticsMessageHandler(thread.getLooper());
            return ret;
        }

        private class AnalyticsMessageHandler extends Handler {
            public AnalyticsMessageHandler(Looper looper) {
                super(looper);
                mDbAdapter = null;
                mDecideChecker = new DecideChecker(mContext, mConfig);
                mDisableFallback = mConfig.getDisableFallback();
                mFlushInterval = mConfig.getFlushInterval();
                mSystemInformation = new SystemInformation(mContext);
            }

            @Override
            public void handleMessage(Message msg) {
                if (mDbAdapter == null) {
                    mDbAdapter = makeDbAdapter(mContext);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), ADbAdapter.Table.EVENTS);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), ADbAdapter.Table.PEOPLE);
                }

                try {
                    int queueDepth = -1;

                    if (msg.what == ENQUEUE_PEOPLE) {
                        final JSONObject message = (JSONObject) msg.obj;

                        logAboutMessageToAlooma("Queuing people record for sending later");
                        logAboutMessageToAlooma("    " + message.toString());

                        queueDepth = mDbAdapter.addJSON(message, ADbAdapter.Table.PEOPLE);
                    }
                    else if (msg.what == ENQUEUE_EVENTS) {
                        final EventDescription eventDescription = (EventDescription) msg.obj;
                        try {
                            final JSONObject message = prepareEventObject(eventDescription);
                            logAboutMessageToAlooma("Queuing event for sending later");
                            logAboutMessageToAlooma("    " + message.toString());
                            queueDepth = mDbAdapter.addJSON(message, ADbAdapter.Table.EVENTS);
                        } catch (final JSONException e) {
                            Log.e(LOGTAG, "Exception tracking event " + eventDescription.getEventName(), e);
                        }
                    }
                    else if (msg.what == FLUSH_QUEUE) {
                        logAboutMessageToAlooma("Flushing queue due to scheduled or forced flush");
                        updateFlushFrequency();
                        sendAllData(mDbAdapter);
                        mDecideChecker.runDecideChecks(getPoster());
                    }
                    else if (msg.what == INSTALL_DECIDE_CHECK) {
                        logAboutMessageToAlooma("Installing a check for surveys and in app notifications");
                        final DecideMessages check = (DecideMessages) msg.obj;
                        mDecideChecker.addDecideCheck(check);
                        mDecideChecker.runDecideChecks(getPoster());
                    }
                    else if (msg.what == REGISTER_FOR_GCM) {
                        final String senderId = (String) msg.obj;
                        runGCMRegistration(senderId);
                    }
                    else if (msg.what == KILL_WORKER) {
                        Log.w(LOGTAG, "Worker received a hard kill. Dumping all events and force-killing. Thread id " + Thread.currentThread().getId());
                        synchronized(mHandlerLock) {
                            mDbAdapter.deleteDB();
                            mHandler = null;
                            Looper.myLooper().quit();
                        }
                    } else {
                        Log.e(LOGTAG, "Unexpected message received by Alooma worker: " + msg);
                    }

                    ///////////////////////////

                    if (queueDepth >= mConfig.getBulkUploadLimit()) {
                        logAboutMessageToAlooma("Flushing queue due to bulk upload limit");
                        updateFlushFrequency();
                        sendAllData(mDbAdapter);
                        mDecideChecker.runDecideChecks(getPoster());
                    } else if (queueDepth > 0 && !hasMessages(FLUSH_QUEUE)) {
                        // The !hasMessages(FLUSH_QUEUE) check is a courtesy for the common case
                        // of delayed flushes already enqueued from inside of this thread.
                        // Callers outside of this thread can still send
                        // a flush right here, so we may end up with two flushes
                        // in our queue, but we're OK with that.

                        logAboutMessageToAlooma("Queue depth " + queueDepth + " - Adding flush in " + mFlushInterval);
                        if (mFlushInterval >= 0) {
                            sendEmptyMessageDelayed(FLUSH_QUEUE, mFlushInterval);
                        }
                    }
                } catch (final RuntimeException e) {
                    Log.e(LOGTAG, "Worker threw an unhandled exception", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            Looper.myLooper().quit();
                            Log.e(LOGTAG, "Alooma will not process any more analytics messages", e);
                        } catch (final Exception tooLate) {
                            Log.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                }
            }// handleMessage

            private void runGCMRegistration(String senderID) {
                final String registrationId;
                try {
                    // We don't actually require Google Play Services to be available
                    // (since we can't specify what version customers will be using,
                    // and because the latest Google Play Services actually have
                    // dependencies on Java 7)

                    // Consider adding a transitive dependency on the latest
                    // Google Play Services version and requiring Java 1.7
                    // in the next major library release.
                    try {
                        final int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(mContext);
                        if (resultCode != ConnectionResult.SUCCESS) {
                            Log.i(LOGTAG, "Can't register for push notifications, Google Play Services are not installed.");
                            return;
                        }
                    } catch (RuntimeException e) {
                        Log.i(LOGTAG, "Can't register for push notifications, Google Play services are not configured.");
                        return;
                    }


                    final GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(mContext);
                    registrationId = gcm.register(senderID);
                } catch (IOException e) {
                    Log.i(LOGTAG, "Exception when trying to register for GCM", e);
                    return;
                } catch (NoClassDefFoundError e) {
                    Log.w(LOGTAG, "Google play services were not part of this build, push notifications cannot be registered or delivered");
                    return;
                }

                AloomaAPI.allInstances(new AloomaAPI.InstanceProcessor() {
                    @Override
                    public void process(AloomaAPI api) {
                        if (AConfig.DEBUG) {
                            Log.v(LOGTAG, "Using existing pushId " + registrationId);
                        }
                        api.getPeople().setPushRegistrationId(registrationId);
                    }
                });
            }

            private void sendAllData(ADbAdapter dbAdapter) {
                final ServerMessage poster = getPoster();
                if (! poster.isOnline(mContext)) {
                    logAboutMessageToAlooma("Not flushing data to alooma because the device is not connected to the internet.");
                    return;
                }

                logAboutMessageToAlooma("Sending records to alooma");
                sendData(dbAdapter, ADbAdapter.Table.EVENTS, new String[]{ mSchema + "://" + mAloomaHost + "/track?ip=1" });
            }

            private void sendData(ADbAdapter dbAdapter, ADbAdapter.Table table, String[] urls) {
                final ServerMessage poster = getPoster();
                final String[] eventsData = dbAdapter.generateDataString(table);

                if (eventsData != null) {
                    final String lastId = eventsData[0];
                    final String rawMessage = eventsData[1];

                    final String encodedData = Base64Coder.encodeString(rawMessage);
                    final List<NameValuePair> params = new ArrayList<NameValuePair>(1);
                    params.add(new BasicNameValuePair("data", encodedData));
                    if (AConfig.DEBUG) {
                        params.add(new BasicNameValuePair("verbose", "1"));
                    }

                    boolean deleteEvents = true;
                    byte[] response;
                    for (String url : urls) {
                        try {
                            response = poster.performRequest(url, params);
                            deleteEvents = true; // Delete events on any successful post, regardless of 1 or 0 response
                            if (null == response) {
                                logAboutMessageToAlooma("Response was null, unexpected failure posting to " + url + ".");
                            } else {
                                String parsedResponse;
                                try {
                                    parsedResponse = new String(response, "UTF-8");
                                } catch (UnsupportedEncodingException e) {
                                    throw new RuntimeException("UTF not supported on this platform?", e);
                                }

                                logAboutMessageToAlooma("Successfully posted to " + url + ": \n" + rawMessage);
                                logAboutMessageToAlooma("Response was " + parsedResponse);
                            }
                            break;
                        } catch (final OutOfMemoryError e) {
                            Log.e(LOGTAG, "Out of memory when posting to " + url + ".", e);
                            break;
                        } catch (final MalformedURLException e) {
                            Log.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
                            break;
                        } catch (final IOException e) {
                            logAboutMessageToAlooma("Cannot post message to " + url + ".", e);
                            deleteEvents = false;
                        }
                    }

                    if (deleteEvents) {
                        logAboutMessageToAlooma("Not retrying this batch of events, deleting them from DB.");
                        dbAdapter.cleanupEvents(lastId, table);
                    } else {
                        logAboutMessageToAlooma("Retrying this batch of events.");
                        if (!hasMessages(FLUSH_QUEUE)) {
                            sendEmptyMessageDelayed(FLUSH_QUEUE, mFlushInterval);
                        }
                    }
                }
            }

            private JSONObject getDefaultEventProperties()
                    throws JSONException {
                final JSONObject ret = new JSONObject();

                ret.put("alooma_sdk", "android");
                ret.put("$lib_version", AConfig.VERSION);

                // For querying together with data from other libraries
                ret.put("$os", "Android");
                ret.put("$os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);

                ret.put("$manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
                ret.put("$brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
                ret.put("$model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);

                try {
                    try {
                        final int servicesAvailable = GooglePlayServicesUtil.isGooglePlayServicesAvailable(mContext);
                        switch (servicesAvailable) {
                            case ConnectionResult.SUCCESS:
                                ret.put("$google_play_services", "available");
                                break;
                            case ConnectionResult.SERVICE_MISSING:
                                ret.put("$google_play_services", "missing");
                                break;
                            case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED:
                                ret.put("$google_play_services", "out of date");
                                break;
                            case ConnectionResult.SERVICE_DISABLED:
                                ret.put("$google_play_services", "disabled");
                                break;
                            case ConnectionResult.SERVICE_INVALID:
                                ret.put("$google_play_services", "invalid");
                                break;
                        }
                    } catch (RuntimeException e) {
                        // Turns out even checking for the service will cause explosions
                        // unless we've set up meta-data
                        ret.put("$google_play_services", "not configured");
                    }

                } catch (NoClassDefFoundError e) {
                    ret.put("$google_play_services", "not included");
                }

                final DisplayMetrics displayMetrics = mSystemInformation.getDisplayMetrics();
                ret.put("$screen_dpi", displayMetrics.densityDpi);
                ret.put("$screen_height", displayMetrics.heightPixels);
                ret.put("$screen_width", displayMetrics.widthPixels);

                final String applicationVersionName = mSystemInformation.getAppVersionName();
                if (null != applicationVersionName)
                    ret.put("$app_version", applicationVersionName);

                final Boolean hasNFC = mSystemInformation.hasNFC();
                if (null != hasNFC)
                    ret.put("$has_nfc", hasNFC.booleanValue());

                final Boolean hasTelephony = mSystemInformation.hasTelephony();
                if (null != hasTelephony)
                    ret.put("$has_telephone", hasTelephony.booleanValue());

                final String carrier = mSystemInformation.getCurrentNetworkOperator();
                if (null != carrier)
                    ret.put("$carrier", carrier);

                final Boolean isWifi = mSystemInformation.isWifiConnected();
                if (null != isWifi)
                    ret.put("$wifi", isWifi.booleanValue());

                final Boolean isBluetoothEnabled = mSystemInformation.isBluetoothEnabled();
                if (isBluetoothEnabled != null)
                    ret.put("$bluetooth_enabled", isBluetoothEnabled);

                final String bluetoothVersion = mSystemInformation.getBluetoothVersion();
                if (bluetoothVersion != null)
                    ret.put("$bluetooth_version", bluetoothVersion);

                return ret;
            }

            private JSONObject prepareEventObject(EventDescription eventDescription) throws JSONException {
                final JSONObject eventObj = new JSONObject();
                final JSONObject eventProperties = eventDescription.getProperties();
                final JSONObject sendProperties = getDefaultEventProperties();

                if (eventProperties != null) {
                    for (final Iterator<?> iter = eventProperties.keys(); iter.hasNext();) {
                        final String key = (String) iter.next();
                        sendProperties.put(key, eventProperties.get(key));
                    }
                }

                String token = "no token";
                try {
                    token = sendProperties.getString("token");
                    sendProperties.remove("token");
                } catch (JSONException ignored) { }
                JSONObject props = new JSONObject();
                props.put("token", token);

                for (final Iterator<?> iter = sendProperties.keys(); iter.hasNext();) {
                    final String key = (String) iter.next();
                    eventObj.put(key, sendProperties.get(key));
                }

                eventObj.put("event", eventDescription.getEventName());
                eventObj.put("properties", props);
                return eventObj;
            }

            private ADbAdapter mDbAdapter;
            private final DecideChecker mDecideChecker;
            private final long mFlushInterval;
            private final boolean mDisableFallback;
        }// AnalyticsMessageHandler

        private void updateFlushFrequency() {
            final long now = System.currentTimeMillis();
            final long newFlushCount = mFlushCount + 1;

            if (mLastFlushTime > 0) {
                final long flushInterval = now - mLastFlushTime;
                final long totalFlushTime = flushInterval + (mAveFlushFrequency * mFlushCount);
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                final long seconds = mAveFlushFrequency / 1000;
                logAboutMessageToAlooma("Average send frequency approximately " + seconds + " seconds.");
            }

            mLastFlushTime = now;
            mFlushCount = newFlushCount;
        }

        private final Object mHandlerLock = new Object();
        private Handler mHandler;
        private long mFlushCount = 0;
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
        private SystemInformation mSystemInformation;
    }

    /////////////////////////////////////////////////////////

    // Used across thread boundaries
    private final Worker mWorker;
    private final Context mContext;
    private final AConfig mConfig;

    // Messages for our thread
    private static int ENQUEUE_PEOPLE = 0; // submit events and people data
    private static int ENQUEUE_EVENTS = 1; // push given JSON message to people DB
    private static int FLUSH_QUEUE = 2; // push given JSON message to events DB
    private static int KILL_WORKER = 5; // Hard-kill the worker thread, discarding all events on the event queue. This is for testing, or disasters.
    private static int INSTALL_DECIDE_CHECK = 12; // Run this DecideCheck at intervals until it isDestroyed()
    private static int REGISTER_FOR_GCM = 13; // Register for GCM using Google Play Services

    private static final String LOGTAG = "AloomaAPI.AnalyticsMessages";

    private static final Map<Context, AnalyticsMessages> sInstances = new HashMap<Context, AnalyticsMessages>();
    private final String DEFAULT_ALOOMA_HOST = "inputs.alooma.com";

}
