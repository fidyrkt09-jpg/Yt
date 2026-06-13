package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

object NetworkHelper {

    data class NetworkStatus(
        val isWifiActive: Boolean,
        val isMobileActive: Boolean,
        val isConnected: Boolean,
        val typeLabel: String,
        val speedLabel: String,
        val signalStrengthRating: String // "Excellente", "Bonne", "Modérée", "Faible"
    )

    fun getNetworkStatus(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus(false, false, false, "Inconnu", "Inconnue", "Basse")

        var isWifi = false
        var isMobile = false
        var isConnected = false
        var speedText = "Inconnue"
        var signalRating = "Modérée"
        var typeStr = "Déconnecté"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            if (capabilities != null) {
                isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                
                isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                isMobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

                val downSpeedKbps = capabilities.linkDownstreamBandwidthKbps
                speedText = when {
                    downSpeedKbps >= 150000 -> String.format("%.1f Mbps (Trés haut débit)", downSpeedKbps.toDouble() / 1000)
                    downSpeedKbps >= 20000 -> String.format("%.1f Mbps (Haut débit)", downSpeedKbps.toDouble() / 1000)
                    downSpeedKbps >= 3000 -> String.format("%.1f Mbps (Débit Moyen)", downSpeedKbps.toDouble() / 1000)
                    downSpeedKbps > 0 -> String.format("%.1f Mbps (Bas débit)", downSpeedKbps.toDouble() / 1000)
                    else -> "Inconnue"
                }

                signalRating = when {
                    downSpeedKbps >= 100000 -> "Excellente"
                    downSpeedKbps >= 30000 -> "Bonne"
                    downSpeedKbps >= 5000 -> "Modérée"
                    else -> "Faible"
                }

                typeStr = when {
                    isWifi -> "Wi-Fi"
                    isMobile -> "Réseau Mobile (Cellulaire)"
                    else -> "Autre"
                }
            }
        } else {
            // Legacy networks fallback
            @Suppress("DEPRECATION")
            val activeNetworkInfo = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (activeNetworkInfo != null && activeNetworkInfo.isConnected) {
                isConnected = true
                @Suppress("DEPRECATION")
                isWifi = activeNetworkInfo.type == ConnectivityManager.TYPE_WIFI
                @Suppress("DEPRECATION")
                isMobile = activeNetworkInfo.type == ConnectivityManager.TYPE_MOBILE
                typeStr = activeNetworkInfo.typeName
                speedText = "Inconnue (API Legacy)"
                signalRating = "Bonne"
            }
        }

        return NetworkStatus(
            isWifiActive = isWifi,
            isMobileActive = isMobile,
            isConnected = isConnected,
            typeLabel = typeStr,
            speedLabel = speedText,
            signalStrengthRating = signalRating
        )
    }
}
