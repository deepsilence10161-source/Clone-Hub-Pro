package com.multibox.clone.hub;

import android.app.Activity;
import android.content.Intent;

public abstract class OF extends Activity {
    public final int g;

    public OF(int i) {
        this.g = i;
    }

    @Override
    public void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        if (this.g == 1) {
            try {
                if (AbstractC2211qP.k != null) {
                    AbstractC2211qP.k.invoke(null, 10202, new Object[]{this, Integer.valueOf(i), Integer.valueOf(i2), intent});
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onBackPressed() {
        if (this.g == 0) {
            moveTaskToBack(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (this.g == 1) {
            try {
                if (AbstractC2211qP.k != null) {
                    AbstractC2211qP.k.invoke(null, 10201, new Object[]{this, intent});
                }
            } catch (Throwable ignored) {}
        }
    }
}
