package me.thekey.android.lib;

import static me.thekey.android.lib.Constant.OAUTH_PARAM_ACCESS_TOKEN;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_ATTR_EMAIL;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_ATTR_FIRST_NAME;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_ATTR_LAST_NAME;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_REFRESH_TOKEN;
import static me.thekey.android.lib.Constant.OAUTH_PARAM_THEKEY_GUID;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Date;

import me.thekey.android.TheKeyInvalidSessionException;
import me.thekey.android.lib.util.BroadcastUtils;

public final class AccountManagerTheKeyImpl extends TheKeyImpl {
    private static final String DATA_GUID = "thekey_guid";

    private static final String DATA_ATTR_LOAD_TIME = "attr_load_time";
    private static final String DATA_ATTR_EMAIL = "attr_email";
    private static final String DATA_ATTR_FIRST_NAME = "attr_first_name";
    private static final String DATA_ATTR_LAST_NAME = "attr_last_name";

    private static final String AUTH_TOKEN_ACCESS_TOKEN = "access_token";
    private static final String AUTH_TOKEN_REFRESH_TOKEN = "refresh_token";

    @NonNull
    private final AccountManager mAccountManager;

    @NonNull
    private final String mAccountType;

    @Nullable
    private String mDefaultGuid;

    AccountManagerTheKeyImpl(@NonNull final Context context, @NonNull final Configuration config) {
        super(context, config);
        assert mConfig.mAccountType != null :
                "This object should only be created when there is an account type in the config";
        mAccountManager = AccountManager.get(context);
        mAccountType = mConfig.mAccountType;
    }

    @Override
    public void setDefaultSession(@NonNull final String guid) throws TheKeyInvalidSessionException {
        mDefaultGuid = guid;
        if (findAccount(mDefaultGuid) == null) {
            throw new TheKeyInvalidSessionException();
        }
    }

    @Nullable
    @Override
    public String getDefaultSessionGuid() {
        // check for a default guid if we don't currently have one set
        if (mDefaultGuid == null) {
            final Account[] accounts = mAccountManager.getAccountsByType(mAccountType);
            mDefaultGuid = accounts.length > 0 ? getGuid(accounts[0]) : null;
        }

        return mDefaultGuid;
    }

    @Nullable
    private String getGuid(@Nullable final Account account) {
        return account != null ? mAccountManager.getUserData(account, DATA_GUID) : null;
    }

    @Override
    public boolean isValidSession(@Nullable final String guid) {
        return guid != null && findAccount(guid) != null;
    }

    @Nullable
    private Account findAccount(@NonNull final String guid) {
        final Account[] accounts = mAccountManager.getAccountsByType(mAccountType);
        for (final Account account : accounts) {
            if (guid.equals(getGuid(account))) {
                return account;
            }
        }

        // let's reset the default guid if it matches the guid that wasn't found
        if (TextUtils.equals(guid, mDefaultGuid)) {
            mDefaultGuid = null;
        }

        return null;
    }

    @Override
    void clearAuthState(@NonNull final String guid) {
        final Account account = findAccount(guid);
        if (account != null) {
            mAccountManager.removeAccountExplicitly(account);
            if (TextUtils.equals(guid, mDefaultGuid)) {
                mDefaultGuid = null;
            }

            // broadcast a logout action since we had an account
            BroadcastUtils.broadcastLogout(mContext, guid, false);
        }
    }

    @NonNull
    @Override
    public Attributes getAttributes(@Nullable final String guid) {
        return new AttributesImpl(this, guid != null ? findAccount(guid) : null);
    }

    @Override
    void storeAttributes(@NonNull final String guid, @NonNull final JSONObject json) {
        final Account account = findAccount(guid);
        if (account != null) {
            mAccountManager.setUserData(account, DATA_ATTR_LOAD_TIME, Long.toString(System.currentTimeMillis()));
            mAccountManager.setUserData(account, DATA_ATTR_EMAIL, json.optString(OAUTH_PARAM_ATTR_EMAIL, null));
            mAccountManager.setUserData(account, DATA_ATTR_FIRST_NAME,
                                        json.optString(OAUTH_PARAM_ATTR_FIRST_NAME, null));
            mAccountManager.setUserData(account, DATA_ATTR_LAST_NAME, json.optString(OAUTH_PARAM_ATTR_LAST_NAME, null));
        }
    }

    @Override
    void removeAttributes(@NonNull final String guid) {
        final Account account = findAccount(guid);
        if (account != null) {
            mAccountManager.setUserData(account, DATA_ATTR_LOAD_TIME, null);
            mAccountManager.setUserData(account, DATA_ATTR_EMAIL, null);
            mAccountManager.setUserData(account, DATA_ATTR_FIRST_NAME, null);
            mAccountManager.setUserData(account, DATA_ATTR_LAST_NAME, null);
        }
    }

    @Override
    boolean storeGrants(@NonNull final String guid, @NonNull final JSONObject json) {
        // short-circuit if this grant is for a different user
        if (!TextUtils.equals(guid, json.optString(OAUTH_PARAM_THEKEY_GUID, null))) {
            return false;
        }

        // create account if it doesn't already exist
        Account account = findAccount(guid);
        boolean broadcastLogin = false;
        if (account == null) {
            // create a new account
            //TODO: use username for the account
            account = new Account(guid, mAccountType);
            final Bundle data = new Bundle(1);
            data.putString(DATA_GUID, guid);
            mAccountManager.addAccountExplicitly(account, null, data);
            broadcastLogin = true;
        }

        // store access_token
        if (json.has(OAUTH_PARAM_ACCESS_TOKEN)) {
            mAccountManager.setAuthToken(account, AUTH_TOKEN_ACCESS_TOKEN,
                                         json.optString(OAUTH_PARAM_ACCESS_TOKEN, null));
            //TODO: store expiration time for access_token?
//                if (json.has(OAUTH_PARAM_EXPIRES_IN)) {
//                    final long expireTime =
//                            System.currentTimeMillis() + json.getLong(OAUTH_PARAM_EXPIRES_IN) * 1000;
//                }
        }

        // store refresh_token
        if (json.has(OAUTH_PARAM_REFRESH_TOKEN)) {
            mAccountManager.setAuthToken(account, AUTH_TOKEN_REFRESH_TOKEN,
                                         json.optString(OAUTH_PARAM_REFRESH_TOKEN, null));
        }

        if (broadcastLogin) {
            BroadcastUtils.broadcastLogin(mContext, guid);
        }

        return true;
    }

    @Nullable
    @Override
    String getAccessToken(@NonNull final String guid) {
        final Account account = findAccount(guid);
        if (account != null) {
            try {
                return mAccountManager.blockingGetAuthToken(account, AUTH_TOKEN_ACCESS_TOKEN, false);
            } catch (final Exception ignored) {
            }
        }
        return null;
    }

    @Override
    void removeAccessToken(@NonNull final String guid, @NonNull final String token) {
        final Account account = findAccount(guid);
        if (account != null) {
            mAccountManager.invalidateAuthToken(mAccountType, token);
        }
    }

    @Nullable
    @Override
    String getRefreshToken(@NonNull final String guid) {
        final Account account = findAccount(guid);
        if (account != null) {
            try {
                return mAccountManager.blockingGetAuthToken(account, AUTH_TOKEN_REFRESH_TOKEN, false);
            } catch (final Exception ignored) {
            }
        }
        return null;
    }

    @Override
    void removeRefreshToken(@NonNull final String guid, @NonNull final String token) {
        final Account account = findAccount(guid);
        if (account != null) {
            mAccountManager.invalidateAuthToken(mAccountType, token);
        }
    }

    private static final class AttributesImpl implements Attributes {
        @Nullable
        private final String mGuid;
        private final boolean mValid;
        @NonNull
        private final Date mLoadedTime;
        @Nullable
        private final String mEmail;
        @Nullable
        private final String mFirstName;
        @Nullable
        private final String mLastName;

        AttributesImpl(@NonNull final AccountManagerTheKeyImpl theKey, @Nullable final Account account) {
            mGuid = theKey.getGuid(account);
            mValid = account != null;
            if (mValid) {
                final AccountManager manager = theKey.mAccountManager;
                long loadedTime;
                try {
                    loadedTime = Long.parseLong(manager.getUserData(account, DATA_ATTR_LOAD_TIME));
                } catch (final Exception e) {
                    loadedTime = 0;
                }
                mLoadedTime = new Date(loadedTime);
                mEmail = manager.getUserData(account, DATA_ATTR_EMAIL);
                mFirstName = manager.getUserData(account, DATA_ATTR_FIRST_NAME);
                mLastName = manager.getUserData(account, DATA_ATTR_LAST_NAME);
            } else {
                mLoadedTime = new Date(0);
                mEmail = null;
                mFirstName = null;
                mLastName = null;
            }
        }

        @Nullable
        @Override
        public String getGuid() {
            return mGuid;
        }

        @Override
        public boolean areValid() {
            return mValid;
        }

        @NonNull
        @Override
        public Date getLoadedTime() {
            return mLoadedTime;
        }

        @Nullable
        @Override
        public String getEmail() {
            return mEmail;
        }

        @Nullable
        @Override
        public String getFirstName() {
            return mFirstName;
        }

        @Nullable
        @Override
        public String getLastName() {
            return mLastName;
        }
    }
}