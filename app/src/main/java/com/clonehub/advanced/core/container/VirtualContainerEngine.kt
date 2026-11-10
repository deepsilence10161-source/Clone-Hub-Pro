package com.clonehub.advanced.core.container

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.clonehub.advanced.data.model.ClonedApp

/**
 * Binder bridge to the bundled virtual-container runtime.
 * Every remote result is checked before reporting success to the UI.
 */
object VirtualContainerEngine {

    private const val TAG = "VirtualContainerEngine"
    private const val PROVIDER_METHOD_SERVICE = "995"
    private const val TRANSACTION_GET_PACKAGE_INFO = 5
    private const val TRANSACTION_INSTALL_PACKAGE = 6
    private const val TRANSACTION_LAUNCH_PACKAGE = 12
    private const val TRANSACTION_FORCE_STOP = 15
    private const val INSTALL_EXISTING_PACKAGE = 2
    private const val INSTALL_OPTION_PRIMARY_PATH = "fohwt.BBYZW_IJB"
    private const val INSTALL_OPTION_SECONDARY_PATH = "fohwt.IAJSVXW_IJB"
    private const val INSTALL_OPTION_FLAGS = "fohwt.GSHG"
    private const val INSTALL_RESULT_CODE = "fohwt.XTAEA_QMWG"
    private const val ANDROID_START_SUCCESS = 0

    private var remoteBinder: IBinder? = null

    /**
     * Get or connect to the Virtual Core background service binder.
     */
    @Synchronized
    private fun getRemoteBinder(context: Context): IBinder? {
        if (remoteBinder != null && remoteBinder?.isBinderAlive == true) {
            return remoteBinder
        }

        remoteBinder = null
        try {
            val authority = "${context.packageName}.com.multibox.clone.hub.Jrhl\$Service"
            val cr: ContentResolver = context.contentResolver
            val client = cr.acquireUnstableContentProviderClient(authority)
            if (client != null) {
                // Runtime method 995 returns the package/activity service. Its Bundle key
                // is obfuscated and must not be hard-coded, so locate the Binder by value.
                val bundle = try {
                    client.call(PROVIDER_METHOD_SERVICE, null, Bundle.EMPTY)
                } finally {
                    client.release()
                }
                if (bundle != null) {
                    remoteBinder = bundle.keySet().asSequence()
                        .mapNotNull { key -> bundle.getBinder(key) }
                        .firstOrNull()
                }
                if (remoteBinder == null) {
                    Log.e(TAG, "Virtual core returned no usable Binder")
                }
            } else {
                Log.e(TAG, "Failed to acquire content provider client for $authority")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring virtual core binder", e)
        }
        return remoteBinder
    }

    private fun getInterfaceDescriptor(binder: IBinder): String? {
        return try {
            binder.interfaceDescriptor
        } catch (e: Exception) {
            Log.e(TAG, "Could not read runtime interface descriptor", e)
            null
        }
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

        val descriptor = getInterfaceDescriptor(binder)
        if (descriptor.isNullOrBlank()) {
            Log.e(TAG, "Runtime did not expose its interface descriptor")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(packageName)
            data.writeInt(INSTALL_EXISTING_PACKAGE)
            data.writeInt(spaceId) // userId / spaceId
            data.writeInt(1) // has bundle
            val installOptions = Bundle().apply {
                putString(INSTALL_OPTION_PRIMARY_PATH, null)
                putString(INSTALL_OPTION_SECONDARY_PATH, null)
                putInt(INSTALL_OPTION_FLAGS, -1)
            }
            installOptions.writeToParcel(data, 0)
            if (!binder.transact(TRANSACTION_INSTALL_PACKAGE, data, reply, 0)) {
                Log.e(TAG, "Runtime rejected install transaction for $packageName")
                return false
            }
            reply.readException()
            // AIDL writes a presence int before a non-null Parcelable. readBundle()
            // expects the Bundle length directly, so consume the presence int first.
            val result = if (reply.readInt() != 0) {
                Bundle.CREATOR.createFromParcel(reply)
            } else {
                null
            }

            // A returned Bundle alone is not proof of success. Match the original client:
            // query the runtime package manager and require a real PackageInfo afterwards.
            val installed = queryInstalledPackage(binder, descriptor, packageName, spaceId) != null
            Log.i(
                TAG,
                "Package $packageName install verification in Space $spaceId: " +
                    "installed=$installed, resultCode=${result?.getInt(INSTALL_RESULT_CODE, -1)}, result=$result"
            )
            installed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install package $packageName into Space $spaceId", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun queryInstalledPackage(
        binder: IBinder,
        descriptor: String,
        packageName: String,
        spaceId: Int
    ): PackageInfo? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(packageName)
            data.writeInt(0)
            data.writeInt(spaceId)
            if (!binder.transact(TRANSACTION_GET_PACKAGE_INFO, data, reply, 0)) {
                return null
            }
            reply.readException()
            if (reply.readInt() != 0) {
                PackageInfo.CREATOR.createFromParcel(reply)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not verify installed package $packageName in Space $spaceId", e)
            null
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

        // 1. Ensure package is installed into virtual space. Never launch after a failed install.
        if (!installApp(context, app.packageName, spaceId)) {
            Log.e(TAG, "Cannot launch ${app.packageName} because installation failed")
            return false
        }

        // 2. Launch inside virtual agent process
        val binder = getRemoteBinder(context)
        if (binder == null) {
            Log.e(TAG, "Cannot launch ${app.packageName} - RemoteBinder is null")
            return false
        }

        val descriptor = getInterfaceDescriptor(binder)
        if (descriptor.isNullOrBlank()) {
            Log.e(TAG, "Runtime did not expose its interface descriptor")
            return false
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(app.packageName)
            data.writeInt(0) // flags
            data.writeInt(spaceId) // userId / spaceId
            if (!binder.transact(TRANSACTION_LAUNCH_PACKAGE, data, reply, 0)) {
                Log.e(TAG, "Runtime rejected launch transaction for ${app.packageName}")
                return false
            }
            reply.readException()
            val resultCode = reply.readInt()
            val succeeded = isLaunchResultSuccessful(resultCode)
            Log.i(TAG, "Virtual launch for ${app.packageName} returned code: $resultCode")
            if (succeeded) {
                app.launchCount++
            }
            succeeded
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
        val descriptor = getInterfaceDescriptor(binder) ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.writeString(packageName)
            data.writeInt(spaceId)
            data.writeString("force-stop")
            if (binder.transact(TRANSACTION_FORCE_STOP, data, reply, 0)) {
                reply.readException()
                Log.i(TAG, "Force stopped $packageName in Space $spaceId")
            } else {
                Log.e(TAG, "Runtime rejected force-stop transaction for $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error force stopping virtual app $packageName", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    internal fun isLaunchResultSuccessful(resultCode: Int): Boolean {
        return resultCode == ANDROID_START_SUCCESS
    }
}
