package cn.assetinventory.app

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class PdfPrintAdapter(private val file:File):PrintDocumentAdapter(){
    override fun onLayout(oldAttributes:PrintAttributes?,newAttributes:PrintAttributes?,cancellationSignal:CancellationSignal?,callback:LayoutResultCallback,extras:Bundle?){callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(),true)}
    override fun onWrite(pages:Array<out PageRange>?,destination:ParcelFileDescriptor,cancellationSignal:CancellationSignal?,callback:WriteResultCallback){runCatching{FileInputStream(file).use{input->FileOutputStream(destination.fileDescriptor).use{input.copyTo(it)}}}.onSuccess{callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))}.onFailure{callback.onWriteFailed(it.message)}}
}
