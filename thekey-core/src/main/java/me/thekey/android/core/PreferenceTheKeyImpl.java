package me.thekey.android.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import me.thekey.android.Attributes;

import static android.support.annotation.RestrictTo.Scope.LIBRARY;
import static android.support.annotation.RestrictTo.Scope.SUBCLASSES;
import static me.thekey.android.core.Constants.OAUTH_PARAM_ACCESS_TOKEN;
import static me.thekey.android.core.Constants.OAUTH_PARAM_ATTR_EMAIL;
import static me.thekey.android.core.Constants.OAUTH_PARAM_ATTR_FIRST_NAME;
import static me.thekey.android.core.Constants.OAUTH_PARAM_ATTR_GUID;
import static me.thekey.android.core.Constants.OAUTH_PARAM_ATTR_LAST_NAME;
import static me.thekey.android.core.Constants.OAUTH_PARAM_EXPIRES_IN;
import static me.thekey.android.core.Constants.OAUTH_PARAM_REFRESH_TOKEN;
import static me.thekey.android.core.Constants.OAUTH_PARAM_THEKEY_GUID;
import static me.thekey.android.core.Constants.OAUTH_PARAM_THEKEY_USERNAME;

@RestrictTo(LIBRARY)
final class PreferenceTheKeyImpl extends TheKeyImpl {
    private static final String PREFFILE_THEKEY = "thekey";
    static final String PREF_ACCESS_TOKEN = "access_token";
    static final String PREF_EXPIRE_TIME = "expire_time";
    static final String PREF_USERNAME = "username";
    static final String PREF_GUID = "guid";
    static final String PREF_REFRESH_TOKEN = "refresh_token";
    static final String PREF_ATTR_LOAD_TIME = "attr_load_time";
    static final String PREF_ATTR_GUID = "attr_guid";
    static final String PREF_ATTR_EMAIL = "attr_email";
    static final String PREF_ATTR_FIRST_NAME = "attr_firstName";
    static final String PREF_ATTR_LAST_NAME = "attr_lastName";

    private final Object mLockPrefs = new Object();

    PreferenceTheKeyImpl(@NonNull final Context context, @NonNull final Configuration config) {
        super(context, config);
    }

    @NonNull
    @Override
    public Collection<String> getSessions() {
        final String guid = getSessionGuid();
        return guid != null ? Collections.singleton(guid) : Collections.<String>emptySet();
    }

    @Nullable
    private String getSessionGuid() {
        return getPrefs().getString(PREF_GUID, null);
    }

    @Override
    public boolean isValidSession(@Nullable final String guid) {
        return guid != null && guid.equals(getSessionGuid());
    }

    @NonNull
    @Override
    public Attributes getAttributes(@Nullable final String guid) {
        synchronized (mLockPrefs) {
            if (TextUtils.equals(guid, getSessionGuid())) {
                // return the attributes for the current OAuth session
                return new AttributesImpl(getPrefs().getAll());
            } else {
                throw new UnsupportedOperationException(
                        "cannot get attributes for users other than the active session");
            }
        }
    }

    @NonNull
    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(PREFFILE_THEKEY, Context.MODE_PRIVATE);
    }

    @Override
    @RestrictTo(SUBCLASSES)
    boolean storeGrants(@NonNull final String guid, @NonNull final JSONObject json) {
        try {
            final SharedPreferences.Editor prefs = this.getPrefs().edit();

            // store access_token
            if (json.has(OAUTH_PARAM_ACCESS_TOKEN)) {
                prefs.putString(PREF_ACCESS_TOKEN, json.getString(OAUTH_PARAM_ACCESS_TOKEN));
                prefs.remove(PREF_EXPIRE_TIME);
                if (json.has(OAUTH_PARAM_EXPIRES_IN)) {
                    prefs.putLong(PREF_EXPIRE_TIME,
                                  System.currentTimeMillis() + json.getLong(OAUTH_PARAM_EXPIRES_IN) * 1000);
                }
                prefs.remove(PREF_GUID);
                prefs.remove(PREF_USERNAME);
                if (json.has(OAUTH_PARAM_THEKEY_GUID)) {
                    prefs.putString(PREF_GUID, json.getString(OAUTH_PARAM_THEKEY_GUID));
                }
                if (json.has(OAUTH_PARAM_THEKEY_USERNAME)) {
                    prefs.putString(PREF_USERNAME, json.getString(OAUTH_PARAM_THEKEY_USERNAME));
                }
            }

            // store refresh_token
            if (json.has(OAUTH_PARAM_REFRESH_TOKEN)) {
                prefs.putString(PREF_REFRESH_TOKEN, json.getString(OAUTH_PARAM_REFRESH_TOKEN));
            }

            // we synchronize actual update to prevent race conditions
            final String oldGuid;
            synchronized (mLockPrefs) {
                oldGuid = getPrefs().getString(PREF_GUID, null);

                // store updates
                prefs.apply();
            }

            // trigger logout/login broadcasts based on guid changes
            final String newGuid = json.optString(OAUTH_PARAM_THEKEY_GUID, null);
            if (oldGuid != null && !oldGuid.equals(newGuid)) {
                mEventsManager.logoutEvent(oldGuid, newGuid != null);
            }
            if (newGuid != null && !newGuid.equals(oldGuid)) {
                mEventsManager.loginEvent(newGuid);
            }
        } catch (final JSONException e) {
            clearAuthState(guid, true);
        }

        return true;
    }

    @Nullable
    @Override
    String getRefreshToken(@NonNull final String guid) {
        final Map<String, ?> attrs = getPrefs().getAll();
        if (guid.equals(attrs.get(PREF_GUID))) {
            return (String) attrs.get(PREF_REFRESH_TOKEN);
        }
        return null;
    }

    @Override
    void removeRefreshToken(@NonNull final String guid, @NonNull final String token) {
        final SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.remove(PREF_REFRESH_TOKEN);

        synchronized (mLockPrefs) {
            // short-circuit if the specified guid is different from the stored session
            if (!TextUtils.equals(guid, getSessionGuid())) {
                return;
            }

            if (token.equals(getPrefs().getString(PREF_REFRESH_TOKEN, null))) {
                prefs.apply();
            }
        }
    }

    @Nullable
    @Override
    String getAccessToken(@NonNull final String guid) {
        final Map<String, ?> attrs = getPrefs().getAll();
        final long currentTime = System.currentTimeMillis();
        final Long rawExpireTime = (Long) attrs.get(PREF_EXPIRE_TIME);
        final long expireTime = rawExpireTime != null ? rawExpireTime : currentTime;

        // return access_token only if it hasn't expired (and is for the requested user)
        return expireTime >= currentTime && guid.equals(attrs.get(PREF_GUID)) ? (String) attrs.get(PREF_ACCESS_TOKEN) :
                null;
    }

    @Override
    void removeAccessToken(@NonNull final String guid, @NonNull final String token) {
        final SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.remove(PREF_ACCESS_TOKEN);
        prefs.remove(PREF_EXPIRE_TIME);

        synchronized (mLockPrefs) {
            // short-circuit if the specified guid is different from the stored session
            if (!TextUtils.equals(guid, getSessionGuid())) {
                return;
            }

            if (token.equals(getPrefs().getString(PREF_ACCESS_TOKEN, null))) {
                prefs.apply();
            }
        }
    }

    @Override
    void storeAttributes(@NonNull final String guid, @NonNull final JSONObject json) {
        final SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.putLong(PREF_ATTR_LOAD_TIME, System.currentTimeMillis());
        prefs.putString(PREF_ATTR_GUID, json.optString(OAUTH_PARAM_ATTR_GUID, null));
        prefs.putString(PREF_ATTR_EMAIL, json.optString(OAUTH_PARAM_ATTR_EMAIL, null));
        prefs.putString(PREF_ATTR_FIRST_NAME, json.optString(OAUTH_PARAM_ATTR_FIRST_NAME, null));
        prefs.putString(PREF_ATTR_LAST_NAME, json.optString(OAUTH_PARAM_ATTR_LAST_NAME, null));

        // we synchronize this to prevent race conditions with getAttributes
        synchronized (mLockPrefs) {
            // short-circuit if the specified guid is different from the stored session
            if (!TextUtils.equals(guid, getSessionGuid())) {
                return;
            }

            // store updates
            prefs.apply();
        }
    }

    @Override
    void removeAttributes(@NonNull final String guid) {
        final SharedPreferences.Editor prefs = this.getPrefs().edit();
        prefs.remove(PREF_ATTR_GUID);
        prefs.remove(PREF_ATTR_EMAIL);
        prefs.remove(PREF_ATTR_FIRST_NAME);
        prefs.remove(PREF_ATTR_LAST_NAME);
        prefs.remove(PREF_ATTR_LOAD_TIME);

        // we synchronize this to prevent race conditions with getAttributes
        synchronized (mLockPrefs) {
            // short-circuit if the specified guid is different from the stored session
            if (!TextUtils.equals(guid, getSessionGuid())) {
                return;
            }

            // store updates
            prefs.apply();
        }
    }

    @Override
    void clearAuthState(@NonNull final String guid, final boolean sendBroadcast) {
        final SharedPreferences.Editor prefs = this.getPrefs().edit();
        prefs.remove(PREF_ACCESS_TOKEN);
        prefs.remove(PREF_REFRESH_TOKEN);
        prefs.remove(PREF_EXPIRE_TIME);
        prefs.remove(PREF_GUID);
        prefs.remove(PREF_USERNAME);

        synchronized (mLockPrefs) {
            // short-circuit if the specified guid is different from the stored session
            if (!TextUtils.equals(guid, getSessionGuid())) {
                return;
            }

            prefs.apply();
        }

        if (sendBroadcast) {
            // broadcast a logout action if we had a guid
            mEventsManager.logoutEvent(guid, false);
        }
    }

    @Override
    boolean createMigratingAccount(@NonNull final MigratingAccount account) {
        if (account.isValid()) {
            final SharedPreferences.Editor prefs = getPrefs().edit();
            prefs.putString(PREF_GUID, account.guid);
            prefs.putString(PREF_USERNAME, account.attributes.getUsername());
            prefs.putString(PREF_ACCESS_TOKEN, account.accessToken);
            prefs.putString(PREF_REFRESH_TOKEN, account.refreshToken);

            prefs.putLong(PREF_ATTR_LOAD_TIME, account.attributes.getLoadedTime().getTime());
            prefs.putString(PREF_ATTR_GUID, account.guid);
            prefs.putString(PREF_ATTR_EMAIL, account.attributes.getEmail());
            prefs.putString(PREF_ATTR_FIRST_NAME, account.attributes.getFirstName());
            prefs.putString(PREF_ATTR_LAST_NAME, account.attributes.getLastName());

            prefs.apply();

            return true;
        }

        return false;
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
            this.valid = this.attrs.containsKey(PREF_ATTR_LOAD_TIME) && guid != null &&
                    guid.equals(this.attrs.get(PREF_ATTR_GUID));
        }

        @Nullable
        @Override
        public String getUsername() {
            final Object username = this.attrs.get(PREF_USERNAME);
            return username instanceof String ? (String) username : getEmail();
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