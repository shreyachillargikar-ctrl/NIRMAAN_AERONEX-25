package com.example.camerax

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MavlinkSender(private val usbPort: UsbSerialPort) {

    private val sysId: Byte = 1
    private val compId: Byte = 191.toByte() // MAV_COMP_ID_ONBOARD_COMPUTER
    private var packetSequence: Int = 0

    private var scheduler: ScheduledExecutorService? = null

    fun startHeartbeat() {
        if (scheduler != null && !scheduler!!.isShutdown) return
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleAtFixedRate({ sendHeartbeat() }, 0, 1, TimeUnit.SECONDS)

        // ArduPilot does not stream GLOBAL_POSITION_INT (or most other telemetry)
        // to a new connection automatically -- it must be explicitly requested.
        // Send the request a couple of times a few hundred ms apart in case the
        // first one lands before the autopilot has fully registered the link.
        scheduler?.schedule({ requestDataStreams() }, 300, TimeUnit.MILLISECONDS)
        scheduler?.schedule({ requestDataStreams() }, 1500, TimeUnit.MILLISECONDS)
    }

    /**
     * Asks the autopilot to start streaming telemetry (including
     * GLOBAL_POSITION_INT) to this connection. Uses the legacy but still
     * universally-supported REQUEST_DATA_STREAM message rather than
     * MAV_CMD_SET_MESSAGE_INTERVAL for simplicity. targetSystem/targetComponent
     * default to 1/1, ArduPilot's standard defaults for a single flight
     * controller -- adjust if your SYSID_THISMAV param differs.
     */
    fun requestDataStreams(
        targetSystem: Int = 1,
        targetComponent: Int = 1,
        rateHz: Int = 4
    ) {
        // req_stream_id = 0 -> MAV_DATA_STREAM_ALL: requests every stream
        // category (position, extended status, RC channels, etc.) in one call.
        sendRequestDataStream(targetSystem, targetComponent, streamId = 0, rateHz = rateHz, start = true)
    }

    private fun sendRequestDataStream(
        targetSystem: Int,
        targetComponent: Int,
        streamId: Int,
        rateHz: Int,
        start: Boolean
    ) {
        // REQUEST_DATA_STREAM (#66) field order per MAVLink wire encoding rules
        // (fields sorted by size descending, ties keep declaration order):
        // req_message_rate (uint16) first, then the four uint8 fields.
        val payload = ByteArray(6)
        payload[0] = (rateHz and 0xFF).toByte()
        payload[1] = ((rateHz shr 8) and 0xFF).toByte()
        payload[2] = targetSystem.toByte()
        payload[3] = targetComponent.toByte()
        payload[4] = streamId.toByte()
        payload[5] = if (start) 1 else 0
        sendFrame(messageId = 66, payload = payload, crcExtra = 148)
    }

    fun stopHeartbeat() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    private fun sendHeartbeat() {
        val payload = ByteArray(9)
        // custom_mode (4 bytes, LE) = 0
        payload[4] = 18   // type: MAV_TYPE_ONBOARD_CONTROLLER
        payload[5] = 8    // autopilot: MAV_AUTOPILOT_INVALID
        payload[6] = 0    // base_mode
        payload[7] = 4    // system_status: MAV_STATE_ACTIVE
        payload[8] = 3    // mavlink_version
        sendFrame(messageId = 0, payload = payload, crcExtra = 50)
    }

    fun sendStatusText(text: String, severity: Int = 4) {
        val payload = ByteArray(51)
        payload[0] = severity.toByte()
        val textBytes = text.take(50).toByteArray(Charsets.US_ASCII)
        for (i in textBytes.indices) payload[1 + i] = textBytes[i]
        sendFrame(messageId = 253, payload = payload, crcExtra = 83)
    }

    /**
     * Builds and sends a MAVLink v2 frame (STX 0xFD), matching the version
     * MavlinkUtils.StreamParser expects for incoming messages.
     */
    @Synchronized
    private fun sendFrame(messageId: Int, payload: ByteArray, crcExtra: Int) {
        val header = byteArrayOf(
            0xFD.toByte(),
            payload.size.toByte(),
            0x00, // incompat flags
            0x00, // compat flags
            (packetSequence++ and 0xFF).toByte(),
            sysId,
            compId,
            (messageId and 0xFF).toByte(),
            ((messageId shr 8) and 0xFF).toByte(),
            ((messageId shr 16) and 0xFF).toByte()
        )

        var crc = 0xFFFF
        for (b in header.copyOfRange(1, header.size)) crc = crcAccumulate(b, crc)
        for (b in payload) crc = crcAccumulate(b, crc)
        crc = crcAccumulate(crcExtra.toByte(), crc)

        val frame = header + payload + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())

        try {
            usbPort.write(frame, 1000)
        } catch (e: Exception) {
            // cable unplugged or write buffer full
            Log.e("MavlinkSender", "Write failed for msgId=$messageId: ${e.message}")
        }
    }

    private fun crcAccumulate(byte: Byte, crcIn: Int): Int {
        var tmp = (byte.toInt() and 0xFF) xor (crcIn and 0xFF)
        tmp = tmp xor ((tmp shl 4) and 0xFF)
        var crc = (crcIn ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)
        crc = crc and 0xFFFF
        return crc
    }
}