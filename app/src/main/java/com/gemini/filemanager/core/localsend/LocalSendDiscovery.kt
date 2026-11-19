package com.gemini.filemanager.core.localsend

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.gemini.filemanager.data.model.DiscoveredDevice
import com.gemini.filemanager.data.model.LocalSendDeviceDto
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LocalSendDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    val myAlias: String,
    val myFingerprint: String,
    private val onDeviceFound: (DiscoveredDevice) -> Unit
) {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenJob: Job? = null
    private var socket: MulticastSocket? = null

    private val json = Json { ignoreUnknownKeys = true }

    // 宽松且高并发的 OkHttpClient，支持无视自签名证书以兼容官方 LocalSend 的 HTTPS 模式
    private val pingClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        val dispatcher = Dispatcher().apply {
            maxRequests = 256
            maxRequestsPerHost = 256
        }

        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(1000, TimeUnit.MILLISECONDS)
            .readTimeout(1200, TimeUnit.MILLISECONDS)
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

                // 加入组播组（绑定所有活动且支持组播的网卡）
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

                            // 双方即时感知核心优化：当收到对端广播的上线宣告(announce == true)时，
                            // 立即向对方发送单播回应及 HTTP 注册，让对端也瞬间获得本机信息
                            if (deviceDto.announce == true) {
                                sendDirectAnnouncement(senderIp, deviceDto.port)
                            }
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
     * 发送 UDP 复合上线公告：多网卡组播 + 全网广播 + 局域网定向广播
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

            // 1. 发送至组播地址 224.0.0.167（针对每个活动的非环回网卡显式发送）
            try {
                val group = InetAddress.getByName(LocalSendConstants.MULTICAST_ADDRESS)
                val nifs = NetworkInterface.getNetworkInterfaces()
                var sentAny = false
                while (nifs.hasMoreElements()) {
                    val nif = nifs.nextElement()
                    if (nif.isUp && !nif.isLoopback && nif.supportsMulticast()) {
                        try {
                            val s = MulticastSocket()
                            s.networkInterface = nif
                            val packet = DatagramPacket(data, data.size, group, LocalSendConstants.PORT)
                            s.send(packet)
                            s.close()
                            sentAny = true
                        } catch (ignored: Exception) {}
                    }
                }
                if (!sentAny) {
                    val s = MulticastSocket()
                    s.send(DatagramPacket(data, data.size, group, LocalSendConstants.PORT))
                    s.close()
                }
            } catch (ignored: Exception) {}

            // 2. 发送至通用广播 255.255.255.255 与定向子网广播
            try {
                val broadcastSocket = DatagramSocket()
                broadcastSocket.broadcast = true
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(data, data.size, broadcastAddr, LocalSendConstants.PORT)
                broadcastSocket.send(packet)

                // 计算并发送至本网段定向广播（如 192.168.1.255）
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
     * 单播直连回应给指定设备，实现双向即时对等发现
     */
    fun sendDirectAnnouncement(targetIp: String, targetPort: Int) {
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
                announce = false
            )
            val data = json.encodeToString(LocalSendDeviceDto.serializer(), myInfo).toByteArray(Charsets.UTF_8)
            try {
                val unicastSocket = DatagramSocket()
                val targetAddr = InetAddress.getByName(targetIp)
                unicastSocket.send(DatagramPacket(data, data.size, targetAddr, targetPort))
                unicastSocket.close()
            } catch (ignored: Exception) {}

            // 同时通过 HTTP Register 规范向对端登记
            registerToRemoteDevice(targetIp, targetPort, myInfo)
        }
    }

    /**
     * 遵循 LocalSend 官方协议规范，主动调用对方的 /api/localsend/v2/register 进行对等注册
     */
    private fun registerToRemoteDevice(targetIp: String, targetPort: Int, myInfo: LocalSendDeviceDto) {
        val protocols = listOf("http", "https")
        val jsonStr = json.encodeToString(LocalSendDeviceDto.serializer(), myInfo)
        val body = jsonStr.toRequestBody("application/json; charset=utf-8".toMediaType())

        for (proto in protocols) {
            val url = "$proto://$targetIp:$targetPort${LocalSendConstants.PATH_REGISTER}"
            try {
                val req = Request.Builder().url(url).post(body).build()
                pingClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return
                    }
                }
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
