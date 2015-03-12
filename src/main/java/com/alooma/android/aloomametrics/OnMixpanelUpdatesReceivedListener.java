package com.alooma.android.aloomametrics;

/**
 * For use with {@link AloomaAPI.People#addOnMixpanelUpdatesReceivedListener(OnMixpanelUpdatesReceivedListener)}
 */
/* package */ interface OnMixpanelUpdatesReceivedListener {
    /**
     * Called when the Mixpanel library has updates, for example, Surveys or Notifications.
     * This method will not be called once per update, but rather any time a batch of updates
     * becomes available. The related updates can be checked with
     * {@link AloomaAPI.People#getSurveyIfAvailable()} or {@link AloomaAPI.People#getNotificationIfAvailable()}
     */
    public void onMixpanelUpdatesReceived();
}
