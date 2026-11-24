package com.clonehub.advanced.migrate;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Reports its own identity; the gate reads it from the package manager instead. */
public class MigrateActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("MIGRATE_PROBE\npackage=" + getPackageName());
        view.setTextSize(18f);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(18, 18, 22));
        setContentView(view);
    }
}
