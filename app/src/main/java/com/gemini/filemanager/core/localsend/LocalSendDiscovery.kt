package com.gemini.filemanager.core.localsend

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import com.gemini.filemanager.data.model.DiscoveredDevice
import com.gemini.filemanager.data.model.LocalSendDeviceDto
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LocalSendDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDeviceFound: (DiscoveredDevice) -> Unit
) {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenJob: Job? = null
    private var socket: MulticastSocket? = null

    private val json = Json { ignoreUnknownKeys = true }
    val myFingerprint = UUID.randomUUID().toString()
    val myAlias = "${Build.BRAND} ${Build.MODEL}".trim()

    // 宽松的 OkHttpClient，支持无视自签名证书以兼容官方 LocalSend 的 HTTPS 模式
    private val pingClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(1200, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }

    fun start() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("LocalSendMulticastLock")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        listenJob = scope.launch(Dispatchers.IO) {
            try {
                val group = InetAddress.getByName(LocalSendConstants.MULTICAST_ADDRESS)
                val s = MulticastSocket(LocalSendConstants.PORT)
                socket = s
                s.reuseAddress = true

                // 加入组播组
                val networkInterfaces = NetworkInterface.getNetworkInterfaces()
                while (networkInterfaces.hasMoreElements()) {
                    val nif = networkInterfaces.nextElement()
                    if (nif.isUp && !nif.isLoopback && nif.supportsMulticast()) {
                        try {
                            s.joinGroup(InetSocketAddress(group, LocalSendConstants.PORT), nif)
                        } catch (ignored: Exception) {}
                    }
                }

                val buffer = ByteArray(4096)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    s.receive(packet)
                    val senderIp = packet.address.hostAddress ?: continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    try {
                        val deviceDto = json.decodeFromString<LocalSendDeviceDto>(text)
                        if (deviceDto.fingerprint != myFingerprint) {
                            val discovered = DiscoveredDevice(
                                ip = senderIp,
                                port = deviceDto.port,
                                device = deviceDto
                            )
                            onDeviceFound(discovered)
                        }
                    } catch (ignored: Exception) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 发送复合广播（组播 + 局域网广播）
        sendAnnouncement()
        // 自动触发一次局域网全网段并发探测
        scanLocalSubnet()
    }

    /**
     * 发送 UDP 复合上线公告：组播 + 全网广播 + 局域网定向广播
     */
    fun sendAnnouncement() {
        scope.launch(Dispatchers.IO) {
            val myInfo = LocalSendDeviceDto(
                alias = myAlias,
                version = LocalSendConstants.PROTOCOL_VERSION,
                deviceModel = Build.MODEL,
                deviceType = "mobile",
                fingerprint = myFingerprint,
                port = LocalSendConstants.PORT,
                protocol = "http",
                download = true,
                announce = true
            )
            val data = json.encodeToString(LocalSendDeviceDto.serializer(), myInfo).toByteArray(Charsets.UTF_8)

            // 1. 发送至组播地址 224.0.0.167
            try {
                val group = InetAddress.getByName(LocalSendConstants.MULTICAST_ADDRESS)
                val s = MulticastSocket()
                val packet = DatagramPacket(data, data.size, group, LocalSendConstants.PORT)
                s.send(packet)
                s.close()
            } catch (ignored: Exception) {}

            // 2. 发送至通用广播 255.255.255.255
            try {
                val broadcastSocket = DatagramSocket()
                broadcastSocket.broadcast = true
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, broadcastAddr, LocalSendConstants.PORT)
                broadcastSocket.send(packet)

                // 3. 计算并发送至本网段的特定定向广播（如 192.168.1.255）
                getLocalIpAddress()?.let { localIp ->
                    val subnetBroadcast = localIp.substringBeforeLast(".") + ".255"
                    val subnetAddr = InetAddress.getByName(subnetBroadcast)
                    broadcastSocket.send(DatagramPacket(data, data.size, subnetAddr, LocalSendConstants.PORT))
                }
                broadcastSocket.close()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * 针对家用/企业路由器屏蔽组播的情况，执行全网段 HTTP/HTTPS 高并发 Ping 探测
     */
    fun scanLocalSubnet(onProgress: ((Int, Int) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val localIp = getLocalIpAddress() ?: return@launch
            val prefix = localIp.substringBeforeLast(".") + "."

            val deferreds = mutableListOf<Deferred<Unit>>()
            var completedCount = 0

            for (i in 1..254) {
                val targetIp = "$prefix$i"
                if (targetIp == localIp) continue

                deferreds.add(async {
                    pingDevice(targetIp)
                    synchronized(this@LocalSendDiscovery) {
                        completedCount++
                        onProgress?.invoke(completedCount, 254)
                    }
                    Unit
                })
            }
            deferreds.awaitAll()
        }
    }

    /**
     * 探测单个 IP 是否运行了 LocalSend（兼容 HTTP 与 HTTPS 官方客户端）
     */
    suspend fun pingDevice(ip: String): DiscoveredDevice? = withContext(Dispatchers.IO) {
        // 先试 HTTPS，因为很多官方桌面版默认开启 HTTPS
        val protocols = listOf("https", "http")
        for (proto in protocols) {
            val url = "$proto://$ip:${LocalSendConstants.PORT}${LocalSendConstants.PATH_INFO}"
            try {
                val req = Request.Builder().url(url).build()
                val resp = pingClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: continue
                    val dto = json.decodeFromString<LocalSendDeviceDto>(body)
                    if (dto.fingerprint != myFingerprint) {
                        val device = DiscoveredDevice(
                            ip = ip,
                            port = dto.port,
                            device = dto.copy(protocol = proto)
                        )
                        onDeviceFound(device)
                        return@withContext device
                    }
                }
            } catch (ignored: Exception) {}
        }
        null
    }

    fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    fun stop() {
        listenJob?.cancel()
        try {
            socket?.close()
        } catch (ignored: Exception) {}
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (ignored: Exception) {}
    }
}
