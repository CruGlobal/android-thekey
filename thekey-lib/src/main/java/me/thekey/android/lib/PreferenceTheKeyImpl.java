package me.thekey.android.lib;

import static me.thekey.android.lib.Constant.OAUTH_PARAM_ACCESS_TOKEN;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_ATTR_EMAIL;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_ATTR_FIRST_NAME;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_ATTR_GUID;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_ATTR_LAST_NAME;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_EXPIRES_IN;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_REFRESH_TOKEN;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_THEKEY_GUID;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import me.thekey.android.lib.util.BroadcastUtils;

class PreferenceTheKeyImpl extends TheKeyImpl {
    private static final String PREFFILE_THEKEY = "thekey";
    private static final String PREF_ACCESS_TOKEN = "access_token";
    private static final String PREF_EXPIRE_TIME = "expire_time";
    private static final String PREF_GUID = "guid";
    private static final String PREF_REFRESH_TOKEN = "refresh_token";
    private static final String PREF_ATTR_LOAD_TIME = "attr_load_time";
    private static final String PREF_ATTR_GUID = "attr_guid";
    private static final String PREF_ATTR_EMAIL = "attr_email";
    private static final String PREF_ATTR_FIRST_NAME = "attr_firstName";
    private static final String PREF_ATTR_LAST_NAME = "attr_lastName";

    private final Object mLockAttrs = new Object();

    PreferenceTheKeyImpl(@NonNull final Context context, @NonNull final Configuration config) {
        super(context, config);
    }

    @Nullable
    public String getDefaultSessionGuid() {
        return getPrefs().getString(PREF_GUID, null);
    }

    @NonNull
    @Override
    public Attributes getAttributes(@Nullable final String guid) {
        if (TextUtils.equals(guid, getDefaultSessionGuid())) {
            synchronized (mLockAttrs) {
                // return the attributes for the current OAuth session
                return new AttributesImpl(this.getPrefs().getAll());
            }
        } else {
            throw new UnsupportedOperationException("cannot get attributes for users other than the active session");
        }
    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(PREFFILE_THEKEY, Context.MODE_PRIVATE);
    }

    @Override
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    boolean storeGrants(@NonNull final String guid, @NonNull final JSONObject json) {
        try {
            final SharedPreferences.Editor prefs = this.getPrefs().edit();

            // store access_token
            if (json.has(OAUTH_PARAM_ACCESS_TOKEN)) {
                prefs.putString(PREF_ACCESS_TOKEN, json.getString(OAUTH_PARAM_ACCESS_TOKEN));
                prefs.remove(PREF_EXPIRE_TIME);
                if (json.has(OAUTH_PARAM_EXPIRES_IN)) {
                    prefs.putLong(PREF_EXPIRE_TIME, System.currentTimeMillis() + json.getLong(OAUTH_PARAM_EXPIRES_IN)
                            * 1000);
                }
                prefs.remove(PREF_GUID);
                if (json.has(OAUTH_PARAM_THEKEY_GUID)) {
                    prefs.putString(PREF_GUID, json.getString(OAUTH_PARAM_THEKEY_GUID));
                }
            }

            // store refresh_token
            if (json.has(OAUTH_PARAM_REFRESH_TOKEN)) {
                prefs.putString(PREF_REFRESH_TOKEN, json.getString(OAUTH_PARAM_REFRESH_TOKEN));
            }

            // we synchronize update to prevent race conditions
            synchronized (mLockAuth) {
                final String oldGuid = this.getPrefs().getString(PREF_GUID, null);
                final String newGuid = json.optString(OAUTH_PARAM_THEKEY_GUID, null);

                // store updates
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    prefs.apply();
                } else {
                    prefs.commit();
                }

                // trigger logout/login broadcasts based on guid changes
                if (oldGuid != null && !oldGuid.equals(newGuid)) {
                    BroadcastUtils.broadcastLogout(mContext, oldGuid, newGuid != null);
                }
                if (newGuid != null && !newGuid.equals(oldGuid)) {
                    BroadcastUtils.broadcastLogin(mContext, newGuid);
                }
            }
        } catch (final JSONException e) {
            clearAuthState(guid);
        }

        return true;
    }

    @Nullable
    @Override
    String getRefreshToken(@NonNull final String guid) {
        return this.getPrefs().getString(PREF_REFRESH_TOKEN, null);
    }

    @Override
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    void removeRefreshToken(@NonNull final String guid, @NonNull final String token) {
        final SharedPreferences.Editor prefs = this.getPrefs().edit();
        prefs.remove(PREF_REFRESH_TOKEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            prefs.apply();
        } else {
            prefs.commit();
        }
    }

    @Nullable
    @Override
    String getAccessToken(@NonNull final String guid) {
        final Map<String, ?> attrs = getPrefs().getAll();
        final long currentTime = System.currentTimeMillis();
        final long expireTime;
        {
            final Long v = (Long) attrs.get(PREF_EXPIRE_TIME);
            expireTime = v != null ? v : currentTime;
        }

        // return access_token only if it hasn't expired
        return expireTime >= currentTime ? (String) attrs.get(PREF_ACCESS_TOKEN) : null;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    void removeAccessToken(@NonNull final String guid, @NonNull final String token) {
        synchronized (mLockAuth) {
            if (token.equals(getPrefs().getString(PREF_ACCESS_TOKEN, null))) {
                final SharedPreferences.Editor prefs = getPrefs().edit();
                prefs.remove(PREF_ACCESS_TOKEN);
                prefs.remove(PREF_EXPIRE_TIME);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    prefs.apply();
                } else {
                    prefs.commit();
                }
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    void storeAttributes(@NonNull final String guid, @NonNull final JSONObject json) {
        final SharedPreferences.Editor prefs = getPrefs().edit();

        prefs.putLong(PREF_ATTR_LOAD_TIME, System.currentTimeMillis());
        prefs.putString(PREF_ATTR_GUID, json.optString(OAUTH_PARAM_ATTR_GUID, null));
        prefs.putString(PREF_ATTR_EMAIL, json.optString(OAUTH_PARAM_ATTR_EMAIL, null));
        prefs.putString(PREF_ATTR_FIRST_NAME, json.optString(OAUTH_PARAM_ATTR_FIRST_NAME, null));
        prefs.putString(PREF_ATTR_LAST_NAME, json.optString(OAUTH_PARAM_ATTR_LAST_NAME, null));

        // we synchronize this to prevent race conditions with getAttributes
        synchronized (mLockAttrs) {
            // store updates
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                prefs.apply();
            } else {
                prefs.commit();
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    void removeAttributes(@NonNull final String guid) {
        final SharedPreferences.Editor prefs = this.getPrefs().edit();
        prefs.remove(PREF_ATTR_GUID);
        prefs.remove(PREF_ATTR_EMAIL);
        prefs.remove(PREF_ATTR_FIRST_NAME);
        prefs.remove(PREF_ATTR_LAST_NAME);
        prefs.remove(PREF_ATTR_LOAD_TIME);

        // we synchronize this to prevent race conditions with getAttributes
        synchronized (mLockAttrs) {
            // store updates
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                prefs.apply();
            } else {
                prefs.commit();
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    void clearAuthState(@NonNull final String guid) {
        final SharedPreferences.Editor prefs = this.getPrefs().edit();
        prefs.remove(PREF_ACCESS_TOKEN);
        prefs.remove(PREF_REFRESH_TOKEN);
        prefs.remove(PREF_EXPIRE_TIME);
        prefs.remove(PREF_GUID);

        synchronized (mLockAuth) {
            // short-circuit if the specified guid is different from the stored session
            if (!TextUtils.equals(guid, getPrefs().getString(PREF_GUID, null))) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                prefs.apply();
            } else {
                prefs.commit();
            }

            // broadcast a logout action if we had a guid
            BroadcastUtils.broadcastLogout(mContext, guid, false);
        }
    }

    private static final class AttributesImpl implements Attributes {
        private final Map<String, ?> attrs;
        private final boolean valid;

        AttributesImpl(final Map<String, ?> prefsMap) {
            this.attrs = new HashMap<String, Object>(prefsMap);
            this.attrs.remove(PREF_ACCESS_TOKEN);
            this.attrs.remove(PREF_REFRESH_TOKEN);
            this.attrs.remove(PREF_EXPIRE_TIME);

            // determine if the attributes are valid
            final String guid = (String) this.attrs.get(PREF_GUID);
            this.valid = this.attrs.containsKey(PREF_ATTR_LOAD_TIME) && guid != null
                    && guid.equals(this.attrs.get(PREF_ATTR_GUID));
        }

        @Nullable
        @Override
        public String getGuid() {
            return (String) this.attrs.get(PREF_GUID);
        }

        @Override
        public boolean areValid() {
            return this.valid;
        }

        @NonNull
        @Override
        public Date getLoadedTime() {
            final Long time = this.valid ? (Long) this.attrs.get(PREF_ATTR_LOAD_TIME) : null;
            return new Date(time != null ? time : 0);
        }

        @Nullable
        @Override
        public String getEmail() {
            return this.valid ? (String) this.attrs.get(PREF_ATTR_EMAIL) : null;
        }

        @Nullable
        @Override
        public String getFirstName() {
            return this.valid ? (String) this.attrs.get(PREF_ATTR_FIRST_NAME) : null;
        }

        @Nullable
        @Override
        public String getLastName() {
            return this.valid ? (String) this.attrs.get(PREF_ATTR_LAST_NAME) : null;
        }
    }
}