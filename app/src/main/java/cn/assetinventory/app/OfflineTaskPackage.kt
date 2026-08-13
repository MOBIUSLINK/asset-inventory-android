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

data class OfflineTaskPackage(
    val version: Int, val createdAt: Long, val company: Company, val task: InventoryTask,
    val areas: List<Area>, val assets: List<ImportedAsset>
)

object TaskPackageCodec {
    private const val VERSION = 1
    const val TASK_EXTENSION = "invtask"
    const val RESULT_EXTENSION = "invresult"

    fun create(context: Context, database: InventoryDatabase, task: InventoryTask, areas: List<Area>): File {
        require(areas.isNotEmpty()) { "请至少选择一个区域" }
        val pkg = OfflineTaskPackage(VERSION, System.currentTimeMillis(), database.company(task.companyId), task, areas, database.ledgerAssets(task.companyId))
        val payload = encode(pkg).toString().toByteArray(Charsets.UTF_8)
        val dir = File(context.filesDir, "task_packages").apply { mkdirs() }
        val safeName = (pkg.company.code+"_"+task.name).replace(Regex("[^A-Za-z0-9一-龥_-]"), "_")
        val file = File(dir, "${safeName}_${System.currentTimeMillis()}.$TASK_EXTENSION")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("task.json")); zip.write(payload); zip.closeEntry()
            zip.putNextEntry(ZipEntry("sha256.txt")); zip.write(sha256(payload).toByteArray()); zip.closeEntry()
        }
        return file
    }

    fun read(context: Context, uri: Uri): OfflineTaskPackage {
        var payload: ByteArray? = null; var checksum: String? = null
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开任务包" }
            ZipInputStream(input).use { zip -> while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) { "task.json" -> payload = zip.readBytes(); "sha256.txt" -> checksum = zip.readBytes().toString(Charsets.UTF_8).trim() }
                zip.closeEntry()
            } }
        }
        val bytes = requireNotNull(payload) { "任务包缺少任务数据" }
        require(checksum == sha256(bytes)) { "任务包校验失败，文件可能不完整" }
        return decode(JSONObject(bytes.toString(Charsets.UTF_8))).also { require(it.version == VERSION) { "暂不支持此任务包版本" } }
    }

    private fun encode(p: OfflineTaskPackage) = JSONObject().apply {
        put("format","ASSET_INVENTORY_TASK"); put("version",p.version); put("createdAt",p.createdAt)
        put("company",JSONObject().put("id",p.company.id).put("code",p.company.code).put("name",p.company.name))
        put("task",JSONObject().put("id",p.task.id).put("companyId",p.task.companyId).put("name",p.task.name))
        put("areas",JSONArray(p.areas.map { JSONObject().put("id",it.id).put("companyId",it.companyId).put("code",it.code).put("name",it.name) }))
        put("assets",JSONArray(p.assets.map { a -> JSONObject().apply {
            put("code",a.code);put("name",a.name);put("warehouse",a.warehouse);put("category",a.category);put("status",a.status);put("user",a.user);put("department",a.department);put("source",a.source);put("serialNumber",a.serialNumber);put("assetType",a.assetType);put("amount",a.amount);put("specification",a.specification);put("location",a.location);put("purchaseDate",a.purchaseDate);put("supplier",a.supplier);put("note",a.note)
        } }))
    }

    private fun decode(j: JSONObject): OfflineTaskPackage {
        require(j.optString("format") == "ASSET_INVENTORY_TASK") { "不是有效的资产盘点任务包" }
        val cj=j.getJSONObject("company"); val tj=j.getJSONObject("task")
        val company=Company(cj.getString("id"),cj.getString("code"),cj.getString("name"))
        val task=InventoryTask(tj.getString("id"),tj.getString("companyId"),tj.getString("name"),"进行中")
        val areas=j.getJSONArray("areas").let { arr -> List(arr.length()){i->arr.getJSONObject(i).let{Area(it.getString("id"),it.getString("companyId"),it.getString("code"),it.getString("name"))}} }
        val assets=j.getJSONArray("assets").let { arr -> List(arr.length()){i->arr.getJSONObject(i).let{a->ImportedAsset(a.s("code"),a.s("name"),a.s("warehouse"),a.s("category"),a.s("status"),a.s("user"),a.s("department"),a.s("source"),a.s("serialNumber"),a.s("assetType"),a.s("amount"),a.s("specification"),a.s("location"),a.s("purchaseDate"),a.s("supplier"),a.s("note"))}} }
        return OfflineTaskPackage(j.getInt("version"),j.getLong("createdAt"),company,task,areas,assets)
    }
    private fun JSONObject.s(name:String)=optString(name,"")
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
