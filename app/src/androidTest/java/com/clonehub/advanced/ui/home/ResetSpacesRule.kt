package com.clonehub.advanced.ui.home

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.clonehub.advanced.core.container.SpaceManager
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/** Gives independent UI tests the same clean host model as a fresh installation. */
class ResetSpacesRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                reset()
                try {
                    base.evaluate()
                } finally {
                    reset()
                }
            }
        }

    private fun reset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.getSharedPreferences("clone_spaces_db", Context.MODE_PRIVATE)
            .edit().clear().commit()) { "Could not reset test space preferences" }
        File(context.filesDir, "spaces").deleteRecursively()
        SpaceManager::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }
}
