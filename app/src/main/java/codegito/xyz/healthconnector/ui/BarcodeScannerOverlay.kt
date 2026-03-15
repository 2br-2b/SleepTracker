package codegito.xyz.healthconnector.ui

import android.Manifest
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BarcodeScannerOverlay"

/**
 * Fullscreen camera overlay that scans barcodes using CameraX + ZXing.
 *
 * When [frozen] is true the camera preview stays visible (freeze frame) but
 * image analysis stops — allowing the food-entry bottom sheet to be shown on
 * top without the scanner continuing to fire callbacks.
 *
 * @param onBarcodeDetected Called once when a barcode is successfully decoded.
 * @param onClose           Called when the user taps the close (×) button.
 * @param frozen            When true, analysis is paused (freeze-frame effect).
 */
@Composable
fun BarcodeScannerOverlay(
    onBarcodeDetected: (String) -> Unit,
    onClose: () -> Unit,
    frozen: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionDenied = true
    }

    // Check/request camera permission on first composition
    LaunchedEffect(Unit) {
        val status = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (status == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreviewWithAnalysis(
                frozen = frozen,
                onBarcodeDetected = onBarcodeDetected,
            )
            ScannerViewfinderOverlay()
        } else if (permissionDenied) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Camera permission is required to scan barcodes.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Please grant it in app settings.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        }

        // Close button — always visible
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close scanner",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun CameraPreviewWithAnalysis(
    frozen: Boolean,
    onBarcodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Single-use flag: fire onBarcodeDetected only once per "thaw" cycle
    val detectionFired = remember { AtomicBoolean(false) }

    // Reset the flag whenever frozen transitions false → true (new scan cycle starts
    // when we unfreeze, so reset when freezing so next thaw allows a new detection)
    LaunchedEffect(frozen) {
        if (!frozen) detectionFired.set(false)
    }

    // Dedicated single-thread executor for image analysis
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // ZXing reader — reused across frames; reset() clears state between calls
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(
                        BarcodeFormat.EAN_13,
                        BarcodeFormat.EAN_8,
                        BarcodeFormat.UPC_A,
                        BarcodeFormat.UPC_E,
                        BarcodeFormat.CODE_128,
                        BarcodeFormat.CODE_39,
                        BarcodeFormat.QR_CODE,
                    ),
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }
    }

    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            if (!frozen && !detectionFired.get()) {
                decodeBarcode(imageProxy, reader) { barcode ->
                    if (detectionFired.compareAndSet(false, true)) {
                        onBarcodeDetected(barcode)
                    }
                }
            }
            imageProxy.close()
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Attempts to decode a barcode from [imageProxy] using [reader].
 * Calls [onDecoded] with the raw barcode string if successful.
 */
private fun decodeBarcode(
    imageProxy: ImageProxy,
    reader: MultiFormatReader,
    onDecoded: (String) -> Unit,
) {
    runCatching {
        val bitmap = imageProxy.toBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        reader.reset()
        val result = reader.decodeWithState(binaryBitmap)
        onDecoded(result.text)
    }.onFailure { e ->
        // NotFoundException is the normal "no barcode found" case — not an error
        if (e !is NotFoundException) {
            Log.w(TAG, "Barcode decode error", e)
        }
    }
}

/**
 * Semi-transparent scrim + white rounded-rect viewfinder drawn over the camera preview.
 */
@Composable
private fun ScannerViewfinderOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val viewfinderWidth = size.width * 0.75f
        val viewfinderHeight = viewfinderWidth * 0.5f
        val left = (size.width - viewfinderWidth) / 2f
        val top = (size.height - viewfinderHeight) / 2f

        // Dark scrim — fill entire canvas
        drawRect(color = Color.Black.copy(alpha = 0.5f))

        // Clear the viewfinder rectangle (punch a hole through the scrim)
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(viewfinderWidth, viewfinderHeight),
            cornerRadius = CornerRadius(16.dp.toPx()),
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
        )

        // White border around the viewfinder
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(viewfinderWidth, viewfinderHeight),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
