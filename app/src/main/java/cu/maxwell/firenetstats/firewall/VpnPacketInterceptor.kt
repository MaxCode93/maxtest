package cu.maxwell.firenetstats.firewall

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VpnPacketInterceptor(
    private val vpnService: VpnService,
    private val connectivityManager: ConnectivityManager,
    private val foregroundAppMonitor: ForegroundAppMonitor,
    private val interceptNotificationManager: InterceptNotificationManager,
    private val appInterceptPreferences: AppInterceptPreferences,
    private val vpnAddress: Inet4Address,
    private val onBlockedAttempt: (String) -> Unit
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var inputStream: FileInputStream? = null
    private var tunnelInterface: ParcelFileDescriptor? = null

    @Volatile
    private var blockedPackages: Set<String> = emptySet()

    fun updateBlockedPackages(newBlockedPackages: Set<String>) {
        blockedPackages = newBlockedPackages
    }

    fun start(interfaceDescriptor: ParcelFileDescriptor) {
        stop()
        tunnelInterface = interfaceDescriptor
        inputStream = FileInputStream(interfaceDescriptor.fileDescriptor)

        running.set(true)
        executor.execute { readPackets() }
    }

    fun stop() {
        running.set(false)

        try {
            inputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing VPN input stream", e)
        } finally {
            inputStream = null
        }

        try {
            tunnelInterface?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing VPN interface descriptor", e)
        } finally {
            tunnelInterface = null
        }
    }

    private fun readPackets() {
        val buffer = ByteArray(32_768)
        while (running.get()) {
            try {
                val length = inputStream?.read(buffer) ?: break
                if (length <= 0) continue

                val packet = parseIpv4Packet(buffer, length) ?: continue
                // Solo nos interesan paquetes salientes desde la dirección del túnel
                if (packet.sourceAddress != vpnAddress) continue

                val uid = resolveUid(packet) ?: continue
                val packages = vpnService.packageManager.getPackagesForUid(uid) ?: continue

                for (pkg in packages) {
                    if (blockedPackages.contains(pkg)) {
                        handleBlockedAttempt(pkg)
                        break
                    }
                }
            } catch (e: IOException) {
                if (running.get()) {
                    Log.e(TAG, "Error reading from VPN tunnel", e)
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error handling VPN packet", e)
            }
        }

        running.set(false)
    }

    private fun handleBlockedAttempt(packageName: String) {
        if (!appInterceptPreferences.isInterceptNotificationsEnabled()) {
            return
        }

        if (interceptNotificationManager.hasBeenNotifiedInSession(packageName)) {
            return
        }

        if (!foregroundAppMonitor.isForegroundApp(packageName)) {
            return
        }

        onBlockedAttempt(packageName)
    }

    private fun resolveUid(packet: ParsedPacket): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "getConnectionOwnerUid requires Android 12+. Skipping UID resolution.")
            return null
        }

        return try {
            val sourceSocket = InetSocketAddress(packet.sourceAddress, packet.sourcePort)
            val destinationSocket = InetSocketAddress(packet.destinationAddress, packet.destinationPort)
            connectivityManager.getConnectionOwnerUid(packet.protocol, sourceSocket, destinationSocket)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to resolve connection owner UID", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve UID for packet", e)
            null
        }
    }

    private fun parseIpv4Packet(raw: ByteArray, length: Int): ParsedPacket? {
        if (length < IPV4_MIN_HEADER) return null

        val version = (raw[0].toInt() shr 4) and 0xF
        if (version != 4) return null

        val headerLength = (raw[0].toInt() and 0x0F) * 4
        if (length < headerLength + 4) return null

        val protocol = raw[9].toInt() and 0xFF
        if (protocol != IPPROTO_TCP && protocol != IPPROTO_UDP) return null

        val sourceAddress = InetAddress.getByAddress(raw.copyOfRange(12, 16)) as Inet4Address
        val destinationAddress = InetAddress.getByAddress(raw.copyOfRange(16, 20)) as Inet4Address

        val portBuffer = ByteBuffer.wrap(raw, headerLength, length - headerLength)
        portBuffer.order(ByteOrder.BIG_ENDIAN)

        val sourcePort = portBuffer.short.toInt() and 0xFFFF
        val destinationPort = portBuffer.short.toInt() and 0xFFFF

        return ParsedPacket(protocol, sourceAddress, destinationAddress, sourcePort, destinationPort)
    }

    companion object {
        private const val TAG = "VpnPacketInterceptor"
        private const val IPV4_MIN_HEADER = 20
        private const val IPPROTO_TCP = 6
        private const val IPPROTO_UDP = 17
    }
}

data class ParsedPacket(
    val protocol: Int,
    val sourceAddress: Inet4Address,
    val destinationAddress: Inet4Address,
    val sourcePort: Int,
    val destinationPort: Int
)