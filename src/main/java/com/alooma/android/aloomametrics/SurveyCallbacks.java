package com.alooma.android.aloomametrics;

/**
 * This interface is only used with deprecated APIs, and should not be used in new code.
 * Use {@link AloomaAPI.People#getSurveyIfAvailable()} instead.
 *
 * @deprecated since 4.0.1
 */
@Deprecated
public interface SurveyCallbacks {
    /**
     * @deprecated since v4.0.1
     */
    @Deprecated
    public void foundSurvey(Survey s);
}
