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
    // Pro-owned transport. PF maps this internally to the runtime's server mode
    // without registering the original app's conflicting Android authority.
    private const val SERVICE_PROVIDER_AUTHORITY =
        "com.clonehub.pro.virtual.container.server0001.Jrhl\$Service"
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
            val authority = SERVICE_PROVIDER_AUTHORITY
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
     *
     * Success is reported only after the runtime's own package manager confirms the package.
     */
    fun installAppOutcome(context: Context, packageName: String, spaceId: Int): EngineOutcome {
        val binder = getRemoteBinder(context)
            ?: return EngineOutcome.RuntimeUnavailable("no binder from $SERVICE_PROVIDER_AUTHORITY")
        val descriptor = getInterfaceDescriptor(binder)
            ?: return EngineOutcome.RuntimeUnavailable("runtime exposed no interface descriptor")

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
                return EngineOutcome.Rejected("install", -1, "transaction refused")
            }
            reply.readException()
            // AIDL writes a presence int before a non-null Parcelable. readBundle() expects the
            // Bundle length directly, so consume the presence int first.
            val result = if (reply.readInt() != 0) {
                Bundle.CREATOR.createFromParcel(reply)
            } else {
                null
            }
            val resultCode = result?.getInt(INSTALL_RESULT_CODE, -1) ?: -1

            // A returned Bundle alone is not proof of success. Match the original client:
            // query the runtime package manager and require a real PackageInfo afterwards.
            if (queryInstalledPackage(binder, descriptor, packageName, spaceId) != null) {
                EngineOutcome.Success
            } else {
                EngineOutcome.NotVerified(
                    "install",
                    "resultCode=$resultCode but the runtime package manager does not list $packageName"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install package $packageName into Space $spaceId", e)
            EngineOutcome.Rejected("install", -1, e.message ?: e.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Backwards-compatible boolean view used by older call sites and tests. */
    fun installApp(context: Context, packageName: String, spaceId: Int): Boolean =
        installAppOutcome(context, packageName, spaceId).isSuccess

    private fun queryRuntimeSpaces(binder: IBinder, descriptor: String): IntArray? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(null)
            if (!binder.transact(1, data, reply, 0)) return null
            reply.readException()
            reply.createIntArray()
        } catch (e: Exception) {
            Log.e(TAG, "Could not list runtime spaces", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun queryInstalledPackageNames(
        binder: IBinder,
        descriptor: String,
        spaceId: Int
    ): List<String>? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(8192)
            data.writeInt(spaceId)
            if (!binder.transact(3, data, reply, 0)) return null
            reply.readException()
            reply.createStringArrayList()
        } catch (e: Exception) {
            Log.e(TAG, "Could not list packages in Space $spaceId", e)
            null
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
    fun launchAppOutcome(context: Context, app: ClonedApp, spaceId: Int): EngineOutcome {
        Log.i(TAG, "Initiating virtual container launch for ${app.packageName} in Space $spaceId")

        // 1. Ensure the package is installed into the virtual space; never launch after a failed install.
        val installed = installAppOutcome(context, app.packageName, spaceId)
        Log.i(TAG, "Install step for ${app.packageName} in Space $spaceId: ${installed.describe()}")
        if (!installed.isSuccess) {
            return when (installed) {
                is EngineOutcome.NotVerified -> EngineOutcome.NotVerified("launch/install", installed.detail)
                else -> installed
            }
        }

        // 2. Launch inside the virtual agent process.
        val binder = getRemoteBinder(context)
            ?: return EngineOutcome.RuntimeUnavailable("binder disappeared before launch")
        val descriptor = getInterfaceDescriptor(binder)
            ?: return EngineOutcome.RuntimeUnavailable("runtime exposed no interface descriptor")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(app.packageName)
            data.writeInt(0) // flags
            data.writeInt(spaceId) // userId / spaceId
            if (!binder.transact(TRANSACTION_LAUNCH_PACKAGE, data, reply, 0)) {
                return EngineOutcome.Rejected("launch", -1, "transaction refused")
            }
            reply.readException()
            val resultCode = reply.readInt()
            // A launch result is only evidence about *this* space, so record it verbatim: the
            // 16-test compatibility suite and the native E2E gates read this line to distinguish
            // "runtime refused" from "app started but drew nothing".
            Log.i(TAG, "Launch result for ${app.packageName} in Space $spaceId: code=$resultCode")
            if (isLaunchResultSuccessful(resultCode)) {
                EngineOutcome.Success
            } else {
                EngineOutcome.Rejected("launch", resultCode, launchFailureHint(resultCode))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch virtual app ${app.packageName} in Space $spaceId", e)
            EngineOutcome.Rejected("launch", -1, e.message ?: e.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Backwards-compatible boolean view. */
    fun launchApp(context: Context, app: ClonedApp, spaceId: Int): Boolean =
        launchAppOutcome(context, app, spaceId).isSuccess

    /**
     * Known non-zero launch results seen from this runtime. Unknown codes stay unknown on purpose;
     * inventing an explanation would be worse than saying nothing.
     */
    private fun launchFailureHint(resultCode: Int): String? = when (resultCode) {
        -96 -> "runtime could not start an agent process slot"
        102 -> "target activity was not found inside the clone"
        else -> null
    }

    /**
     * Force stops the virtual app process.
     * Transaction 15 (FORCE_STOP).
     */
    fun forceStopApp(context: Context, packageName: String, spaceId: Int): EngineOutcome {
        val binder = getRemoteBinder(context)
            ?: return EngineOutcome.RuntimeUnavailable("binder unavailable")
        val descriptor = getInterfaceDescriptor(binder)
            ?: return EngineOutcome.RuntimeUnavailable("runtime exposed no interface descriptor")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(packageName)
            data.writeInt(spaceId)
            data.writeString("force-stop")
            if (binder.transact(TRANSACTION_FORCE_STOP, data, reply, 0)) {
                reply.readException()
                Log.i(TAG, "Force stopped $packageName in Space $spaceId")
                EngineOutcome.Success
            } else {
                Log.e(TAG, "Runtime rejected force-stop transaction for $packageName")
                EngineOutcome.Rejected("force-stop", -1, "transaction refused")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error force stopping virtual app $packageName", e)
            EngineOutcome.Rejected("force-stop", -1, e.message ?: e.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    internal fun isLaunchResultSuccessful(resultCode: Int): Boolean {
        return resultCode == ANDROID_START_SUCCESS
    }
}
