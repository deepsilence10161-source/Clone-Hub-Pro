package com.clonehub.advanced.core.accounts

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Content-provider surface for [AccountBridge].
 *
 * The virtual runtime's activity/session code cannot call into a Kotlin object in another process, so
 * the bridge is exposed the one way a container can reach it: a provider `call()` on a signature
 * authority. Keys are deliberately tiny strings; nothing here can install, launch or delete anything.
 */
class HostAccountProvider : ContentProvider() {

    private companion object {
        const val TAG = "HostAccountProvider"
        const val AUTHORITY = "com.clonehub.pro.accounts"
        const val METHOD_ACCOUNTS = "accounts"
        const val METHOD_TOKEN = "token"
        const val KEY_TYPE = "type"
        const val KEY_TOKEN_TYPE = "token_type"
        const val KEY_NAMES = "names"
        const val KEY_ERROR = "error"
        const val KEY_RESULT = "result"
        const val KEY_LENGTH = "length"
        const val KEY_DETAIL = "detail"
    }

    override fun onCreate(): Boolean {
        context?.let { CloneHubContextHolder.install(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = callingPackage ?: "unknown"
        val type = extras?.getString(KEY_TYPE) ?: AccountBridge.GOOGLE_ACCOUNT_TYPE
        Log.i(TAG, "call method=$method caller=$caller type=$type")
        return when (method) {
            METHOD_ACCOUNTS -> {
                val (names, error) = AccountBridge.accountNames(type)
                Bundle().apply {
                    putStringArrayList(KEY_NAMES, ArrayList(names))
                    if (error != null) putString(KEY_ERROR, error)
                }
            }
            METHOD_TOKEN -> {
                val outcome = AccountBridge.authToken(
                    type,
                    extras?.getString(KEY_TOKEN_TYPE) ?: AccountBridge.TOKEN_TYPE
                )
                Bundle().apply {
                    putString(KEY_RESULT, outcome.result)
                    putInt(KEY_LENGTH, outcome.length)
                    outcome.detail?.let { putString(KEY_DETAIL, it) }
                }
            }
            else -> Bundle().apply { putString(KEY_ERROR, "UNKNOWN_METHOD:$method") }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
