package app.it.fast4x.rimusic.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.it.fast4x.rimusic.enums.NetworkQuality
import app.it.fast4x.rimusic.utils.isConnectionMeteredEnabledKey
import app.it.fast4x.rimusic.utils.preferences
import timber.log.Timber

object NetworkQualityHelper {

    fun isMetered(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            
            var metered = false
            
            // 1. Check if Roaming
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                metered = true
            }

            // 2. Check System Data Saver (API 24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                    metered = true
                }
            }

            // 3. Check user override preference in the app (Manual button)
            val forceMetered = context.preferences.getBoolean(isConnectionMeteredEnabledKey, false)
            if (forceMetered) {
                metered = true
            }
            metered
        } catch (e: Exception) {
            false
        }
    }

    fun isNetworkConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getCurrentNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return "-"
            val actNw = cm.getNetworkCapabilities(nw) ?: return "-"
            when {
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "?"
            }
        } catch (e: Exception) {
            "?"
        }
    }

    fun getCurrentNetworkQuality(context: Context): NetworkQuality {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkQuality.LOW
            
            // Use our centralized metered check
            val isMetered = isMetered(context)
            val bandwidth = capabilities.linkDownstreamBandwidthKbps
            
            // Update Global Logger State
            GlobalNetworkLogger.lastBandwidth = bandwidth
            GlobalNetworkLogger.lastIsMetered = isMetered

            var quality = when {
                bandwidth > 20000 -> NetworkQuality.HIGH
                bandwidth > 5000 -> NetworkQuality.MEDIUM
                else -> NetworkQuality.LOW
            }

            // If ANY of the metered conditions are met, cap quality to MEDIUM to save data
            if (isMetered && quality == NetworkQuality.HIGH) {
                quality = NetworkQuality.MEDIUM
            }

            // Centralized Log
            GlobalNetworkLogger.logNetworkState("NetworkHelper", bandwidth, isMetered, "RAW_DETECT", quality.name)

            quality
        } catch (e: Exception) {
            Timber.e(e, "NetworkQualityHelper: Failed to detect quality")
            NetworkQuality.LOW
        }
    }
}



