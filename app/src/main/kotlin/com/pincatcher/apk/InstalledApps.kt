package com.pincatcher.apk

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** One launchable app we could extract an APK from. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isSplit: Boolean,
    val icon: Drawable?,
)

/**
 * Reads the installed-app list (FR-01).
 *
 * `sourceDir` is readable by any app, so extraction needs no root - the part
 * that needs root would only be `/data/data`.
 *
 * `isSplit` is decided by `splitSourceDirs` being non-null, which matters
 * because a split APK cannot be patched as-is; it has to be merged first.
 */
object InstalledApps {

    fun list(context: Context, includeSystemApps: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val packages: List<PackageInfo> = try {
            if (includeSystemApps) {
                pm.getInstalledPackages(flags)
            } else {
                pm.getInstalledPackages(flags).filter { info ->
                    info.applicationInfo?.let { !isSystemApp(it) } ?: true
                }
            }
        } catch (e: Exception) {
            // QUERY_ALL_PACKAGES can be refused by the user on some builds.
            emptyList()
        }

        return packages.mapNotNull { info ->
            // applicationInfo is nullable from API 33 when the package is
            // visible but not queryable, which is not worth surfacing.
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            val label = try {
                appInfo.loadLabel(pm).toString()
            } catch (e: Exception) {
                info.packageName
            }
            InstalledApp(
                packageName = info.packageName,
                label = label,
                versionName = info.versionName.orEmpty(),
                versionCode = versionCodeOf(info),
                isSystem = isSystemApp(appInfo),
                isSplit = appInfo.splitSourceDirs != null,
                icon = try { appInfo.loadIcon(pm) } catch (e: Exception) { null },
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Launches an app so the user can open it. */
    fun launch(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        context.startActivity(intent)
    }

    private fun isSystemApp(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
}