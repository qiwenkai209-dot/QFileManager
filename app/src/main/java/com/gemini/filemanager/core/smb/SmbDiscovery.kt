package com.gemini.filemanager.core.smb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.gemini.filemanager.data.model.SmbDiscoveredHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class SmbDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onHostFound: (SmbDiscoveredHost) -> Unit
) {
    private var scanJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery(onProgress: ((Int, Int) -> Unit)? = null) {
        stopNsd()
        startNsdDiscovery()
        scanLocalSubnet(onProgress)
    }

    private fun startNsdDiscovery() {
        try {
            val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
                ?: return
            nsdManager = manager

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("_smb._tcp")) {
                        try {
                            @Suppress("DEPRECATION")
                            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    @Suppress("DEPRECATION")
                                    val hostIp = info.host?.hostAddress ?: return
                                    val port = if (info.port > 0) info.port else 445
                                    onHostFound(
                                        SmbDiscoveredHost(
                                            ip = hostIp,
                                            hostName = info.serviceName.orEmpty(),
                                            port = port,
                                            source = "mDNS 发现"
                                        )
                                    )
                                }
                            })
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    stopNsd()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            discoveryListener = listener
            manager.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    fun scanLocalSubnet(onProgress: ((Int, Int) -> Unit)? = null) {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            val localIp = getLocalIpAddress()
            if (localIp == null) {
                onProgress?.invoke(254, 254)
                return@launch
            }
            val prefix = localIp.substringBeforeLast(".") + "."
            val semaphore = Semaphore(48)
            val completed = AtomicInteger(0)

            val jobs = (1..254).map { i ->
                async {
                    val targetIp = "$prefix$i"
                    if (targetIp != localIp) {
                        semaphore.withPermit {
                            if (isPortOpen(targetIp, 445, 350)) {
                                val resolvedName = resolveHostName(targetIp)
                                onHostFound(
                                    SmbDiscoveredHost(
                                        ip = targetIp,
                                        hostName = resolvedName,
                                        port = 445,
                                        source = "局域网扫描"
                                    )
                                )
                            }
                        }
                    }
                    val done = completed.incrementAndGet()
                    onProgress?.invoke(done, 254)
                }
            }
            jobs.awaitAll()
        }
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveHostName(ip: String): String {
        return try {
            val addr = InetAddress.getByName(ip)
            val host = addr.canonicalHostName
            if (host.isNullOrBlank() || host == ip) "" else host.removeSuffix(".local")
        } catch (_: Exception) {
            ""
        }
    }

    fun getLocalIpAddress(): String? {
        return try {
            val candidates = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) {
                            candidates.add(ip)
                        }
                    }
                }
            }
            candidates.firstOrNull {
                it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")
            } ?: candidates.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun stopNsd() {
        val listener = discoveryListener
        val manager = nsdManager
        if (listener != null && manager != null) {
            try {
                manager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
    }

    fun stop() {
        scanJob?.cancel()
        stopNsd()
    }
}

