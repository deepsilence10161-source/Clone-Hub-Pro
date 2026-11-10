package com.clonehub.advanced.fixture

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Visible third-party fixture launched by the real-user clone-flow E2E test. */
class CloneFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "CLONE_FIXTURE_RUNNING"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val details = TextView(this).apply {
            text = "package=$packageName\nprocess=${android.os.Process.myPid()}"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 80, 55))
            addView(status)
            addView(details)
        })
    }
}
