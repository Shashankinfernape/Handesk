package com.directlink.client

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlin.experimental.xor

object StunClient {
    fun getPublicAddress(socket: DatagramSocket): InetSocketAddress? {
        return try {
            socket.soTimeout = 3000
            // Force IPv4 STUN server so Android doesn't accidentally ask for an IPv6 STUN mapping
            // (142.250.14.127 is stun.l.google.com IPv4)
            val stunServer = InetAddress.getByName("142.250.14.127")
            
            val req = ByteArray(20)
            req[0] = 0x00
            req[1] = 0x01
            // Magic Cookie
            req[4] = 0x21
            req[5] = 0x12
            req[6] = 0xA4.toByte()
            req[7] = 0x42
            // Transaction ID
            val rand = SecureRandom()
            val txId = ByteArray(12)
            rand.nextBytes(txId)
            System.arraycopy(txId, 0, req, 8, 12)

            val sendPacket = DatagramPacket(req, req.size, stunServer, 19302)
            socket.send(sendPacket)

            val buf = ByteArray(1024)
            val recvPacket = DatagramPacket(buf, buf.size)
            socket.receive(recvPacket)

            val len = recvPacket.length
            if (len < 20) return null

            var i = 20
            while (i < len) {
                if (i + 4 > len) break
                val attrType = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i+1].toInt() and 0xFF)
                val attrLen = ((buf[i+2].toInt() and 0xFF) shl 8) or (buf[i+3].toInt() and 0xFF)
                i += 4
                if (i + attrLen > len) break

                if (attrType == 0x0020 || attrType == 0x0001) { // XOR-MAPPED or MAPPED
                    val family = buf[i+1].toInt() and 0xFF
                    if (family == 0x01) { // IPv4
                        val port = ((buf[i+2].toInt() and 0xFF) shl 8) or (buf[i+3].toInt() and 0xFF)
                        if (attrType == 0x0020) {
                            val xorPort = port xor 0x2112
                            val ip = byteArrayOf(
                                (buf[i+4] xor 0x21),
                                (buf[i+5] xor 0x12),
                                (buf[i+6] xor 0xA4.toByte()),
                                (buf[i+7] xor 0x42)
                            )
                            return InetSocketAddress(InetAddress.getByAddress(ip), xorPort)
                        } else {
                            val ip = byteArrayOf(buf[i+4], buf[i+5], buf[i+6], buf[i+7])
                            return InetSocketAddress(InetAddress.getByAddress(ip), port)
                        }
                    }
                }
                i += attrLen
            }
            null
        } catch (e: Exception) {
            Log.e("STUN", "Failed to get public IP", e)
            null
        }
    }
}
