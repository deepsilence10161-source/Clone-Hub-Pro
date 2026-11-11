package com.clonehub.advanced.data

import com.clonehub.advanced.data.model.ClonedApp
import com.clonehub.advanced.data.model.Space
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Existing installs persist clones as JSON. Any model change must keep those payloads loadable,
 * otherwise an update silently destroys every clone the user created.
 */
class CloneModelCompatibilityTest {

    private val gson = Gson()
    private val listType = object : TypeToken<List<Space>>() {}.type

    /** Payload written by version 2.0.1, before launch-health fields existed. */
    private val legacyJson = """
        [{"spaceId":1,"spaceName":"Space 1","apps":[
          {"packageName":"net.gsantner.markor","appName":"Markor","originalName":"Markor",
           "spaceId":1,"installTime":1700000000000,"launchCount":3}
        ]}]
    """.trimIndent()

    @Test
    fun legacyPayload_loadsWithDefaults() {
        val spaces: List<Space> = gson.fromJson(legacyJson, listType)
        assertEquals(1, spaces.size)
        val app = spaces[0].apps.single()
        assertEquals("net.gsantner.markor", app.packageName)
        assertEquals(3, app.launchCount)
        assertNull("Legacy clones must not look like they failed", app.lastLaunchSucceeded)
        assertEquals(0L, app.lastLaunchAt)
        assertNull(app.customIconPath)
    }

    @Test
    fun newPayload_roundTripsLaunchHealth() {
        val app = ClonedApp(
            packageName = "com.beemdevelopment.aegis",
            appName = "Aegis",
            originalName = "Aegis",
            spaceId = 2
        ).apply {
            lastLaunchSucceeded = false
            lastFailureReason = "Engine rejected launch (code -96)"
            lastLaunchAt = 1_700_000_000_123
        }
        val json = gson.toJson(listOf(Space(spaceId = 2, apps = mutableListOf(app))))
        val restored: List<Space> = gson.fromJson(json, listType)
        val restoredApp = restored.single().apps.single()
        assertNotNull(restoredApp.lastLaunchSucceeded)
        assertEquals(false, restoredApp.lastLaunchSucceeded)
        assertEquals("Engine rejected launch (code -96)", restoredApp.lastFailureReason)
        assertEquals(1_700_000_000_123, restoredApp.lastLaunchAt)
    }
}
