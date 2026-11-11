package com.clonehub.advanced.core.backup

import com.clonehub.advanced.data.model.CloneModel
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/**
 * The on-disk backup format, kept free of Android APIs so it is fully unit-testable.
 *
 * Envelope: `{ driver, schema, appVersionName, appVersionCode, createdAt, abi, modelVersion,
 * cloneCount, payloadSha256, payload }` where `payload` is the [CloneModel] as JSON and
 * `payloadSha256` is the SHA-256 of *that exact JSON text*. Any edit, truncation or foreign file
 * therefore fails to decode instead of silently restoring the wrong clones.
 */
object BackupDocument {

    const val DRIVER = "com.clonehub.pro.clone-model"
    const val SCHEMA = 1

    sealed class ParseResult {
        data class Valid(
            val model: CloneModel,
            val appVersionName: String?,
            val createdAt: Long,
            val cloneCount: Int
        ) : ParseResult()

        data class Rejected(val reason: String) : ParseResult()
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun encode(
        model: CloneModel,
        versionName: String?,
        versionCode: Long,
        abi: String,
        now: Long = System.currentTimeMillis()
    ): String {
        val payload = Gson().toJson(model)
        val document = JsonObject().apply {
            addProperty("driver", DRIVER)
            addProperty("schema", SCHEMA)
            addProperty("appVersionName", versionName ?: "")
            addProperty("appVersionCode", versionCode)
            addProperty("createdAt", now)
            addProperty("abi", abi)
            addProperty("modelVersion", model.version)
            addProperty("cloneCount", model.spaces.sumOf { it.apps.size })
            addProperty("payloadSha256", sha256(payload))
            add("payload", JsonParser.parseString(payload))
        }
        return gson.toJson(document)
    }

    fun decode(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Rejected("The backup file is empty")
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (error: Exception) {
            return ParseResult.Rejected("The backup file is not valid JSON (${error.javaClass.simpleName})")
        }
        if (root.get("driver")?.takeIf { it.isJsonPrimitive }?.asString != DRIVER) {
            return ParseResult.Rejected("This file was not written by Clone Hub Pro")
        }
        val schema = root.get("schema")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: return ParseResult.Rejected("The backup file has no format version")
        if (schema > SCHEMA) {
            return ParseResult.Rejected("The backup was written by a newer version of Clone Hub Pro")
        }
        val payload = root.get("payload")?.takeIf { it.isJsonObject }
            ?: return ParseResult.Rejected("The backup file has no payload")
        val expected = root.get("payloadSha256")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return ParseResult.Rejected("The backup file is missing its checksum")
        val actual = sha256(payload.toString())
        if (!expected.equals(actual, ignoreCase = true)) {
            return ParseResult.Rejected(
                "The backup file is damaged or was modified (checksum mismatch)"
            )
        }
        val model = try {
            gson.fromJson(payload, CloneModel::class.java)
        } catch (error: Exception) {
            return ParseResult.Rejected("The backup payload could not be read (${error.message})")
        } ?: return ParseResult.Rejected("The backup payload is empty")

        val problem = validate(model)
        if (problem != null) return ParseResult.Rejected(problem)

        return ParseResult.Valid(
            model = model,
            appVersionName = root.get("appVersionName")?.takeIf { it.isJsonPrimitive }?.asString,
            createdAt = root.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            cloneCount = model.spaces.sumOf { it.apps.size }
        )
    }

    /**
     * Structural validation. Everything here is a reason to refuse the restore: restoring a
     * malformed model would either lose clones or create impossible state.
     */
    fun validate(model: CloneModel): String? {
        if (model.spaces.isEmpty()) return "The backup contains no spaces"
        if (model.version > CloneModel.MODEL_VERSION) {
            return "The backup uses a newer model version (${model.version}) than this app supports"
        }
        val duplicateSpace = model.spaces.groupBy { it.spaceId }.entries.firstOrNull { it.value.size > 1 }
        if (duplicateSpace != null) {
            return "The backup is corrupt: space ${duplicateSpace.key} appears twice"
        }
        model.spaces.forEach { space ->
            if (space.spaceId < 1) return "The backup is corrupt: invalid space id ${space.spaceId}"
            if (space.apps.any { it.packageName.isBlank() }) {
                return "The backup is corrupt: a clone in Space ${space.spaceId} has no package name"
            }
            if (space.apps.map { it.packageName }.toSet().size != space.apps.size) {
                return "The backup is corrupt: Space ${space.spaceId} lists the same app twice"
            }
            if (space.apps.any { it.spaceId != space.spaceId }) {
                return "The backup is corrupt: Space ${space.spaceId} holds a clone bound to another space"
            }
        }
        return null
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
