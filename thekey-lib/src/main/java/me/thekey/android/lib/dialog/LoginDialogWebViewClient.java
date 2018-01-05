package me.thekey.android.lib.dialog;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import me.thekey.android.lib.LoginWebViewClient;
import me.thekey.android.lib.fragment.DialogFragment;

public class LoginDialogWebViewClient extends LoginWebViewClient {
    private final DialogFragment mDialog;

    public LoginDialogWebViewClient(final DialogFragment dialog, final Bundle args) {
        super(dialog.getActivity(), args);
        mDialog = dialog;
    }

    @Override
    protected void onAuthorizeSuccess(@NonNull final Uri uri, @NonNull final String code,
                                      @Nullable final String state) {
        new LoginDialogCodeGrantAsyncTask(mDialog, mTheKey, code, state).execute();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void onAuthorizeError(final Uri uri, final String errorCode) {
        final Activity activity = mDialog.getActivity();
        if (activity instanceof LoginDialogListener) {
            ((LoginDialogListener<DialogFragment>) activity).onLoginFailure(mDialog);
        }

        // close the dialog if it is still active (added to the activity)
        if (mDialog.isAdded()) {
            mDialog.dismissAllowingStateLoss();
        }
    }
}
