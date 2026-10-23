package com.clonehub.advanced.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.clonehub.advanced.data.model.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads host apps safely and asynchronously.
 */
class AppRepository(private val context: Context) {

    suspend fun getInstalledApps(includeSystemApps: Boolean): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val list = mutableListOf<InstalledAppInfo>()

        for (info in resolveInfos) {
            val pkgName = info.activityInfo.packageName
            if (pkgName == context.packageName) continue // Don't list ourselves

            val appInfo = info.activityInfo.applicationInfo
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            if (!includeSystemApps && isSystem) {
                continue
            }

            val label = info.loadLabel(pm).toString()
            val version = try {
                pm.getPackageInfo(pkgName, 0).versionName ?: "1.0"
            } catch (e: Exception) {
                "1.0"
            }
            val icon = info.loadIcon(pm)

            list.add(
                InstalledAppInfo(
                    packageName = pkgName,
                    appName = label,
                    versionName = version,
                    isSystemApp = isSystem,
                    icon = icon
                )
            )
        }

        list.sortBy { it.appName.lowercase() }
        list
    }
}
