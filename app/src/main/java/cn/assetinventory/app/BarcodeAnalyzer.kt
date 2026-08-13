package cn.assetinventory.app

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onValue: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) : ImageAnalysis.Analyzer {
    private val busy = AtomicBoolean(false)

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                codes.firstOrNull()?.rawValue?.let(onValue)
            }
            .addOnFailureListener { error -> onError(error.message ?: "二维码识别组件异常") }
            .addOnCompleteListener {
                busy.set(false)
                imageProxy.close()
            }
    }
}
