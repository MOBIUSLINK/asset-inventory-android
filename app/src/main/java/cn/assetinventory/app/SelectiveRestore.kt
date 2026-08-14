package cn.assetinventory.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

object SelectiveRestore {
    fun restoreTask(context:Context,current:InventoryDatabase,preview:BackupPreview,taskId:String,overwrite:Boolean){
        val source=SQLiteDatabase.openDatabase(preview.file.absolutePath,null,SQLiteDatabase.OPEN_READONLY)
        val companyId=source.rawQuery("SELECT company_id FROM inventory_task WHERE id=?",arrayOf(taskId)).use{require(it.moveToFirst()){"备份中没有该任务"};it.getString(0)}
        require(current.companies().any{it.id==companyId}){"任务所属公司当前不存在，请先恢复公司"}
        val target=current.writableDatabase;target.beginTransaction()
        try{
            val exists=target.rawQuery("SELECT 1 FROM inventory_task WHERE id=?",arrayOf(taskId)).use{it.moveToFirst()}
            require(!exists||overwrite){"当前已有同一任务"}
            if(exists)deleteTaskRows(target,taskId)
            copy(source,target,"area","id IN (SELECT area_id FROM task_area WHERE task_id=?)",arrayOf(taskId),SQLiteDatabase.CONFLICT_IGNORE)
            copy(source,target,"inventory_task","id=?",arrayOf(taskId))
            copy(source,target,"task_area","task_id=?",arrayOf(taskId))
            copy(source,target,"work_session","task_id=?",arrayOf(taskId))
            copyScansAndPhotos(context,source,target,preview,"task_id=?",arrayOf(taskId))
            target.setTransactionSuccessful()
        }finally{target.endTransaction();source.close()}
    }

    fun restoreCompany(context:Context,current:InventoryDatabase,preview:BackupPreview,companyId:String,overwrite:Boolean){
        val source=SQLiteDatabase.openDatabase(preview.file.absolutePath,null,SQLiteDatabase.OPEN_READONLY)
        val companyCode=source.rawQuery("SELECT code FROM company WHERE id=?",arrayOf(companyId)).use{require(it.moveToFirst()){"备份中没有该公司"};it.getString(0)}
        val target=current.writableDatabase
        val conflicting=target.rawQuery("SELECT id FROM company WHERE code=? COLLATE NOCASE AND id<>?",arrayOf(companyCode,companyId)).use{it.moveToFirst()}
        require(!conflicting){"当前已有其他公司使用编号 $companyCode，请先修改公司编号"}
        target.beginTransaction()
        try{
            val exists=target.rawQuery("SELECT 1 FROM company WHERE id=?",arrayOf(companyId)).use{it.moveToFirst()}
            require(!exists||overwrite){"当前已有同一公司"}
            if(exists)deleteCompanyRows(target,companyId)
            copy(source,target,"company","id=?",arrayOf(companyId))
            copy(source,target,"company_code_history","company_id=?",arrayOf(companyId),SQLiteDatabase.CONFLICT_IGNORE)
            copy(source,target,"company_change_audit","company_id=?",arrayOf(companyId),SQLiteDatabase.CONFLICT_IGNORE)
            copy(source,target,"area","company_id=?",arrayOf(companyId))
            copy(source,target,"ledger_import","company_id=?",arrayOf(companyId))
            copy(source,target,"ledger_issue","import_id IN (SELECT id FROM ledger_import WHERE company_id=?)",arrayOf(companyId))
            copy(source,target,"asset_ledger","company_id=?",arrayOf(companyId))
            copy(source,target,"inventory_task","company_id=?",arrayOf(companyId))
            copy(source,target,"task_area","task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(companyId))
            copy(source,target,"work_session","task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(companyId))
            copyScansAndPhotos(context,source,target,preview,"task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(companyId))
            target.setTransactionSuccessful()
        }finally{target.endTransaction();source.close()}
    }

    private fun deleteTaskRows(db:SQLiteDatabase,taskId:String){db.delete("anomaly_photo","scan_id IN (SELECT id FROM scan_event WHERE task_id=?)",arrayOf(taskId));db.delete("scan_event","task_id=?",arrayOf(taskId));db.delete("work_session","task_id=?",arrayOf(taskId));db.delete("task_area","task_id=?",arrayOf(taskId));db.delete("inventory_task","id=?",arrayOf(taskId))}
    private fun deleteCompanyRows(db:SQLiteDatabase,id:String){db.delete("anomaly_photo","scan_id IN (SELECT s.id FROM scan_event s JOIN inventory_task t ON t.id=s.task_id WHERE t.company_id=?)",arrayOf(id));db.delete("scan_event","task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(id));db.delete("work_session","task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(id));db.delete("task_area","task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(id));db.delete("inventory_task","company_id=?",arrayOf(id));db.delete("ledger_issue","import_id IN (SELECT id FROM ledger_import WHERE company_id=?)",arrayOf(id));db.delete("asset_ledger","company_id=?",arrayOf(id));db.delete("ledger_import","company_id=?",arrayOf(id));db.delete("company_change_audit","company_id=?",arrayOf(id));db.delete("company_code_history","company_id=?",arrayOf(id));db.delete("area","company_id=?",arrayOf(id));db.delete("company","id=?",arrayOf(id))}

    private fun copy(source:SQLiteDatabase,target:SQLiteDatabase,table:String,where:String,args:Array<String>,conflict:Int=SQLiteDatabase.CONFLICT_ABORT){source.query(table,null,where,args,null,null,null).use{c->while(c.moveToNext())target.insertWithOnConflict(table,null,values(c),conflict)}}
    private fun values(c:Cursor,skip:Set<String> = emptySet()):ContentValues=ContentValues().apply{for(i in 0 until c.columnCount){val n=c.getColumnName(i);if(n in skip)continue;when(c.getType(i)){Cursor.FIELD_TYPE_NULL->putNull(n);Cursor.FIELD_TYPE_INTEGER->put(n,c.getLong(i));Cursor.FIELD_TYPE_FLOAT->put(n,c.getDouble(i));Cursor.FIELD_TYPE_BLOB->put(n,c.getBlob(i));else->put(n,c.getString(i))}}}
    private fun copyScansAndPhotos(context:Context,source:SQLiteDatabase,target:SQLiteDatabase,preview:BackupPreview,where:String,args:Array<String>){
        source.query("scan_event",null,where,args,null,null,"id").use{c->while(c.moveToNext()){
            val oldId=c.getLong(c.getColumnIndexOrThrow("id"));val newId=target.insertOrThrow("scan_event",null,values(c,setOf("id")))
            source.query("anomaly_photo",null,"scan_id=?",arrayOf(oldId.toString()),null,null,null).use{p->while(p.moveToNext()){
                val oldPath=p.getString(p.getColumnIndexOrThrow("path"));val sourcePhoto=File(preview.file.parentFile,"photos").walkTopDown().firstOrNull{it.isFile&&it.name==File(oldPath).name};val newFile=sourcePhoto?.let{src->File(context.filesDir,"anomaly_photos/${UUID.randomUUID()}_${src.name}").apply{parentFile?.mkdirs();src.copyTo(this,true)}}
                val v=values(p,setOf("id","scan_id","path"));v.put("id",UUID.randomUUID().toString());v.put("scan_id",newId);v.put("path",newFile?.absolutePath?:oldPath);target.insertOrThrow("anomaly_photo",null,v)
            }}
        }}
    }
}
