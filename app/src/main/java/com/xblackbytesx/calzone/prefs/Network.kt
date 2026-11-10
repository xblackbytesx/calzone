package com.xblackbytesx.calzone.prefs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings

/**
 * Whether CalZone can reach any network. On GrapheneOS, with the app's
 * Network permission off, the app sees no network at all and Android holds
 * every sync without an error (decision 45 in calzone-server), so the
 * settings screen says so plainly instead of looking fine.
 */
object Network {
    fun reachable(context: Context): Boolean {
        if (context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** The app's own page in Android's settings, where Network is switched on. */
    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
