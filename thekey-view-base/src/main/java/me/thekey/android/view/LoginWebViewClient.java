package me.thekey.android.view;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewParent;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import me.thekey.android.core.ArgumentUtils;
import me.thekey.android.core.TheKeyImpl;
import me.thekey.android.view.base.R;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static me.thekey.android.TheKey.PARAM_CODE;
import static me.thekey.android.TheKey.PARAM_STATE;
import static me.thekey.android.core.Constants.OAUTH_PARAM_ERROR;

@RestrictTo(LIBRARY_GROUP)
public abstract class LoginWebViewClient extends WebViewClient {
    private final Context mContext;
    private final Bundle mArgs;
    @NonNull
    protected final TheKeyImpl mTheKey;

    @Nullable
    private Reference<Activity> mActivity;

    /* various Uris used internally */
    private final Uri mLoginUri;
    private final Uri mSelfServiceUri;
    private final Uri mRedirectUri;

    protected LoginWebViewClient(@NonNull final Context context, @Nullable final Bundle args) {
        mContext = context.getApplicationContext();
        mTheKey = TheKeyImpl.getInstance(mContext);
        mArgs = args;
        mLoginUri = mTheKey.getCasUri("login");
        mSelfServiceUri = mTheKey.getCasUri("service", "selfservice");
        mRedirectUri = ArgumentUtils.getRedirectUri(mArgs, mTheKey.getDefaultRedirectUri());
    }

    public void setActivity(@Nullable final Activity activity) {
        mActivity = activity != null ? new WeakReference<>(activity) : null;
    }

    @Override
    @SuppressWarnings("checkstyle:RightCurly")
    public boolean shouldOverrideUrlLoading(final WebView view, final String uri) {
        final Uri parsedUri = Uri.parse(uri);

        // response redirect
        if (this.isRedirectUri(parsedUri)) {
            final String code = parsedUri.getQueryParameter(PARAM_CODE);
            final String state = parsedUri.getQueryParameter(PARAM_STATE);
            if (code != null) {
                onAuthorizeSuccess(parsedUri, code, state);
            } else {
                onAuthorizeError(parsedUri, parsedUri.getQueryParameter(OAUTH_PARAM_ERROR));
            }
            return true;
        }
        // CAS OAuth url
        else if (this.isOAuthUri(parsedUri)) {
            return false;
        }
        // CAS self service
        else if (ArgumentUtils.isSelfServiceEnabled(mArgs) && isSelfServiceUri(parsedUri)) {
            return false;
        }
        // external link, launch default Android activity
        else {
            // prefer using the activity is possible, but fallback to the application context object
            Context context = mActivity != null ? mActivity.get() : null;
            if (context == null) {
                context = mContext;
            }

            context.startActivity(new Intent(Intent.ACTION_VIEW, parsedUri));
            return true;
        }
    }

    @Override
    public void onPageFinished(final WebView webView, final String uri) {
        super.onPageFinished(webView, uri);

        // find the login progress view
        ViewParent parent = webView;
        View rootView = webView;
        while ((parent = parent.getParent()) != null) {
            if (parent instanceof View) {
                rootView = (View) parent;
            }
        }
        final View progress = rootView.findViewById(R.id.loginProgress);

        // toggle visibility based on redirects received
        final Uri parsedUri = Uri.parse(uri);
        if (this.isOAuthUri(parsedUri)) {
            if (progress != null) {
                // hide progress bar
                progress.setVisibility(View.GONE);
            }

            // show WebView
            webView.setVisibility(View.VISIBLE);
        } else if (this.isRedirectUri(parsedUri)) {
            if (progress != null) {
                // hide WebView
                webView.setVisibility(View.GONE);

                // show progress bar
                progress.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onReceivedSslError(final WebView view, final SslErrorHandler handler, final SslError error) {
        super.onReceivedSslError(view, handler, error);
        final Uri url = Uri.parse(error.getUrl());
        if (isOAuthUri(url)) {
            onAuthorizeError(url, null);
        }
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        onAuthorizeError(Uri.parse(failingUrl), null);
    }

    private boolean isOAuthUri(final Uri uri) {
        return isBaseUriEqual(mLoginUri, uri);
    }

    private boolean isRedirectUri(final Uri uri) {
        return isBaseUriEqual(mRedirectUri, uri) && uri.getQueryParameter(PARAM_CODE) != null;
    }

    private boolean isSelfServiceUri(final Uri uri) {
        return isBaseUriEqual(mSelfServiceUri, uri);
    }

    private boolean isBaseUriEqual(@NonNull final Uri baseUri, @NonNull final Uri uri) {
        return TextUtils.equals(uri.getScheme(), baseUri.getScheme()) &&
                TextUtils.equals(uri.getAuthority(), baseUri.getAuthority()) &&
                TextUtils.equals(uri.getPath(), baseUri.getPath());
    }

    protected abstract void onAuthorizeSuccess(@NonNull Uri uri, @NonNull String code, @Nullable String state);

    protected abstract void onAuthorizeError(@NonNull Uri uri, @Nullable String errorCode);
}
