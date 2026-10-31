package com.usbcam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.ImageCapture
import com.herohan.uvcapp.VideoCapture
import com.serenegiant.opengl.renderer.MirrorMode
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.widget.AspectRatioTextureView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单页 USB（UVC）摄像头应用：
 * 插上摄像头 -> 自动申请权限 -> 预览 -> 拍照 / 录像 / 切分辨率 / 旋转 / 镜像 / 换设备。
 */
class MainActivity : Activity(), ICameraHelper.StateCallback {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMISSIONS = 1001

        private val COLOR_BLUE = 0xFF2D6CDF.toInt()
        private val COLOR_RED = 0xFFD93A3A.toInt()
        private val COLOR_DARK = 0xFF2E2E2E.toInt()
    }

    // ---------- views ----------
    private lateinit var previewView: AspectRatioTextureView
    private lateinit var tipText: TextView
    private lateinit var statusText: TextView
    private lateinit var recordText: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnRecord: Button
    private lateinit var btnFormat: Button
    private lateinit var btnRotate: Button
    private lateinit var btnMirror: Button
    private lateinit var btnSwitch: Button

    // ---------- state ----------
    private var helper: CameraHelper? = null
    private var device: UsbDevice? = null
    private var deviceLabel: String = "USB 摄像头"
    private var cameraOpened = false
    private var recording = false
    private var selecting = false
    private var currentSize: Size? = null
    private var rotation = 0
    private var mirror = MirrorMode.MIRROR_NORMAL
    private var recordStartedAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = (SystemClock.elapsedRealtime() - recordStartedAt) / 1000
            recordText.text = String.format(
                Locale.US,
                "● %02d:%02d:%02d",
                elapsed / 3600,
                elapsed % 3600 / 60,
                elapsed % 60
            )
            mainHandler.postDelayed(this, 500)
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            helper?.addSurface(surface, false)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            helper?.removeSurface(surface)
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        }
    }

    // =====================================================================
    // lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        initCameraHelper()
        updateUi()

        handleUsbIntent(intent)

        if (!hasCameraPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQ_PERMISSIONS
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        previewView.surfaceTextureListener = surfaceListener
        if (previewView.isAvailable) {
            previewView.surfaceTexture?.let { helper?.addSurface(it, false) }
        }
        scanForDevice()
    }

    override fun onStop() {
        super.onStop()
        if (recording) {
            helper?.stopRecording()
        }
    }

    override fun onDestroy() {
        stopRecordTimer()
        previewView.surfaceTextureListener = null
        helper?.release()
        helper = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            refreshAudioConfig()
            if (!hasCameraPermission()) {
                toast("没有相机权限，部分手机（Android 9+）无法打开 USB 摄像头")
            }
            selecting = false
            scanForDevice()
        }
    }

    // =====================================================================
    // UI
    // =====================================================================

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(26).toFloat()
    }

    private fun card(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
    }

    private fun makeButton(label: String, primary: Boolean): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        textSize = if (primary) 16f else 12.5f
        setTextColor(Color.WHITE)
        background = if (primary) pill(COLOR_BLUE) else pill(COLOR_DARK)
        minimumWidth = 0
        minimumHeight = 0
        setPadding(dp(10), dp(if (primary) 14 else 9), dp(10), dp(if (primary) 14 else 9))
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        previewView = AspectRatioTextureView(this)
        previewView.setAspectRatio(4.0 / 3.0)
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        tipText = TextView(this).apply {
            text = "请插入 USB 摄像头\n连接后会自动开始预览"
            setTextColor(0xFFB0B0B0.toInt())
            gravity = Gravity.CENTER
            textSize = 16f
            setLineSpacing(0f, 1.4f)
        }
        root.addView(
            tipText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        statusText = TextView(this).apply {
            text = "未连接"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = pill(0x99000000.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(20)
                leftMargin = dp(16)
            }
        )

        recordText = TextView(this).apply {
            text = "● 00:00:00"
            setTextColor(0xFFFF5252.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = pill(0x99000000.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        root.addView(
            recordText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(20)
            }
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(0xCC141414.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val rowMain = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnCapture = makeButton("拍照", true)
        btnRecord = makeButton("录像", true)
        rowMain.addView(btnCapture, rowParams(16))
        rowMain.addView(btnRecord, rowParams(16))
        panel.addView(
            rowMain,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val rowSub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnFormat = makeButton("分辨率", false)
        btnRotate = makeButton("旋转", false)
        btnMirror = makeButton("镜像", false)
        btnSwitch = makeButton("切换", false)
        rowSub.addView(btnFormat, rowParams(8))
        rowSub.addView(btnRotate, rowParams(8))
        rowSub.addView(btnMirror, rowParams(8))
        rowSub.addView(btnSwitch, rowParams(8))
        panel.addView(
            rowSub,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        )

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(10)
            }
        )

        btnCapture.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnFormat.setOnClickListener { showFormatDialog() }
        btnRotate.setOnClickListener { rotatePreview() }
        btnMirror.setOnClickListener { cycleMirror() }
        btnSwitch.setOnClickListener { showDeviceDialog() }

        setContentView(root)
    }

    private fun rowParams(margin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = margin / 2
            rightMargin = margin / 2
        }

    private fun updateUi() {
        previewView.visibility = if (cameraOpened) View.VISIBLE else View.INVISIBLE
        tipText.visibility = if (cameraOpened) View.GONE else View.VISIBLE

        val actionable = cameraOpened && !recording
        btnCapture.alpha = if (actionable) 1f else 0.45f
        btnFormat.alpha = if (actionable) 1f else 0.45f

        btnRecord.text = if (recording) "停止" else "录像"
        btnRecord.background = pill(if (recording) COLOR_RED else COLOR_BLUE)

        recordText.visibility = if (recording) View.VISIBLE else View.GONE
        if (!recording) recordText.text = "● 00:00:00"

        btnRotate.text = "旋转 $rotation°"
        btnMirror.text = when (mirror) {
            MirrorMode.MIRROR_HORIZONTAL -> "镜像:水平"
            MirrorMode.MIRROR_VERTICAL -> "镜像:垂直"
            else -> "镜像:关"
        }

        updateStatus()
    }

    private fun updateStatus() {
        val size = currentSize
        statusText.text = when {
            !cameraOpened -> "未连接 · 等待 USB 摄像头"
            size != null -> "$deviceLabel  ${size.width}×${size.height}@${size.fps} ${formatName(size.type)}"
            else -> deviceLabel
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // =====================================================================
    // camera helper
    // =====================================================================

    private fun initCameraHelper() {
        if (helper != null) return

        val h = CameraHelper()
        h.setStateCallback(this)
        h.setImageCaptureConfig(h.getImageCaptureConfig().setJpegCompressionQuality(95))
        h.setVideoCaptureConfig(
            h.getVideoCaptureConfig()
                .setVideoFrameRate(30)
                .setIFrameInterval(1)
                .setBitRate(12 * 1024 * 1024)
                .setAudioCaptureEnable(hasAudioPermission())
        )
        helper = h
    }

    private fun refreshAudioConfig() {
        val h = helper ?: return
        h.setVideoCaptureConfig(h.getVideoCaptureConfig().setAudioCaptureEnable(hasAudioPermission()))
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val attached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice ?: return
        device = attached
        selecting = true
        helper?.selectDevice(attached)
    }

    /** USB 服务启动需要一点时间，失败时重试几次。 */
    private fun scanForDevice(attempt: Int = 0) {
        if (cameraOpened || selecting || isFinishing) return
        val h = helper ?: return

        val devices = h.getDeviceList()
        if (!devices.isNullOrEmpty()) {
            val previous = device
            val target = devices.firstOrNull { previous != null && it.deviceId == previous.deviceId }
                ?: devices.first()
            device = target
            selecting = true
            h.selectDevice(target)
            return
        }

        if (attempt < 8) {
            mainHandler.postDelayed({ scanForDevice(attempt + 1) }, 400)
        }
    }

    // =====================================================================
    // ICameraHelper.StateCallback
    // =====================================================================

    override fun onAttach(device: UsbDevice) {
        this.device = device
        if (!cameraOpened) {
            selecting = true
            helper?.selectDevice(device)
        }
    }

    override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
        selecting = false
        helper?.openCamera()
    }

    override fun onCameraOpen(device: UsbDevice) {
        selecting = false
        cameraOpened = true
        deviceLabel = device.productName ?: "USB 摄像头"

        val h = helper
        h?.startPreview()
        val size = h?.getPreviewSize()
        if (size != null) {
            currentSize = size
            previewView.setAspectRatio(size.width, size.height)
        }
        if (previewView.isAvailable) {
            previewView.surfaceTexture?.let { h?.addSurface(it, false) }
        }

        updateUi()
    }

    override fun onCameraClose(device: UsbDevice) {
        cameraOpened = false
        recording = false
        selecting = false
        stopRecordTimer()
        previewView.surfaceTexture?.let { helper?.removeSurface(it) }
        updateUi()
    }

    override fun onDeviceClose(device: UsbDevice) {
    }

    override fun onDetach(device: UsbDevice) {
        if (device.deviceId == this.device?.deviceId) {
            this.device = null
        }
        cameraOpened = false
        recording = false
        selecting = false
        stopRecordTimer()
        currentSize = null
        updateUi()
    }

    override fun onCancel(device: UsbDevice) {
        selecting = false
        if (!cameraOpened) {
            toast("未授权使用该 USB 设备")
        }
    }

    override fun onError(device: UsbDevice, e: CameraException) {
        selecting = false
        cameraOpened = false
        stopRecordTimer()
        updateUi()
        toast("摄像头打开失败：${e.message ?: "请拔插一次再试"}")
    }

    // =====================================================================
    // capture
    // =====================================================================

    private fun takePhoto() {
        val h = helper ?: return
        if (!cameraOpened) {
            toast("请先连接 USB 摄像头")
            return
        }
        if (recording) {
            toast("录像过程中不能拍照")
            return
        }

        val file = newMediaFile("IMG", "jpg", Environment.DIRECTORY_PICTURES)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        h.takePicture(options, object : ImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    val exported = MediaStoreExport.export(this@MainActivity, file, false)
                    toast(
                        if (exported != null) "照片已保存到相册：Pictures/USBCam/${file.name}"
                        else "照片已保存：${file.absolutePath}"
                    )
                }
            }

            override fun onError(imageCaptureError: Int, message: String, cause: Throwable?) {
                runOnUiThread { toast("拍照失败：$message") }
            }
        })
    }

    private fun toggleRecording() {
        val h = helper ?: return

        if (!cameraOpened) {
            toast("请先连接 USB 摄像头")
            return
        }

        if (recording || h.isRecording()) {
            h.stopRecording()
            return
        }

        val file = newMediaFile("VID", "mp4", Environment.DIRECTORY_MOVIES)
        val options = VideoCapture.OutputFileOptions.Builder(file).build()

        h.startRecording(options, object : VideoCapture.OnVideoCaptureCallback {
            override fun onStart() {
                runOnUiThread {
                    recording = true
                    recordStartedAt = SystemClock.elapsedRealtime()
                    startRecordTimer()
                    updateUi()
                }
            }

            override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                runOnUiThread {
                    recording = false
                    stopRecordTimer()
                    updateUi()
                    val exported = MediaStoreExport.export(this@MainActivity, file, true)
                    toast(
                        if (exported != null) "录像已保存到相册：Movies/USBCam/${file.name}"
                        else "录像已保存：${file.absolutePath}"
                    )
                }
            }

            override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                runOnUiThread {
                    recording = false
                    stopRecordTimer()
                    updateUi()
                    toast("录像失败：$message")
                }
            }
        })
    }

    private fun startRecordTimer() {
        mainHandler.removeCallbacks(timerTick)
        mainHandler.post(timerTick)
    }

    private fun stopRecordTimer() {
        mainHandler.removeCallbacks(timerTick)
    }

    private fun newMediaFile(prefix: String, suffix: String, publicDir: String): File {
        val base = getExternalFilesDir(publicDir) ?: filesDir
        val dir = File(base, "USBCam")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${prefix}_$stamp.$suffix")
    }

    // =====================================================================
    // format / rotate / mirror / device
    // =====================================================================

    private fun showFormatDialog() {
        val h = helper ?: return
        if (!cameraOpened) {
            toast("请先连接 USB 摄像头")
            return
        }
        if (recording) {
            toast("请先停止录像再切换分辨率")
            return
        }

        val formats = h.getSupportedFormatList()
        if (formats.isNullOrEmpty()) {
            toast("读取不到摄像头的分辨率列表")
            return
        }

        val entries = ArrayList<Size>()
        val seen = HashSet<String>()
        for (format in formats) {
            val descriptors = format.frameDescriptors ?: continue
            for (descriptor in descriptors) {
                val fpsList = descriptor.intervals?.map { it.fps }?.distinct() ?: emptyList()
                val candidates = if (fpsList.isEmpty()) listOf(descriptor.fps) else fpsList
                for (fps in candidates) {
                    if (fps <= 0) continue
                    val key = "${descriptor.type}-${descriptor.width}x${descriptor.height}@$fps"
                    if (!seen.add(key)) continue
                    entries.add(
                        Size(
                            descriptor.type,
                            descriptor.width,
                            descriptor.height,
                            fps,
                            ArrayList(candidates)
                        )
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            toast("这台摄像头没有上报可用的分辨率")
            return
        }

        entries.sortWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.fps })

        val labels = entries.map {
            "${formatName(it.type)}  ${it.width}×${it.height}  @${it.fps}fps"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择分辨率 / 格式")
            .setItems(labels) { _, which -> applyFormat(entries[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyFormat(size: Size) {
        val h = helper ?: return
        h.stopPreview()
        h.setPreviewSize(size)
        h.startPreview()
        currentSize = size
        previewView.setAspectRatio(size.width, size.height)
        updateStatus()
        toast("已切换到 ${size.width}×${size.height}@${size.fps}fps")
    }

    private fun formatName(type: Int): String = when (type) {
        UVCCamera.UVC_VS_FRAME_MJPEG -> "MJPEG"
        UVCCamera.UVC_VS_FRAME_UNCOMPRESSED -> "YUYV"
        else -> "TYPE$type"
    }

    private fun rotatePreview() {
        val h = helper ?: return
        rotation = (rotation + 90) % 360
        h.setPreviewConfig(h.getPreviewConfig().setRotation(rotation))
        updateUi()
    }

    private fun cycleMirror() {
        val h = helper ?: return
        mirror = when (mirror) {
            MirrorMode.MIRROR_HORIZONTAL -> MirrorMode.MIRROR_VERTICAL
            MirrorMode.MIRROR_VERTICAL -> MirrorMode.MIRROR_NORMAL
            else -> MirrorMode.MIRROR_HORIZONTAL
        }
        h.setPreviewConfig(h.getPreviewConfig().setMirror(mirror))
        updateUi()
    }

    private fun showDeviceDialog() {
        val h = helper ?: return
        val devices = h.getDeviceList()
        if (devices.isNullOrEmpty()) {
            toast("没有检测到 USB 摄像头")
            return
        }

        val labels = devices.map { dev ->
            dev.productName ?: "USB 设备 ${dev.vendorId.toString(16)}:${dev.productId.toString(16)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择摄像头")
            .setItems(labels) { _, which ->
                val target = devices[which]
                device = target
                if (cameraOpened) {
                    h.closeCamera()
                    cameraOpened = false
                    updateUi()
                }
                selecting = true
                h.selectDevice(target)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
