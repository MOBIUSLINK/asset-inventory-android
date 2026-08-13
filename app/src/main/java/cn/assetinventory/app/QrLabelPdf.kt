package cn.assetinventory.app

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

data class QrLabel(val payload:String,val code:String,val name:String,val type:String)

object QrLabelPdf {
    fun create(context:Context,labels:List<QrLabel>):File{
        require(labels.isNotEmpty()){"请至少选择一个二维码"}
        val pageW=595;val pageH=842;val margin=30;val cols=3;val rows=5;val gap=8;val cellW=(pageW-margin*2-gap*(cols-1))/cols;val cellH=(pageH-margin*2-gap*(rows-1))/rows
        val doc=PdfDocument();val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textAlign=Paint.Align.CENTER};val border=Paint().apply{style=Paint.Style.STROKE;color=Color.LTGRAY;strokeWidth=1f}
        labels.chunked(cols*rows).forEachIndexed{pageIndex,pageLabels->val page=doc.startPage(PdfDocument.PageInfo.Builder(pageW,pageH,pageIndex+1).create());val canvas=page.canvas
            pageLabels.forEachIndexed{i,label->val col=i%cols;val row=i/cols;val left=margin+col*(cellW+gap);val top=margin+row*(cellH+gap);canvas.drawRect(left.toFloat(),top.toFloat(),(left+cellW).toFloat(),(top+cellH).toFloat(),border)
                val qrSize=minOf(cellW-24,cellH-48);val qr=QrGenerator.bitmapForPayload(label.payload,qrSize);val qLeft=left+(cellW-qrSize)/2;canvas.drawBitmap(qr,null,Rect(qLeft,top+8,qLeft+qrSize,top+8+qrSize),null)
                text.textSize=11f;text.isFakeBoldText=true;canvas.drawText(label.code,(left+cellW/2).toFloat(),(top+qrSize+25).toFloat(),text);text.textSize=8f;text.isFakeBoldText=false;val display=label.name.take(18);canvas.drawText("${label.type} ${display}",(left+cellW/2).toFloat(),(top+qrSize+39).toFloat(),text)
            };doc.finishPage(page)}
        val dir=File(context.filesDir,"qr_exports").apply{mkdirs()};val file=File(dir,"QR_labels_${System.currentTimeMillis()}.pdf");FileOutputStream(file).use{doc.writeTo(it)};doc.close();return file
    }
}
