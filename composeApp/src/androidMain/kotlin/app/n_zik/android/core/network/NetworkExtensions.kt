package app.n_zik.android.core.network

import android.content.Context

inline val Context.isNetworkConnected: Boolean
    get() = NetworkQualityHelper.isNetworkConnected(this)

inline val Context.isNetworkAvailable: Boolean
    get() = NetworkQualityHelper.isNetworkAvailable(this)

inline val Context.isMetered: Boolean
    get() = NetworkQualityHelper.isMetered(this)

inline val Context.networkType: String
    get() = NetworkQualityHelper.getCurrentNetworkType(this)

inline val Context.networkQuality: NetworkQuality
    get() = NetworkQualityHelper.networkQuality(this)

/**
 * Note: we use NetworkQualityHelper.getCurrentNetworkQuality(this) in the helper, 
 * but here we can expose it via extension for convenience.
 */
fun NetworkQualityHelper.networkQuality(context: Context): NetworkQuality = 
    this.getCurrentNetworkQuality(context)
