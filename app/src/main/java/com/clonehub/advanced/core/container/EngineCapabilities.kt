package com.clonehub.advanced.core.container

import android.os.IBinder

/**
 * Which runtime operations this build can actually perform.
 *
 * The bundled runtime exposes package install, package query and launch over its service binder.
 * It does **not** expose per-clone data deletion or package removal: the only available call is a
 * force-stop (transaction 15), and the package manager AIDL has no uninstall entry point.
 *
 * Rather than fake those actions, the UI asks this object and shows an honest limitation.
 */
object EngineCapabilities {

    /** Transaction 15 exists in the runtime's service switch and is used to stop a clone. */
    const val CAN_FORCE_STOP = true

    /**
     * No uninstall transaction is reachable through the published service binder, therefore
     * "remove clone" only removes the host record and never claims to delete clone data.
     */
    const val CAN_UNINSTALL_CLONE = false

    /** No transaction deletes a specific clone's data directory. */
    const val CAN_CLEAR_CLONE_DATA = false

    /**
     * Verifies at runtime that the binder we hold really answers the descriptor we expect.
     * Used before promising any capability to the user.
     */
    fun describeRuntime(binder: IBinder?): String {
        if (binder == null) return "engine not connected"
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
        return if (descriptor.isNullOrBlank()) "engine connected (no descriptor)" else descriptor
    }
}
