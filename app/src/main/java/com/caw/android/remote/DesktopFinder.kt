package com.caw.android.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds the paired computer on the Wi-Fi by mDNS / DNS-SD (`_caw._tcp`, TXT `id`), for when its
 * address changed since pairing. Blocking; call off the main thread.
 */
class DesktopFinder(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    /** Every address the lookup gave, in its order ([AddressOrder] decides which to try first). */
    data class Found(val hosts: List<String>, val port: Int, val name: String?)

    fun find(desktopId: String, timeoutMs: Long = 6_000): Found? {
        val manager = nsd ?: return null
        val done = CountDownLatch(1)
        var result: Found? = null
        val toResolve = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        val lock = Any()

        fun resolveNext() {
            val next = synchronized(lock) {
                if (resolving || result != null) return
                toResolve.removeFirstOrNull()?.also { resolving = true }
            } ?: return
            @Suppress("DEPRECATION")
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.i(TAG, "discovery: could not resolve ${info.serviceName} ($errorCode)")
                    synchronized(lock) { resolving = false }
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val id = info.attributes["id"]?.let { String(it, Charsets.UTF_8) }
                    val hosts = hostsOf(info)
                    Log.i(TAG, "discovery: ${info.serviceName} id=$id at $hosts port ${info.port}")
                    synchronized(lock) {
                        resolving = false
                        if (id.equals(desktopId, ignoreCase = true) && hosts.isNotEmpty()) {
                            result = Found(hosts, info.port, info.attributes["name"]?.let { String(it, Charsets.UTF_8) })
                            done.countDown()
                        }
                    }
                    resolveNext()
                }
            })
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.i(TAG, "discovery: could not start ($errorCode)")
                done.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) { toResolve.addLast(info) }
                resolveNext()
            }
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (t: Throwable) {
            Log.w(TAG, "discovery: could not start", t)
            return null
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        try {
            manager.stopServiceDiscovery(discovery)
        } catch (t: Throwable) {
            // Already stopped (start failed).
        }
        return synchronized(lock) { result }
    }

    /** All resolved addresses; an IPv6 link-local one keeps its scope (`fe80::…%wlan0`). */
    private fun hostsOf(info: NsdServiceInfo): List<String> {
        val all = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.mapNotNull { it.hostAddress } else emptyList()
        @Suppress("DEPRECATION")
        val legacy = info.host?.hostAddress
        return (all + listOfNotNull(legacy)).distinct()
    }

    companion object {
        private const val TAG = "CawRemote"
        const val SERVICE_TYPE = "_caw._tcp"
    }
}
