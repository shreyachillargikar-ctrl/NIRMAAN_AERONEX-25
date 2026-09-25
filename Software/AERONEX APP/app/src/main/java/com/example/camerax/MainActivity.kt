package com.example.camerax

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var interpreter: Interpreter
    private lateinit var statusText: TextView
    private lateinit var overlayView: OverlayView
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var usbSerialPort: UsbSerialPort? = null
    private var mavlinkSender: MavlinkSender? = null
    private val mavParser = MavlinkUtils.StreamParser()

    @Volatile private var lastLat: Double = 0.0
    @Volatile private var lastLon: Double = 0.0
    @Volatile private var lastFixTimeMs: Long = 0
    @Volatile private var lastPixhawkHeartbeatMs: Long = 0L
    private var lastAlertTimeMs: Long = 0

    // Tensor configuration
    private var inputWidth: Int = 640
    private var inputHeight: Int = 640
    private var isChannelFirst: Boolean = false
    private var inputBuffer: ByteBuffer? = null
    private var pixels: IntArray? = null
    private var gpuDelegate: GpuDelegate? = null

    // High-speed CPU Bulk Transfer Arrays
    private var floatInputArray: FloatArray = FloatArray(0)
    private var flatOutputArray: FloatArray = FloatArray(0)

    // Pre-calculated 256-element Lookup Table to eliminate float division in loops
    private val NORM_LUT = FloatArray(256) { it / 255.0f }

    // Canvas & bitmaps for zero-GC latency
    private var letterboxBitmap: Bitmap? = null
    private var letterboxCanvas: Canvas? = null
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    // Output parsing configuration
    private var outputShape: IntArray = intArrayOf(1, 5, 8400)
    private var outputByteBuffer: ByteBuffer? = null
    private var numChannels: Int = 0
    private var numAnchors: Int = 0
    private var isTransposed: Boolean = false

    // FPS & Hardware Tracking
    private var inferenceMode: String = "CPU"
    private var frameCount: Int = 0
    private var lastFpsTimestampMs: Long = System.currentTimeMillis()
    private var currentFps: Float = 0f

    data class Detection(val rect: RectF, val conf: Float)

    companion object {
        const val CONF_THRESHOLD = 0.35f
        const val IOU_THRESHOLD = 0.45f
        const val ALERT_COOLDOWN_MS = 2000L
        const val ACTION_USB_PERMISSION = "com.example.camerax.USB_PERMISSION"

        // Pixhawk 2.4.8 USB CDC-ACM identifiers, confirmed via logcat for this
        // board: VID 0x1209 is the pid.codes open-source registry VID; PID 0x5741
        // is ArduPilot's ChibiOS-based firmware running normally (0x5740 would be
        // its DFU bootloader mode instead).
        const val PIXHAWK_VID_ARDUPILOT: Int = 0x1209
        const val PIXHAWK_PID_ARDUPILOT: Int = 0x5741
        const val ACTION_USB_ATTACHED = UsbManager.ACTION_USB_DEVICE_ATTACHED

        // EDIT THIS: your PC's LAN IP + port, exactly as server.py prints it
        // on startup (e.g. "http://192.168.1.42:8000"). Phone and PC must be
        // on the same Wi-Fi network. Leave the placeholder text in place and
        // this feature quietly no-ops instead of failing.
        const val SERVER_BASE_URL = "http://PC_LAN_IP_HERE:8000"
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_ATTACHED == intent.action) {
                Log.i("Detector", "USB device attached broadcast received, re-scanning")
                setupUsbSerial()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openSerial(it) }
                    } else {
                        Log.w("Detector", "USB permission denied by user")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        overlayView = findViewById(R.id.overlayView)

        statusText.text = "Initializing GPU & Model..."

        val attachFilter = IntentFilter(ACTION_USB_ATTACHED)
        ContextCompat.registerReceiver(this, usbAttachReceiver, attachFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Offload heavy model initialization to background thread to unblock main UI thread
        cameraExecutor.execute {
            loadModel()

            // Once model is loaded, start Camera and USB on the main thread
            runOnUiThread {
                setupUsbSerial()

                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                } else {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.CAMERA),
                        100
                    )
                }
            }
        }
    }

    private fun loadModel() {
        try {
            val assetFileDescriptor = assets.openFd("model.tflite")
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val modelBuffer = inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.declaredLength
            )

            val options = Interpreter.Options()

            // Request GPU Acceleration with FP16 Precision
            try {
                val delegateOptions = GpuDelegate.Options().apply {
                    setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    setPrecisionLossAllowed(true)
                }
                gpuDelegate = GpuDelegate(delegateOptions)
                options.addDelegate(gpuDelegate)
                inferenceMode = "GPU"
                Log.i("Detector", "GPU Delegate requested with FP16 precision")
            } catch (e: Exception) {
                gpuDelegate?.close()
                gpuDelegate = null
                options.setNumThreads(4)
                inferenceMode = "CPU"
                Log.w("Detector", "GPU Delegate setup failed, using CPU", e)
            }

            try {
                interpreter = Interpreter(modelBuffer, options)
                Log.i("Detector", "Interpreter initialized successfully on $inferenceMode")
            } catch (e: Exception) {
                if (inferenceMode == "GPU") {
                    Log.w("Detector", "Model failed with GPU delegate, falling back to CPU...", e)
                    gpuDelegate?.close()
                    gpuDelegate = null
                    val cpuOptions = Interpreter.Options().apply { setNumThreads(4) }
                    interpreter = Interpreter(modelBuffer, cpuOptions)
                    inferenceMode = "CPU"
                } else {
                    throw e
                }
            }

            val inputShape = interpreter.getInputTensor(0).shape()
            if (inputShape.size == 4) {
                if (inputShape[1] == 3) {
                    isChannelFirst = true
                    inputHeight = inputShape[2]
                    inputWidth = inputShape[3]
                } else {
                    isChannelFirst = false
                    inputHeight = inputShape[1]
                    inputWidth = inputShape[2]
                }
            }

            inputBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            pixels = IntArray(inputWidth * inputHeight)
            floatInputArray = FloatArray(1 * inputWidth * inputHeight * 3)

            letterboxBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
            letterboxCanvas = Canvas(letterboxBitmap!!)

            outputShape = interpreter.getOutputTensor(0).shape()
            if (outputShape.size == 3) {
                val d1 = outputShape[1]
                val d2 = outputShape[2]
                isTransposed = d1 > d2
                numChannels = if (isTransposed) d2 else d1
                numAnchors = if (isTransposed) d1 else d2

                val outSize = outputShape.fold(1) { acc, v -> acc * v }
                outputByteBuffer = ByteBuffer.allocateDirect(outSize * 4).apply {
                    order(ByteOrder.nativeOrder())
                }
                flatOutputArray = FloatArray(outSize)
            }
            Log.i("Detector", "TFLite model initialized on $inferenceMode. Output shape=${outputShape.joinToString()}")
        } catch (e: Exception) {
            Log.e("Detector", "Failed to load TFLite model", e)
            runOnUiThread { statusText.text = "Model Error: ${e.message}" }
        }
    }

    private fun fallbackToCpu() {
        Log.w("Detector", "GPU Inference failed! Switching to CPU...")
        try {
            interpreter.close()
            gpuDelegate?.close()
            gpuDelegate = null

            val assetFileDescriptor = assets.openFd("model.tflite")
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val modelBuffer = inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.declaredLength
            )
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(modelBuffer, options)
            inferenceMode = "CPU"
        } catch (e: Exception) {
            Log.e("Detector", "CPU Recovery failed", e)
        }
    }

    /**
     * IMPORTANT: UsbSerialProber.getDefaultProber() only recognizes a hardcoded
     * list of known chip VID/PIDs (FTDI, CP210x, CH340, Prolific, a few Arduino
     * boards). A Pixhawk's onboard STM32 CDC-ACM virtual COM port is NOT in that
     * default table, so the default prober silently returns an empty driver list
     * and openSerial() is never called. We build a custom ProbeTable that starts
     * from the default table (keeps FTDI/CP210x/etc support for other hardware)
     * and explicitly registers the Pixhawk's VID/PID against CdcAcmSerialDriver.
     */
    private var permissionReceiverRegistered = false

    private fun setupUsbSerial() {
        if (usbSerialPort != null) {
            Log.i("Detector", "setupUsbSerial: port already open, skipping re-scan")
            return
        }

        val usbManager = getSystemService(USB_SERVICE) as UsbManager

        for ((_, device) in usbManager.deviceList) {
            Log.i(
                "Detector",
                "USB device found: VID=0x${device.vendorId.toString(16)} " +
                        "PID=0x${device.productId.toString(16)} name=${device.deviceName}"
            )
        }

        val customTable = UsbSerialProber.getDefaultProbeTable()
        customTable.addProduct(PIXHAWK_VID_ARDUPILOT, PIXHAWK_PID_ARDUPILOT, CdcAcmSerialDriver::class.java)
        val prober = UsbSerialProber(customTable)

        val availableDrivers = prober.findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            Log.w(
                "Detector",
                "No matching USB serial driver found. No device with VID=0x${PIXHAWK_VID_ARDUPILOT.toString(16)} " +
                        "PID=0x${PIXHAWK_PID_ARDUPILOT.toString(16)} is currently attached."
            )
            runOnUiThread { statusText.text = "PIXHAWK: NO USB DRIVER MATCH" }
            return
        }

        val driver = availableDrivers[0]
        if (!permissionReceiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            permissionReceiverRegistered = true
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val usbPermissionIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val permissionIntent = PendingIntent.getBroadcast(this, 0, usbPermissionIntent, flags)

        if (usbManager.hasPermission(driver.device)) {
            openSerial(driver.device, prober)
        } else {
            usbManager.requestPermission(driver.device, permissionIntent)
        }

        // Keep a reference to the prober used, so a later permission-granted
        // callback can re-resolve the driver with the same custom table.
        pendingProber = prober
    }

    private var pendingProber: UsbSerialProber? = null

    private fun openSerial(device: UsbDevice, proberOverride: UsbSerialProber? = null) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val prober = proberOverride ?: pendingProber ?: UsbSerialProber.getDefaultProber()
        val driver = prober.findAllDrivers(usbManager)
            .firstOrNull { it.device == device } ?: run {
            Log.e("Detector", "openSerial: no driver resolved for granted device")
            return
        }
        val connection = usbManager.openDevice(driver.device) ?: run {
            Log.e("Detector", "openSerial: usbManager.openDevice returned null")
            return
        }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(57600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort = port
            Log.i("Detector", "USB serial port opened successfully")

            // Initialize MavlinkSender & start continuous 1 Hz heartbeat to register link with Pixhawk
            mavlinkSender = MavlinkSender(port)
            mavlinkSender?.startHeartbeat()

            startMavlinkReader(port)
        } catch (e: Exception) {
            Log.e("Detector", "Serial open failed", e)
            runOnUiThread { statusText.text = "PIXHAWK: SERIAL OPEN FAILED - ${e.message}" }
        }
    }

    private fun startMavlinkReader(port: UsbSerialPort) {
        Thread {
            val buf = ByteArray(256)
            while (usbSerialPort != null) {
                try {
                    val n = port.read(buf, 500)
                    for (i in 0 until n) {
                        when (val result = mavParser.feed(buf[i])) {
                            is MavlinkUtils.MavResult.Position -> {
                                lastLat = result.lat
                                lastLon = result.lon
                                lastFixTimeMs = System.currentTimeMillis()
                            }
                            is MavlinkUtils.MavResult.Heartbeat -> {
                                lastPixhawkHeartbeatMs = System.currentTimeMillis()
                            }
                            null -> {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun sendDetectionAlert(confidence: Float, count: Int) {
        // 6 decimal places (~0.11m resolution) still comfortably fits the
        // MAVLink v1 STATUSTEXT 50-char payload limit alongside count/confidence.
        val text = "HUMANS:$count c=%.2f %.6f,%.6f".format(
            confidence, lastLat, lastLon
        )
        try {
            mavlinkSender?.sendStatusText(text, severity = 4) // severity 4 = WARNING
        } catch (e: Exception) {
            Log.e("Detector", "Failed to send STATUSTEXT", e)
        }
        postDetectionToServer(confidence, count)
    }

    /**
     * Fire-and-forget HTTP POST to the local relay server (server.py) so the
     * live_map.html page can pick this detection up on its next poll. This is
     * independent of the MAVLink link -- it goes over the phone's own Wi-Fi.
     * SERVER_BASE_URL must point at your PC's LAN IP (run server.py and it
     * prints the address to use). Requires INTERNET permission and
     * android:usesCleartextTraffic="true" in AndroidManifest.xml since this
     * uses plain HTTP for local-network simplicity.
     */
    private fun postDetectionToServer(confidence: Float, count: Int) {
        if (SERVER_BASE_URL.contains("PC_LAN_IP_HERE")) return // not configured yet

        val lat = lastLat
        val lon = lastLon
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        Thread {
            try {
                val json = org.json.JSONObject().apply {
                    put("lat", lat)
                    put("lon", lon)
                    put("count", count)
                    put("conf", confidence)
                    put("time", timeStr)
                    put("ts", System.currentTimeMillis())
                }
                val url = java.net.URL("$SERVER_BASE_URL/detection")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
                conn.responseCode // triggers the request
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("Detector", "postDetectionToServer failed: ${e.message}")
            }
        }.start()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(640, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> runDetection(imageProxy) }
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e("Detector", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun readOutput(c: Int, i: Int): Float {
        val index = if (isTransposed) (i * numChannels + c) else (c * numAnchors + i)
        return flatOutputArray[index]
    }

    private fun runDetection(imageProxy: ImageProxy) {
        val outBuf = outputByteBuffer
        val inBuf = inputBuffer
        val px = pixels
        val lBitmap = letterboxBitmap
        val lCanvas = letterboxCanvas

        if (!::interpreter.isInitialized || outBuf == null || inBuf == null || px == null || lBitmap == null || lCanvas == null) {
            imageProxy.close()
            return
        }

        var frameBitmap: Bitmap? = null

        try {
            frameCount++
            val now = System.currentTimeMillis()
            val timeDiff = now - lastFpsTimestampMs
            if (timeDiff >= 1000) {
                currentFps = (frameCount * 1000f) / timeDiff
                frameCount = 0
                lastFpsTimestampMs = now
            }

            frameBitmap = imageProxy.toBitmap()

            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val srcW = frameBitmap.width.toFloat()
            val srcH = frameBitmap.height.toFloat()

            val isRotated = rotation == 90f || rotation == 270f
            val rotatedW = if (isRotated) srcH else srcW
            val rotatedH = if (isRotated) srcW else srcH

            val scale = min(inputWidth.toFloat() / rotatedW, inputHeight.toFloat() / rotatedH)
            val scaledW = rotatedW * scale
            val scaledH = rotatedH * scale
            val padX = (inputWidth - scaledW) / 2f
            val padY = (inputHeight - scaledH) / 2f

            matrix.reset()
            matrix.postTranslate(-srcW / 2f, -srcH / 2f)
            matrix.postRotate(rotation)
            matrix.postScale(scale, scale)
            matrix.postTranslate(inputWidth / 2f, inputHeight / 2f)

            lCanvas.drawColor(Color.rgb(114, 114, 114))
            lCanvas.drawBitmap(frameBitmap, matrix, letterboxPaint)

            lBitmap.getPixels(px, 0, inputWidth, 0, 0, inputWidth, inputHeight)

            val totalPixels = inputWidth * inputHeight
            if (isChannelFirst) {
                var rIdx = 0
                var gIdx = totalPixels
                var bIdx = totalPixels * 2
                for (i in 0 until totalPixels) {
                    val p = px[i]
                    floatInputArray[rIdx++] = NORM_LUT[(p shr 16) and 0xFF]
                    floatInputArray[gIdx++] = NORM_LUT[(p shr 8) and 0xFF]
                    floatInputArray[bIdx++] = NORM_LUT[p and 0xFF]
                }
            } else {
                var idx = 0
                for (i in 0 until totalPixels) {
                    val p = px[i]
                    floatInputArray[idx++] = NORM_LUT[(p shr 16) and 0xFF]
                    floatInputArray[idx++] = NORM_LUT[(p shr 8) and 0xFF]
                    floatInputArray[idx++] = NORM_LUT[p and 0xFF]
                }
            }

            inBuf.rewind()
            inBuf.asFloatBuffer().put(floatInputArray)
            outBuf.rewind()

            try {
                interpreter.run(inBuf, outBuf)
            } catch (e: Exception) {
                if (inferenceMode == "GPU") {
                    fallbackToCpu()
                    return
                } else {
                    throw e
                }
            }

            outBuf.rewind()
            outBuf.asFloatBuffer().get(flatOutputArray)

            val rawDetections = mutableListOf<Detection>()

            for (i in 0 until numAnchors) {
                var maxClassConf = 0f
                for (c in 4 until numChannels) {
                    val conf = readOutput(c, i)
                    if (conf > maxClassConf) maxClassConf = conf
                }

                if (maxClassConf > CONF_THRESHOLD) {
                    val cx = readOutput(0, i)
                    val cy = readOutput(1, i)
                    val w  = readOutput(2, i)
                    val h  = readOutput(3, i)

                    val pixelCx = if (cx <= 1.0f) cx * inputWidth else cx
                    val pixelCy = if (cy <= 1.0f) cy * inputHeight else cy
                    val pixelW  = if (w <= 1.0f) w * inputWidth else w
                    val pixelH  = if (h <= 1.0f) h * inputHeight else h

                    val unpadX = (pixelCx - padX) / scaledW
                    val unpadY = (pixelCy - padY) / scaledH
                    val unpadW = pixelW / scaledW
                    val unpadH = pixelH / scaledH

                    val left   = unpadX - unpadW / 2f
                    val top    = unpadY - unpadH / 2f
                    val right  = unpadX + unpadW / 2f
                    val bottom = unpadY + unpadH / 2f

                    rawDetections.add(Detection(RectF(left, top, right, bottom), maxClassConf))
                }
            }

            val finalDetections = applyNMS(rawDetections, IOU_THRESHOLD)
            val humanCount = finalDetections.size
            val highestConf = if (finalDetections.isNotEmpty()) finalDetections.maxOf { it.conf } else 0f

            val viewWidth = overlayView.width.toFloat()
            val viewHeight = overlayView.height.toFloat()

            val screenBoxes = finalDetections.map {
                RectF(
                    it.rect.left * viewWidth,
                    it.rect.top * viewHeight,
                    it.rect.right * viewWidth,
                    it.rect.bottom * viewHeight
                )
            }

            val currentTime = System.currentTimeMillis()
            val isPixhawkConnected = (currentTime - lastPixhawkHeartbeatMs) < 3000
            val isGpsActive = (currentTime - lastFixTimeMs) < 3000

            val pixhawkStatusStr = when {
                !isPixhawkConnected -> "PIXHAWK: NO RX DATA"
                isGpsActive -> "PIXHAWK: CONNECTED (GPS OK)"
                else -> "PIXHAWK: CONNECTED (NO GPS FIX)"
            }

            runOnUiThread {
                overlayView.setResults(screenBoxes)
                val linkAlive = (System.currentTimeMillis() - lastPixhawkHeartbeatMs) < 3000
                val linkText = if (linkAlive) "PIXHAWK: LINKED" else "PIXHAWK: NO LINK"
                statusText.text = "[%s] %.1f FPS | Humans: %d | MaxConf: %.2f | %s | Lat:%.5f Lon:%.5f".format(
                    inferenceMode, currentFps, humanCount, highestConf, linkText, lastLat, lastLon
                )
            }

            val alertNow = System.currentTimeMillis()
            if (humanCount > 0 && (alertNow - lastAlertTimeMs) > ALERT_COOLDOWN_MS) {
                lastAlertTimeMs = alertNow
                sendDetectionAlert(highestConf, humanCount)
            }

        } catch (e: Exception) {
            Log.e("Detector", "Pipeline error", e)
        } finally {
            frameBitmap?.recycle()
            imageProxy.close()
        }
    }

    private fun applyNMS(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        val sorted = detections.sortedByDescending { it.conf }
        val selected = mutableListOf<Detection>()

        for (box in sorted) {
            var shouldSelect = true
            for (selectedBox in selected) {
                if (calculateIoU(box.rect, selectedBox.rect) > iouThreshold) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) selected.add(box)
        }
        return selected
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val intersectLeft   = max(a.left, b.left)
        val intersectTop    = max(a.top, b.top)
        val intersectRight  = min(a.right, b.right)
        val intersectBottom = min(a.bottom, b.bottom)

        if (intersectRight < intersectLeft || intersectBottom < intersectTop) return 0f

        val intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)

        return intersectionArea / (areaA + areaB - intersectionArea)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mavlinkSender?.stopHeartbeat()
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        if (permissionReceiverRegistered) {
            try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        }
        try { usbSerialPort?.close() } catch (_: Exception) {}
        cameraExecutor.shutdown()
        if (::interpreter.isInitialized) {
            interpreter.close()
        }
        gpuDelegate?.close()
    }
}