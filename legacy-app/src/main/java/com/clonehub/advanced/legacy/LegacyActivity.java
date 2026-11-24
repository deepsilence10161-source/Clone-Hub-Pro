package com.clonehub.advanced.legacy;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** The old artifact's only screen. It reports its own identity and nothing else. */
public class LegacyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("LEGACY_CLONE_HUB\npackage=" + getPackageName() + "\nversion=2.0.1 (2001)");
        view.setTextSize(18f);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(20, 20, 24));
        setContentView(view);
    }
}
