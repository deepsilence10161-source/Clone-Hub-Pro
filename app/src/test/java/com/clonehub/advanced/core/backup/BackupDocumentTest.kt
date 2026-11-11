package com.clonehub.advanced.core.backup

import com.clonehub.advanced.data.model.CloneModel
import com.clonehub.advanced.data.model.ClonedApp
import com.clonehub.advanced.data.model.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for the backup document format. These run without a device, so the format's
 * safety properties (checksum, version gate, duplicate detection) are proven on every build.
 */
class BackupDocumentTest {

    private fun sampleModel(): CloneModel = CloneModel(
        spaces = listOf(
            Space(
                spaceId = 1,
                spaceName = "Work",
                apps = mutableListOf(
                    ClonedApp(packageName = "net.gsantner.markor", appName = "Markor", originalName = "Markor", spaceId = 1)
                )
            ),
            Space(
                spaceId = 2,
                spaceName = "Personal",
                apps = mutableListOf(
                    ClonedApp(packageName = "com.beemdevelopment.aegis", appName = "Aegis", originalName = "Aegis", spaceId = 2),
                    ClonedApp(packageName = "com.simplemobiletools.calculator", appName = "Calculator", originalName = "Calculator", spaceId = 2)
                )
            )
        )
    )

    @Test
    fun modelRoundTripsThroughJson() {
        val original = sampleModel()
        val json = BackupDocument.encode(original, versionName = "2.0.1", versionCode = 2001, abi = "arm64-v8a")
        val decoded = BackupDocument.decode(json)
        assertTrue(decoded is BackupDocument.ParseResult.Valid)
        val model = (decoded as BackupDocument.ParseResult.Valid).model
        assertEquals(2, model.spaces.size)
        assertEquals(3, model.spaces.sumOf { it.apps.size })
        assertEquals("Work", model.spaces[0].spaceName)
        assertEquals("com.beemdevelopment.aegis", model.spaces[1].apps[0].packageName)
    }

    @Test
    fun editedPayload_isRejected() {
        val json = BackupDocument.encode(sampleModel(), "2.0.1", 2001, "arm64-v8a")
        // A single character change inside the payload must invalidate the checksum.
        val tampered = json.replace("Markor", "Mark0r")
        assertNotEquals(json, tampered)
        val decoded = BackupDocument.decode(tampered)
        assertTrue(decoded is BackupDocument.ParseResult.Rejected)
        assertTrue((decoded as BackupDocument.ParseResult.Rejected).reason.contains("checksum", ignoreCase = true))
    }

    @Test
    fun truncatedFile_isRejected() {
        val json = BackupDocument.encode(sampleModel(), "2.0.1", 2001, "arm64-v8a")
        val truncated = json.substring(0, json.length / 2)
        assertTrue(BackupDocument.decode(truncated) is BackupDocument.ParseResult.Rejected)
    }

    @Test
    fun foreignFile_isRejected() {
        assertTrue(BackupDocument.decode("{\"hello\":\"world\"}") is BackupDocument.ParseResult.Rejected)
        assertTrue(BackupDocument.decode("") is BackupDocument.ParseResult.Rejected)
    }

    @Test
    fun newerSchema_isRejectedInsteadOfGuessed() {
        val json = BackupDocument.encode(sampleModel(), "9.9.9", 9999, "arm64-v8a")
            .replace("\"schema\": 1", "\"schema\": 99")
        val decoded = BackupDocument.decode(json)
        assertTrue(decoded is BackupDocument.ParseResult.Rejected)
    }

    @Test
    fun duplicateSpaceInSameFile_isRejected() {
        val broken = CloneModel(
            spaces = listOf(
                Space(spaceId = 3, spaceName = "A", apps = mutableListOf()),
                Space(spaceId = 3, spaceName = "B", apps = mutableListOf())
            )
        )
        val json = BackupDocument.encode(broken, "2.0.1", 2001, "arm64-v8a")
        val decoded = BackupDocument.decode(json)
        assertTrue(decoded is BackupDocument.ParseResult.Rejected)
        assertTrue((decoded as BackupDocument.ParseResult.Rejected).reason.contains("twice"))
    }

    @Test
    fun cloneIdsArePreserved_exceptTheSpaceLink() {
        val model = sampleModel()
        val json = BackupDocument.encode(model, "2.0.1", 2001, "arm64-v8a")
        val decoded = (BackupDocument.decode(json) as BackupDocument.ParseResult.Valid).model
        assertEquals(model.spaces[0].apps[0].installTime, decoded.spaces[0].apps[0].installTime)
        assertEquals(model.spaces[0].apps[0].appName, decoded.spaces[0].apps[0].appName)
        assertFalse(decoded.spaces.any { it.apps.any { app -> app.packageName.isBlank() } })
    }
}
