package com.example.camerax

object MavlinkUtils {

    sealed class MavResult {
        data class Position(val lat: Double, val lon: Double) : MavResult()
        object Heartbeat : MavResult()
    }

    private fun crcAccumulate(byte: Byte, crcIn: Int): Int {
        var tmp = (byte.toInt() and 0xFF) xor (crcIn and 0xFF)
        tmp = tmp xor ((tmp shl 4) and 0xFF)
        var crc = (crcIn ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)
        return crc and 0xFFFF
    }

    class StreamParser {
        private var state = 0
        private var len = 0
        private var msgId = 0
        private val buffer = ByteArray(280)
        private var idx = 0
        private var payloadStart = 0

        fun feed(b: Byte): MavResult? {
            when (state) {
                0 -> if (b == 0xFD.toByte()) { idx = 0; buffer[idx++] = b; state = 1 }
                1 -> { len = b.toInt() and 0xFF; buffer[idx++] = b; state = 2 }
                2 -> { buffer[idx++] = b; state = 3 }
                3 -> { buffer[idx++] = b; state = 4 }
                4 -> { buffer[idx++] = b; state = 5 }
                5 -> { buffer[idx++] = b; state = 6 }
                6 -> { buffer[idx++] = b; state = 7 }
                7 -> { buffer[idx++] = b; msgId = b.toInt() and 0xFF; state = 8 }
                8 -> { buffer[idx++] = b; msgId = msgId or ((b.toInt() and 0xFF) shl 8); state = 9 }
                9 -> {
                    buffer[idx++] = b
                    msgId = msgId or ((b.toInt() and 0xFF) shl 16)
                    payloadStart = idx
                    state = if (len == 0) 11 else 10
                }
                10 -> {
                    buffer[idx++] = b
                    if (idx - payloadStart >= len) state = 11
                }
                11 -> { buffer[idx++] = b; state = 12 }
                12 -> {
                    buffer[idx++] = b
                    state = 0
                    when (msgId) {
                        0 -> return MavResult.Heartbeat
                        33 -> if (len >= 12) {
                            val lat = readInt32LE(buffer, payloadStart + 4)
                            val lon = readInt32LE(buffer, payloadStart + 8)
                            return MavResult.Position(lat / 1e7, lon / 1e7)
                        }
                    }
                }
            }
            if (idx >= buffer.size) state = 0
            return null
        }

        private fun readInt32LE(arr: ByteArray, offset: Int): Int {
            return (arr[offset].toInt() and 0xFF) or
                    ((arr[offset + 1].toInt() and 0xFF) shl 8) or
                    ((arr[offset + 2].toInt() and 0xFF) shl 16) or
                    ((arr[offset + 3].toInt() and 0xFF) shl 24)
        }
    }
}