package com.minifile.explorer.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.minifile.explorer.core.qr.QrDecoder
import com.minifile.explorer.core.qr.QrPayloads
import kotlinx.coroutines.delay
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MANUAL_HINT_DELAY_MS = 8_000L

/**
 * Offline QR scanner sheet (camera2 + [QrDecoder], no external dependencies).
 *
 * [onResult] receives either the raw decoded QR text, or a string built from manual entry:
 * a `WIFI:` payload (via [QrPayloads.buildWifi]) or an `http://host[:port]/` URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerSheet(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
    title: String = "Scan QR to Join",
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val latestOnResult by rememberUpdatedState(onResult)
    val delivered = remember { AtomicBoolean(false) }

    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraMessage by remember {
        mutableStateOf(if (hasCamera) null else "No camera available on this device.")
    }
    var manualMode by remember { mutableStateOf(!hasCamera) }
    var showManualButton by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            cameraMessage = "Camera permission denied. Enter the details manually."
            manualMode = true
        }
    }

    LaunchedEffect(Unit) {
        if (hasCamera && !permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val cameraActive = hasCamera && permissionGranted && !manualMode && cameraMessage == null
    LaunchedEffect(cameraActive) {
        showManualButton = false
        if (cameraActive) {
            delay(MANUAL_HINT_DELAY_MS)
            showManualButton = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (manualMode) {
                    "Enter the hotspot or the receiver's address shown on the other device"
                } else {
                    "Point the camera at the QR code shown on the other device"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (cameraActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCameraPreview(
                        torchOn = torchOn,
                        onTorchAvailable = { torchAvailable = it },
                        onDecoded = { text ->
                            if (delivered.compareAndSet(false, true)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnResult(text)
                            }
                        },
                        onError = { message ->
                            cameraMessage = message
                            manualMode = true
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.68f)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(20.dp),
                            ),
                    )
                    if (torchAvailable) {
                        FilledTonalIconButton(
                            onClick = { torchOn = !torchOn },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp),
                        ) {
                            Icon(
                                imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription = if (torchOn) "Turn off flashlight" else "Turn on flashlight",
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (showManualButton) {
                    Text(
                        text = "Having trouble scanning?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { manualMode = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Enter manually")
                    }
                }
            } else {
                cameraMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                QrManualEntry(onSubmit = { latestOnResult(it) })
                if (hasCamera && cameraMessage == null && permissionGranted) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { manualMode = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Back to camera")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrManualEntry(onSubmit: (String) -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == 0,
                onClick = { mode = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Wi-Fi") }
            SegmentedButton(
                selected = mode == 1,
                onClick = { mode = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Address") }
        }

        if (mode == 0) {
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("Network name (SSID)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (leave empty if open)") },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onSubmit(
                        QrPayloads.buildWifi(
                            ssid,
                            password,
                            if (password.isEmpty()) "nopass" else "wpa2",
                        ),
                    )
                },
                enabled = ssid.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Join")
            }
        } else {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address (host[:port])") },
                placeholder = { Text("192.168.43.1:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSubmit(normalizeManualAddress(address)) },
                enabled = address.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
        }
    }
}

/** `host[:port]` → `http://host[:port]/`; inputs already carrying an http(s) scheme are kept. */
private fun normalizeManualAddress(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        val afterScheme = trimmed.substringAfter("://")
        return if ('/' in afterScheme) trimmed else "$trimmed/"
    }
    return "http://" + trimmed.removePrefix("http://").trimEnd('/') + "/"
}

@Composable
private fun QrCameraPreview(
    torchOn: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    onDecoded: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestOnDecoded by rememberUpdatedState(onDecoded)
    val latestOnError by rememberUpdatedState(onError)
    val latestOnTorchAvailable by rememberUpdatedState(onTorchAvailable)

    val controller = remember {
        QrCameraController(
            context = context.applicationContext,
            onDecoded = { latestOnDecoded(it) },
            onError = { latestOnError(it) },
            onTorchAvailable = { latestOnTorchAvailable(it) },
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    LaunchedEffect(controller, torchOn) {
        controller.setTorch(torchOn)
    }

    AndroidView(
        factory = { ctx -> TextureView(ctx).also { controller.attach(it) } },
        modifier = modifier,
    )
}

/**
 * Minimal camera2 pipeline: back camera → (TextureView preview, YUV ImageReader).
 *
 * Camera callbacks and luminance copies run on one [HandlerThread]; decoding runs on a
 * single-thread executor with at most one frame in flight (every 2nd frame is analysed).
 * All UI-facing callbacks are delivered on the main thread and suppressed after [release].
 */
private class QrCameraController(
    private val context: Context,
    private val onDecoded: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onTorchAvailable: (Boolean) -> Unit,
) : TextureView.SurfaceTextureListener {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("QrCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "QrDecode").apply { isDaemon = true }
    }

    private val closed = AtomicBoolean(false)
    private val decoding = AtomicBoolean(false)
    private val resultDelivered = AtomicBoolean(false)

    private var textureView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null
    private var sensorOrientation = 90
    private var afMode = CaptureRequest.CONTROL_AF_MODE_OFF
    private var flashSupported = false
    private var torchRequested = false
    private var opening = false

    // Touched only on cameraThread.
    private var frameCounter = 0L
    private var lumBuffer = ByteArray(0)

    fun attach(view: TextureView) {
        textureView = view
        view.surfaceTextureListener = this
        if (view.isAvailable) {
            view.surfaceTexture?.let { startCamera(it, view.width, view.height) }
        }
    }

    fun setTorch(on: Boolean) {
        if (closed.get()) return
        cameraHandler.post {
            synchronized(lock) {
                torchRequested = on
                if (!closed.get()) updateRepeatingRequestLocked()
            }
        }
    }

    fun release() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { closeCameraLocked() }
        textureView?.surfaceTextureListener = null
        textureView = null
        cameraThread.quitSafely()
        decodeExecutor.shutdownNow()
    }

    // region TextureView.SurfaceTextureListener

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startCamera(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyTransform(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        synchronized(lock) { closeCameraLocked() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    // endregion

    private fun startCamera(surfaceTexture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        if (closed.get() || viewWidth <= 0 || viewHeight <= 0) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            postError("Camera permission denied. Enter the details manually.")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            postError("Camera unavailable. Enter the details manually.")
            return
        }
        try {
            val cameraId = pickBackCamera(manager)
            if (cameraId == null) {
                postError("No camera available on this device.")
                return
            }
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                postError("Camera unavailable. Enter the details manually.")
                return
            }
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val displayRotation = displayRotationDegrees()
            val preview = choosePreviewSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                viewWidth,
                viewHeight,
                sensor,
                displayRotation,
            )
            val analysis = chooseAnalysisSize(map.getOutputSizes(ImageFormat.YUV_420_888))
            if (preview == null || analysis == null) {
                postError("Camera unavailable. Enter the details manually.")
                return
            }
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

            synchronized(lock) {
                if (closed.get() || opening || cameraDevice != null) return
                sensorOrientation = sensor
                previewSize = preview
                flashSupported = flash
                afMode = when {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes ->
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    else -> CaptureRequest.CONTROL_AF_MODE_OFF
                }
                surfaceTexture.setDefaultBufferSize(preview.width, preview.height)
                previewSurface = Surface(surfaceTexture)
                imageReader = ImageReader.newInstance(
                    analysis.width,
                    analysis.height,
                    ImageFormat.YUV_420_888,
                    2,
                ).also { it.setOnImageAvailableListener(::onImageAvailable, cameraHandler) }
                opening = true
            }
            mainHandler.post { if (!closed.get()) onTorchAvailable(flash) }
            applyTransform(viewWidth, viewHeight)
            openCameraChecked(manager, cameraId)
        } catch (e: CameraAccessException) {
            failAndClose("Camera unavailable. Enter the details manually.")
        } catch (e: SecurityException) {
            failAndClose("Camera permission denied. Enter the details manually.")
        } catch (e: IllegalArgumentException) {
            failAndClose("Camera unavailable. Enter the details manually.")
        } catch (e: IllegalStateException) {
            failAndClose("Camera unavailable. Enter the details manually.")
        }
    }

    /** Only reached after the CAMERA permission check in [startCamera]. */
    @SuppressLint("MissingPermission")
    private fun openCameraChecked(manager: CameraManager, cameraId: String) {
        manager.openCamera(cameraId, deviceCallback, cameraHandler)
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            synchronized(lock) {
                opening = false
                if (closed.get() || previewSurface == null || imageReader == null) {
                    camera.close()
                    return
                }
                cameraDevice = camera
                try {
                    createSessionLocked(camera)
                } catch (e: CameraAccessException) {
                    closeCameraLocked()
                    postError("Camera unavailable. Enter the details manually.")
                } catch (e: IllegalStateException) {
                    closeCameraLocked()
                    postError("Camera unavailable. Enter the details manually.")
                } catch (e: IllegalArgumentException) {
                    closeCameraLocked()
                    postError("Camera unavailable. Enter the details manually.")
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            synchronized(lock) {
                opening = false
                camera.close()
                if (cameraDevice === camera) closeCameraLocked()
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            synchronized(lock) {
                opening = false
                camera.close()
                if (cameraDevice === camera) closeCameraLocked()
            }
            postError(
                if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                    error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                ) {
                    "Camera is in use by another app. Enter the details manually."
                } else {
                    "Camera unavailable. Enter the details manually."
                },
            )
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(captureSession: CameraCaptureSession) {
            synchronized(lock) {
                if (closed.get() || cameraDevice == null) {
                    captureSession.close()
                    return
                }
                session = captureSession
                updateRepeatingRequestLocked()
            }
        }

        override fun onConfigureFailed(captureSession: CameraCaptureSession) {
            synchronized(lock) { closeCameraLocked() }
            postError("Camera unavailable. Enter the details manually.")
        }
    }

    private fun createSessionLocked(camera: CameraDevice) {
        val targets = listOfNotNull(previewSurface, imageReader?.surface)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            createSessionApi28(camera, targets)
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, sessionCallback, cameraHandler)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createSessionApi28(camera: CameraDevice, targets: List<Surface>) {
        val executor = Executor { command -> cameraHandler.post(command) }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            targets.map { OutputConfiguration(it) },
            executor,
            sessionCallback,
        )
        camera.createCaptureSession(config)
    }

    private fun updateRepeatingRequestLocked() {
        val camera = cameraDevice ?: return
        val captureSession = session ?: return
        val preview = previewSurface ?: return
        val reader = imageReader ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(preview)
            builder.addTarget(reader.surface)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (flashSupported && torchRequested) {
                    CaptureRequest.FLASH_MODE_TORCH
                } else {
                    CaptureRequest.FLASH_MODE_OFF
                },
            )
            captureSession.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: CameraAccessException) {
            // Session went away (e.g. camera disconnected); the device callback reports it.
        } catch (e: IllegalStateException) {
            // Session already closed.
        }
    }

    private fun closeCameraLocked() {
        try {
            session?.close()
        } catch (e: IllegalStateException) {
            // Already closed.
        }
        session = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
    }

    private fun failAndClose(message: String) {
        synchronized(lock) {
            opening = false
            closeCameraLocked()
        }
        postError(message)
    }

    private fun postError(message: String) {
        mainHandler.post { if (!closed.get()) onError(message) }
    }

    // region Frame analysis (cameraThread)

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            frameCounter++
            if (closed.get() || resultDelivered.get() || frameCounter % 2L != 0L) return
            if (!decoding.compareAndSet(false, true)) return
            val width = image.width
            val height = image.height
            if (lumBuffer.size != width * height) lumBuffer = ByteArray(width * height)
            val lum = lumBuffer
            val copied = try {
                copyLuminance(image, lum, width, height)
            } catch (e: IllegalStateException) {
                false // Image/reader closed concurrently by release().
            }
            if (!copied) {
                decoding.set(false)
                return
            }
            try {
                decodeExecutor.execute { decodeFrame(lum, width, height) }
            } catch (e: RejectedExecutionException) {
                decoding.set(false)
            }
        } finally {
            image.close()
        }
    }

    private fun decodeFrame(lum: ByteArray, width: Int, height: Int) {
        try {
            if (closed.get() || resultDelivered.get()) return
            val text = try {
                QrDecoder.decode(lum, width, height)
            } catch (e: RuntimeException) {
                null
            }
            if (text != null && resultDelivered.compareAndSet(false, true)) {
                mainHandler.post { if (!closed.get()) onDecoded(text) }
            }
        } finally {
            decoding.set(false)
        }
    }

    // endregion

    private fun applyTransform(viewWidth: Int, viewHeight: Int) {
        val view = textureView ?: return
        val size = synchronized(lock) { previewSize } ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val matrix = computeCenterCropTransform(
            viewWidth.toFloat(),
            viewHeight.toFloat(),
            size,
            sensorOrientation,
            displayRotationDegrees(),
        )
        mainHandler.post { if (!closed.get()) view.setTransform(matrix) }
    }

    private fun displayRotationDegrees(): Int =
        when (textureView?.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    private companion object {
        const val MAX_PREVIEW_LONG = 1920
        const val MAX_PREVIEW_SHORT = 1080
        const val TARGET_ANALYSIS_W = 640
        const val TARGET_ANALYSIS_H = 480

        fun pickBackCamera(manager: CameraManager): String? {
            val ids = manager.cameraIdList
            return ids.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: ids.firstOrNull()
        }

        /** Preview buffer size whose aspect best matches the view (in sensor coordinates). */
        fun choosePreviewSize(
            sizes: Array<Size>?,
            viewWidth: Int,
            viewHeight: Int,
            sensorOrientation: Int,
            displayRotation: Int,
        ): Size? {
            if (sizes.isNullOrEmpty()) return null
            val displaySwapped = displayRotation == 90 || displayRotation == 270
            val naturalW = if (displaySwapped) viewHeight else viewWidth
            val naturalH = if (displaySwapped) viewWidth else viewHeight
            val sensorSwapped = sensorOrientation == 90 || sensorOrientation == 270
            val target = if (sensorSwapped) {
                naturalH.toFloat() / naturalW
            } else {
                naturalW.toFloat() / naturalH
            }
            val bounded = sizes.filter {
                max(it.width, it.height) <= MAX_PREVIEW_LONG && min(it.width, it.height) <= MAX_PREVIEW_SHORT
            }.ifEmpty { sizes.toList() }
            return bounded.minWithOrNull(
                compareBy<Size> { abs(it.width.toFloat() / it.height - target) }
                    .thenByDescending { it.width.toLong() * it.height },
            )
        }

        /** YUV size closest to 640×480 (exact match preferred). */
        fun chooseAnalysisSize(sizes: Array<Size>?): Size? {
            if (sizes.isNullOrEmpty()) return null
            val targetArea = TARGET_ANALYSIS_W.toLong() * TARGET_ANALYSIS_H
            val targetAspect = TARGET_ANALYSIS_W.toFloat() / TARGET_ANALYSIS_H
            return sizes.minWithOrNull(
                compareBy<Size> { abs(it.width.toLong() * it.height - targetArea) }
                    .thenBy { abs(it.width.toFloat() / it.height - targetAspect) },
            )
        }

        /**
         * Copies the Y plane into a tight width×height buffer, honouring rowStride and pixelStride.
         * Returns false if the plane is smaller than expected.
         */
        fun copyLuminance(image: Image, out: ByteArray, width: Int, height: Int): Boolean {
            val plane = image.planes.firstOrNull() ?: return false
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            val limit = buffer.limit()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (rowStride <= 0 || pixelStride <= 0) return false
            val lastIndex = base + (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
            if (lastIndex >= limit) return false
            if (pixelStride == 1) {
                for (row in 0 until height) {
                    buffer.position(base + row * rowStride)
                    buffer.get(out, row * width, width)
                }
            } else {
                for (row in 0 until height) {
                    val rowStart = base + row * rowStride
                    val outRow = row * width
                    for (col in 0 until width) {
                        out[outRow + col] = buffer.get(rowStart + col * pixelStride)
                    }
                }
            }
            return true
        }

        /**
         * Maps the default (stretched) TextureView content to an upright, aspect-correct
         * center-crop. The SurfaceTexture already rotates the buffer by the sensor orientation
         * into the device's natural orientation; we undo the stretch, compensate the display
         * rotation and scale uniformly to fill the view.
         */
        fun computeCenterCropTransform(
            viewWidth: Float,
            viewHeight: Float,
            bufferSize: Size,
            sensorOrientation: Int,
            displayRotation: Int,
        ): Matrix {
            val sensorSwapped = sensorOrientation == 90 || sensorOrientation == 270
            val contentW = (if (sensorSwapped) bufferSize.height else bufferSize.width).toFloat()
            val contentH = (if (sensorSwapped) bufferSize.width else bufferSize.height).toFloat()
            val displaySwapped = displayRotation == 90 || displayRotation == 270
            val rotatedW = if (displaySwapped) contentH else contentW
            val rotatedH = if (displaySwapped) contentW else contentH
            val scale = max(viewWidth / rotatedW, viewHeight / rotatedH)
            return Matrix().apply {
                setScale(contentW / viewWidth, contentH / viewHeight)
                postTranslate(-contentW / 2f, -contentH / 2f)
                postRotate(-displayRotation.toFloat())
                postScale(scale, scale)
                postTranslate(viewWidth / 2f, viewHeight / 2f)
            }
        }
    }
}
