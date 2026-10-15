package fr.cinema.retourson

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object Net {
    /** Adresses de broadcast de toutes les interfaces actives (Wi-Fi client ou point d'accès). */
    fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (ia.address is Inet4Address) out += b
                }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) out += InetAddress.getByName("255.255.255.255")
        return out
    }

    fun localIpv4(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    if (ia.address is Inet4Address && ia.broadcast != null) out += ia.address.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/**
 * Empêche le Wi-Fi de passer en économie d'énergie (sinon pics de latence de 100 ms et plus)
 * et le processeur de s'endormir écran éteint.
 */
class NetLocks(ctx: Context, tag: String, multicast: Boolean) {
    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val lowLatency = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "$tag:lowlat")

    @Suppress("DEPRECATION")
    private val highPerf = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$tag:highperf")
    private val multicastLock = if (multicast) wifi.createMulticastLock("$tag:mcast") else null
    private val wake = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag:wake")

    init {
        lowLatency.setReferenceCounted(false)
        highPerf.setReferenceCounted(false)
        multicastLock?.setReferenceCounted(false)
        wake.setReferenceCounted(false)
    }

    fun acquire() {
        wake.acquire()
        lowLatency.acquire()
        highPerf.acquire()
        multicastLock?.acquire()
    }

    fun release() {
        multicastLock?.release()
        highPerf.release()
        lowLatency.release()
        wake.release()
    }
}
