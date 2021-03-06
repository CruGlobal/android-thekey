package me.thekey.android.view.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.RestrictTo;

import me.thekey.android.view.AbstractBuilder;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

public final class ActivityBuilder extends AbstractBuilder<Activity> {
    @RestrictTo(LIBRARY_GROUP)
    static final String EXTRA_ARGS = ActivityBuilder.class.getName() + ".EXTRA_ARGS";

    private final Context mContext;
    private final Intent mIntent;

    public ActivityBuilder(final Context context, final Class<? extends Activity> clazz) {
        mContext = context;
        mIntent = new Intent(context, clazz);
    }

    @Override
    public void start() {
        mIntent.putExtra(EXTRA_ARGS, mArgs);
        mContext.startActivity(mIntent);
    }
}
