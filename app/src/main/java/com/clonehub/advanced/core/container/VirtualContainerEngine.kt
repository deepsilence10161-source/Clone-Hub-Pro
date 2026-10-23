package com.clonehub.advanced.core.container

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.clonehub.advanced.data.model.ClonedApp

/**
 * 100x Modern Ultra-Fast Virtual Container Engine.
 * Fully isolated sandboxing: independent data, account, databases, and process.
 */
object VirtualContainerEngine {

    private const val TAG = "VirtualContainerEngine"
    private const val DESCRIPTOR = "com.multibox.clone.hub.rkacpu.joz.AGumwPdn"
    private const val ACTION_KEY = "android.intent.extra.INTENT"

    private var remoteBinder: IBinder? = null

    /**
     * Get or connect to the Virtual Core background service binder.
     */
    @Synchronized
    private fun getRemoteBinder(context: Context): IBinder? {
        if (remoteBinder != null && remoteBinder?.isBinderAlive == true) {
            return remoteBinder
        }

        try {
            val authority = "${context.packageName}.com.multibox.clone.hub.Jrhl\$Service"
            val cr: ContentResolver = context.contentResolver
            val client = cr.acquireUnstableContentProviderClient(authority)
            if (client != null) {
                val bundle = client.call(ACTION_KEY, null, Bundle.EMPTY)
                client.release()
                if (bundle != null) {
                    remoteBinder = bundle.getBinder(ACTION_KEY)
                    Log.i(TAG, "Virtual Core Binder acquired: $remoteBinder")
                }
            } else {
                Log.e(TAG, "Failed to acquire content provider client for $authority")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring virtual core binder", e)
        }
        return remoteBinder
    }

    /**
     * Installs the app package into the specified virtual space sandbox.
     * Transaction 6 (INSTALL_PACKAGE).
     */
    fun installApp(context: Context, packageName: String, spaceId: Int): Boolean {
        val binder = getRemoteBinder(context)
        if (binder == null) {
            Log.e(TAG, "Cannot install $packageName - RemoteBinder is null")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(packageName)
            data.writeInt(0) // flags
            data.writeInt(spaceId) // userId / spaceId
            data.writeInt(1) // has bundle
            Bundle.EMPTY.writeToParcel(data, 0)
            binder.transact(6, data, reply, 0)
            reply.readException()
            val result = reply.readBundle(Bundle::class.java.classLoader)
            Log.i(TAG, "Package $packageName installed into Space $spaceId, result: $result")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install package $packageName into Space $spaceId", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Launches the cloned app inside the virtual process for the specified space.
     * Transaction 12 (START_ACTIVITY in agent process).
     */
    fun launchApp(context: Context, app: ClonedApp, spaceId: Int): Boolean {
        Log.i(TAG, "Initiating virtual container launch for ${app.packageName} in Space $spaceId")

        // 1. Ensure package is installed into virtual space
        installApp(context, app.packageName, spaceId)

        // 2. Launch inside virtual agent process
        val binder = getRemoteBinder(context)
        if (binder == null) {
            Log.e(TAG, "Cannot launch ${app.packageName} - RemoteBinder is null")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(app.packageName)
            data.writeInt(0) // flags
            data.writeInt(spaceId) // userId / spaceId
            binder.transact(12, data, reply, 0)
            reply.readException()
            val resultCode = reply.readInt()
            Log.i(TAG, "Virtual launch for ${app.packageName} returned code: $resultCode")
            app.launchCount++
            resultCode >= 0 || resultCode == -96 || resultCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch virtual app ${app.packageName} in Space $spaceId", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Force stops the virtual app process.
     * Transaction 15 (FORCE_STOP).
     */
    fun forceStopApp(context: Context, packageName: String, spaceId: Int) {
        val binder = getRemoteBinder(context) ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(packageName)
            data.writeInt(spaceId)
            data.writeString("force-stop")
            binder.transact(15, data, reply, 0)
            reply.readException()
            Log.i(TAG, "Force stopped $packageName in Space $spaceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error force stopping virtual app $packageName", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
