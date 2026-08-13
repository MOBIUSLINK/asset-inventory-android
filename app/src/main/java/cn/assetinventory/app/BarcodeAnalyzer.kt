package cn.assetinventory.app

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeAnalyzer(private val scanner:BarcodeScanner,private val onValue:(String)->Unit,private val onError:(String)->Unit={},private val onLuma:(Double)->Unit={}):ImageAnalysis.Analyzer{
    private val busy=AtomicBoolean(false);private var lastLumaAt=0L
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy:ImageProxy){
        val now=System.currentTimeMillis()
        if(now-lastLumaAt>500){val buffer=imageProxy.planes.firstOrNull()?.buffer;if(buffer!=null){val start=buffer.position();var sum=0L;var samples=0;val step=(buffer.remaining()/400).coerceAtLeast(1);var index=start;while(index<buffer.limit()){sum+=(buffer.get(index).toInt() and 0xff);samples++;index+=step};if(samples>0)onLuma(sum.toDouble()/samples)};lastLumaAt=now}
        val mediaImage=imageProxy.image
        if(mediaImage==null||!busy.compareAndSet(false,true)){imageProxy.close();return}
        scanner.process(InputImage.fromMediaImage(mediaImage,imageProxy.imageInfo.rotationDegrees)).addOnSuccessListener{codes->codes.firstOrNull()?.rawValue?.let(onValue)}.addOnFailureListener{error->onError(error.message?:"二维码识别组件异常")}.addOnCompleteListener{busy.set(false);imageProxy.close()}
    }
}
