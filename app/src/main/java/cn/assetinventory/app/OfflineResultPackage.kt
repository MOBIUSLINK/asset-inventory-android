package cn.assetinventory.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class OfflineResultPackage(val version:Int,val packageId:String,val companyCode:String,val taskId:String,val taskName:String,val deviceName:String,val exportedAt:Long,val areaCodes:List<String>,val scans:List<TransferScan>)
data class ReadResultPackage(val data:OfflineResultPackage,val extractedPhotos:Map<String,List<String>>)

object ResultPackageCodec {
    private const val VERSION=3
    fun create(context:Context,database:InventoryDatabase,task:InventoryTask):File{
        val now=System.currentTimeMillis();val packageId=java.util.UUID.randomUUID().toString();val scans=database.transferScans(task.id)
        val company=database.company(task.companyId);val areaCodes=database.taskAreas(task.id).map{it.code};val json=JSONObject().apply{put("format","ASSET_INVENTORY_RESULT");put("version",VERSION);put("packageId",packageId);put("companyCode",company.code);put("taskId",task.id);put("taskName",task.name);put("deviceName",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);put("exportedAt",now);put("areaCodes",JSONArray(areaCodes));put("scans",JSONArray(scans.map(::scanJson)))}
        val payload=json.toString().toByteArray(Charsets.UTF_8);val dir=File(context.filesDir,"task_packages").apply{mkdirs()};val safe=(company.code+"_"+task.name).replace(Regex("[^A-Za-z0-9一-龥_-]"),"_");val file=File(dir,"${safe}_${now}.${TaskPackageCodec.RESULT_EXTENSION}")
        val photos=database.scanPhotosByUuid(task.id)
        ZipOutputStream(file.outputStream()).use{zip->zip.putNextEntry(ZipEntry("result.json"));zip.write(payload);zip.closeEntry();photos.forEach{(uuid,list)->list.forEachIndexed{i,p->val source=File(p.path);if(source.exists()){zip.putNextEntry(ZipEntry("photos/$uuid/${i}.jpg"));source.inputStream().use{it.copyTo(zip)};zip.closeEntry()}}};zip.putNextEntry(ZipEntry("sha256.txt"));zip.write(hash(payload).toByteArray());zip.closeEntry()}
        return file
    }
    fun read(context:Context,uri:Uri):ReadResultPackage{
        var payload:ByteArray?=null;var checksum:String?=null;val photoBytes=mutableMapOf<String,MutableList<ByteArray>>()
        context.contentResolver.openInputStream(uri).use{input->requireNotNull(input);ZipInputStream(input).use{zip->while(true){val e=zip.nextEntry?:break;when{e.name=="result.json"->payload=zip.readBytes();e.name=="sha256.txt"->checksum=zip.readBytes().toString(Charsets.UTF_8).trim();e.name.startsWith("photos/")->photoBytes.getOrPut(e.name.split('/').getOrElse(1){""}){mutableListOf()}.add(zip.readBytes())};zip.closeEntry()}}}
        val bytes=requireNotNull(payload){"结果包缺少数据"};require(hash(bytes)==checksum){"结果包校验失败，文件可能不完整"};val j=JSONObject(bytes.toString(Charsets.UTF_8));require(j.optString("format")=="ASSET_INVENTORY_RESULT"){"不是有效的盘点结果包"};val version=j.getInt("version");require(version in 1..VERSION){"暂不支持此结果包版本"}
        val arr=j.getJSONArray("scans");val scans=List(arr.length()){i->parseScan(arr.getJSONObject(i))};val areaCodes=if(j.has("areaCodes")){val a=j.getJSONArray("areaCodes");List(a.length()){a.getString(it)}}else scans.map{it.areaCode}.distinct();val pkg=OfflineResultPackage(version,j.getString("packageId"),j.optString("companyCode"),j.getString("taskId"),j.getString("taskName"),j.getString("deviceName"),j.getLong("exportedAt"),areaCodes,scans)
        val dir=File(context.filesDir,"anomaly_photos/imported/${pkg.packageId}").apply{mkdirs()};val paths=photoBytes.mapValues{(uuid,list)->list.mapIndexed{i,data->File(dir,"${uuid}_$i.jpg").apply{writeBytes(data)}.absolutePath}}
        return ReadResultPackage(pkg,paths)
    }
    private fun scanJson(s:TransferScan)=JSONObject().apply{put("uuid",s.uuid);put("taskId",s.taskId);put("areaCode",s.areaCode);put("assetCode",s.assetCode);put("rawValue",s.rawValue);put("operator",s.operator);put("scannedAt",s.scannedAt);put("duplicate",s.duplicate);put("outside",s.outside);put("revokedAt",s.revokedAt);put("revokeOperator",s.revokeOperator);put("revokeReason",s.revokeReason);put("anomalyType",s.anomalyType);put("anomalyNote",s.anomalyNote);put("anomalyOperator",s.anomalyOperator);put("anomalyAt",s.anomalyAt)}
    private fun parseScan(j:JSONObject)=TransferScan(j.getString("uuid"),j.getString("taskId"),j.getString("areaCode"),j.getString("assetCode"),j.optString("rawValue"),j.optString("operator"),j.getLong("scannedAt"),j.optBoolean("duplicate"),j.optBoolean("outside"),j.optLongOrNull("revokedAt"),j.optString("revokeOperator"),j.optString("revokeReason"),j.optString("anomalyType"),j.optString("anomalyNote"),j.optString("anomalyOperator"),j.optLongOrNull("anomalyAt"))
    private fun JSONObject.optLongOrNull(key:String)=if(isNull(key)||!has(key))null else getLong(key)
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
}
