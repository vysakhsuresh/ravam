package com.layerbit.ravam.capture.shell

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Finds the Wireless Debugging **pairing** service on this phone over mDNS, so the user
 * only has to read the six-digit code and never the port. The service
 * (`_adb-tls-pairing._tcp`) is advertised by the system only while the "Pair device with
 * pairing code" dialog is open, which is exactly when we want it.
 *
 * Uses the system NsdManager, so it needs no INTERNET-level socket of its own and no extra
 * permission. Callbacks arrive on a binder thread; the caller marshals to the UI thread.
 */
class PairingDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null

    fun start(onFound: (host: String, port: Int) -> Unit) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: return
                            onFound(host, resolved.port)
                        }
                    })
                }
            }
            override fun onServiceLost(info: NsdServiceInfo?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                android.util.Log.w("RavamAdb", "mDNS start failed: type=$serviceType err=$errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }
        listener = l
        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    private companion object {
        const val SERVICE_TYPE = "_adb-tls-pairing._tcp"
    }
}
