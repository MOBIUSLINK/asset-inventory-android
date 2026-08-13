package cn.assetinventory.app

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupCompany(val id:String,val code:String,val name:String)
data class BackupTask(val name:String,val companyName:String,val status:String)
data class BackupPreview(val file:File,val createdAt:Long,val companies:Int,val tasks:Int,val assets:Int,val scans:Int,val photos:Int,val companyDetails:List<BackupCompany>,val taskDetails:List<BackupTask>)
object InventoryBackup{
    private const val VERSION=1
    fun create(context:Context,db:InventoryDatabase):File{
        db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)",null).use{while(it.moveToNext()) {}}
        val database=context.getDatabasePath("asset_inventory.db");val photos=File(context.filesDir,"anomaly_photos").walkTopDown().filter{it.isFile}.toList();val meta=JSONObject().apply{put("format","ASSET_INVENTORY_BACKUP");put("version",VERSION);put("createdAt",System.currentTimeMillis());put("companies",db.companies().size);put("tasks",db.tasks().size);put("assets",db.ledgerAssetCountAll());put("scans",db.tasks().sumOf{db.transferScans(it.id).size});put("photos",photos.size)}.toString().toByteArray();val dir=File(context.filesDir,"task_packages").apply{mkdirs()};val out=File(dir,"asset_inventory_${System.currentTimeMillis()}.invbackup")
        ZipOutputStream(out.outputStream()).use{z->fun add(name:String,file:File){z.putNextEntry(ZipEntry(name));file.inputStream().use{it.copyTo(z)};z.closeEntry()};z.putNextEntry(ZipEntry("metadata.json"));z.write(meta);z.closeEntry();add("database.db",database);photos.forEach{add("photos/${it.relativeTo(File(context.filesDir,"anomaly_photos")).invariantSeparatorsPath}",it)};z.putNextEntry(ZipEntry("sha256.txt"));z.write(hash(database.readBytes()).toByteArray());z.closeEntry()};return out
    }
    fun files(context:Context):List<File> = File(context.filesDir,"task_packages").apply{mkdirs()}.listFiles()
        ?.filter{it.isFile&&it.extension.equals("invbackup",true)}?.sortedByDescending{it.lastModified()} ?: emptyList()
    fun inspect(context:Context,uri:Uri):BackupPreview{val dir=File(context.cacheDir,"backup_preview").apply{deleteRecursively();mkdirs()};var meta:JSONObject?=null;var dbBytes:ByteArray?=null;var checksum="";var photoCount=0;context.contentResolver.openInputStream(uri).use{input->requireNotNull(input);ZipInputStream(input).use{z->while(true){val e=z.nextEntry?:break;when{e.name=="metadata.json"->meta=JSONObject(z.readBytes().toString(Charsets.UTF_8));e.name=="database.db"->dbBytes=z.readBytes();e.name=="sha256.txt"->checksum=z.readBytes().toString(Charsets.UTF_8).trim();e.name.startsWith("photos/")-> {File(dir,e.name).apply{parentFile?.mkdirs();writeBytes(z.readBytes())};photoCount++}};z.closeEntry()}}};val m=requireNotNull(meta){"备份缺少说明信息"};require(m.optString("format")=="ASSET_INVENTORY_BACKUP"&&m.optInt("version")==VERSION){"不是受支持的备份文件"};val bytes=requireNotNull(dbBytes){"备份缺少数据库"};require(hash(bytes)==checksum){"备份校验失败，文件可能损坏"};val dbFile=File(dir,"database.db").apply{writeBytes(bytes)};val backupDb=SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READONLY);val companyDetails=backupDb.rawQuery("SELECT id,code,name FROM company ORDER BY created_at",null).use{c->buildList{while(c.moveToNext())add(BackupCompany(c.getString(0),c.getString(1),c.getString(2)))}};val taskDetails=backupDb.rawQuery("SELECT t.name,c.name,t.status FROM inventory_task t JOIN company c ON c.id=t.company_id ORDER BY t.created_at DESC",null).use{c->buildList{while(c.moveToNext())add(BackupTask(c.getString(0),c.getString(1),c.getString(2)))}};backupDb.close();return BackupPreview(dbFile,m.getLong("createdAt"),m.getInt("companies"),m.getInt("tasks"),m.getInt("assets"),m.getInt("scans"),photoCount,companyDetails,taskDetails)}
    fun scheduleRestore(context:Context,preview:BackupPreview){val pending=File(context.filesDir,"pending_restore").apply{deleteRecursively();mkdirs()};preview.file.copyTo(File(pending,"database.db"),true);File(preview.file.parentFile,"photos").takeIf{it.exists()}?.copyRecursively(File(pending,"photos"),true);context.getSharedPreferences("inventory_prefs",Context.MODE_PRIVATE).edit().putBoolean("pending_restore",true).apply()}
    fun applyPendingRestore(context:Context){val prefs=context.getSharedPreferences("inventory_prefs",Context.MODE_PRIVATE);if(!prefs.getBoolean("pending_restore",false))return;val pending=File(context.filesDir,"pending_restore");val src=File(pending,"database.db");if(src.exists()){val target=context.getDatabasePath("asset_inventory.db");target.parentFile?.mkdirs();File(target.path+"-wal").delete();File(target.path+"-shm").delete();src.copyTo(target,true);val photos=File(pending,"photos");if(photos.exists()){File(context.filesDir,"anomaly_photos").deleteRecursively();photos.copyRecursively(File(context.filesDir,"anomaly_photos"),true)}};pending.deleteRecursively();prefs.edit().remove("pending_restore").apply()}
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
}
