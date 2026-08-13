package cn.assetinventory.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream

object QrGenerator {
    fun bitmapForPayload(content:String,size:Int=900,margin:Int=2):Bitmap{
        val matrix=MultiFormatWriter().encode(content,BarcodeFormat.QR_CODE,size,size,mapOf(EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,EncodeHintType.MARGIN to margin))
        val pixels=IntArray(size*size);for(y in 0 until size)for(x in 0 until size)pixels[y*size+x]=if(matrix[x,y])Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).apply{setPixels(pixels,0,size,0,0,size,size)}
    }
    fun bitmap(assetCode:String,size:Int=900)=bitmapForPayload("ASSET:${assetCode.uppercase()}",size)
    fun save(context:Context,assetCode:String):File{val dir=File(context.filesDir,"generated_qr").apply{mkdirs()};val file=File(dir,"asset_${assetCode.uppercase()}_QR.png");FileOutputStream(file).use{bitmap(assetCode).compress(Bitmap.CompressFormat.PNG,100,it)};return file}
}
