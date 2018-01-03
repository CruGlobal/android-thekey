package me.thekey.android;

import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;

interface TheKeyTokenApi {
    /**
     * Process an OAuth code grant request. This method is blocking, do not call it from the UI thread.
     *
     * @param code        The authorization code being processed
     * @param redirectUri The redirect_uri the authorization code was issued for
     * @return The guid the code grant was successfully processed for, null if there was an error.
     */
    @WorkerThread
    String processCodeGrant(@NonNull String code, @NonNull Uri redirectUri) throws TheKeySocketException;
}
