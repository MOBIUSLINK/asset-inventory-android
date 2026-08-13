package cn.assetinventory.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

data class Company(val id: String, val code: String, val name: String)
data class Area(val id: String, val companyId: String, val code: String, val name: String)
data class InventoryTask(val id: String, val companyId: String, val name: String, val status: String)
data class ScanResultRecord(
    val assetCode: String,
    val areaCode: String,
    val operatorName: String,
    val scannedAt: Long,
    val duplicateInArea: Boolean,
    val outsideLedger: Boolean = false
)
data class ScanEventRecord(
    val id: Long, val assetCode: String, val areaCode: String, val areaName: String,
    val operatorName: String, val scannedAt: Long, val duplicateInArea: Boolean,
    val outsideLedger: Boolean, val revokedAt: Long?, val revokeOperator: String, val revokeReason: String,
    val anomalyType: String, val anomalyNote: String, val anomalyOperator: String, val anomalyAt: Long?, val sourceDevice:String
)
data class AnomalyPhoto(val id: String, val scanId: Long, val path: String, val createdAt: Long)
data class AreaProgress(val code: String, val name: String, val valid: Int, val newAssets: Int, val anomalies: Int, val operators: String, val lastScanAt: Long?)
data class TaskSummary(val ledgerTotal: Int, val scannedLedger: Int, val missing: Int, val newAssets: Int, val anomalies: Int, val duplicates: Int, val revoked: Int)
data class TaskCompletionCheck(val canComplete:Boolean,val duplicateCount:Int,val incompleteAnomalies:Int)
data class ResultImportPreview(
    val company: Company, val task: InventoryTask, val total: Int, val newCount: Int,
    val existingCount: Int, val areaNames: List<String>, val operators: List<String>
)
data class AreaDeliveryStatus(val area: Area, val status: String?, val resultReceivedAt: Long?, val localCount:Int, val receivedCount:Int)
data class LedgerImportRecord(val id:String,val fileName:String,val importedAt:Long,val operator:String,val valid:Int,val issues:Int)
data class TransferScan(
    val uuid:String,val taskId:String,val areaCode:String,val assetCode:String,val rawValue:String,val operator:String,
    val scannedAt:Long,val duplicate:Boolean,val outside:Boolean,val revokedAt:Long?,val revokeOperator:String,val revokeReason:String,
    val anomalyType:String,val anomalyNote:String,val anomalyOperator:String,val anomalyAt:Long?
)

class InventoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "asset_inventory.db", null, 12) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createMasterTables(db)
        createLedgerTables(db)
        createScanTable(db)
        createDeletionAudit(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMasterTables(db)
        if (oldVersion < 3) createLedgerTables(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE scan_event ADD COLUMN outside_ledger INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE scan_event ADD COLUMN revoke_operator TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("UPDATE scan_event SET outside_ledger=CASE WHEN EXISTS(SELECT 1 FROM asset_ledger a WHERE a.code=scan_event.asset_code) THEN 0 ELSE 1 END")
        }
        if (oldVersion < 6) createAnomalyStorage(db)
        if (oldVersion < 7) createCorrectionStorage(db)
        if(oldVersion<8)createSourceStorage(db)
        if(oldVersion<9)migrateLedgerToCompanies(db)
        if(oldVersion<10)createDeliveryTracking(db)
        if(oldVersion<11)createCompanyHistory(db)
        if(oldVersion<12)createDeletionAudit(db)
    }

    private fun createDeletionAudit(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS deletion_audit(
            id TEXT PRIMARY KEY, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
            entity_code TEXT NOT NULL, entity_name TEXT NOT NULL, operator_name TEXT NOT NULL,
            reason TEXT NOT NULL, impact_summary TEXT NOT NULL, deleted_at INTEGER NOT NULL)""")
    }

    private fun migrateLedgerToCompanies(db: SQLiteDatabase) {
        val companyId = db.rawQuery("SELECT company_id FROM inventory_task ORDER BY created_at LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else db.rawQuery("SELECT id FROM company ORDER BY created_at LIMIT 1", null).use { x -> require(x.moveToFirst()) { "缺少公司资料，无法迁移台账" }; x.getString(0) }
        }
        db.execSQL("ALTER TABLE ledger_import ADD COLUMN company_id TEXT")
        db.execSQL("UPDATE ledger_import SET company_id=? WHERE company_id IS NULL", arrayOf(companyId))
        db.execSQL("""CREATE TABLE asset_ledger_v9(
            company_id TEXT NOT NULL, code TEXT NOT NULL, name TEXT, warehouse TEXT, category TEXT, status TEXT,
            user_name TEXT, department TEXT, source TEXT, serial_number TEXT, asset_type TEXT, amount TEXT,
            specification TEXT, location TEXT, purchase_date TEXT, supplier TEXT, note TEXT,
            import_id TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(company_id,code),
            FOREIGN KEY(company_id) REFERENCES company(id), FOREIGN KEY(import_id) REFERENCES ledger_import(id))""")
        db.execSQL("""INSERT INTO asset_ledger_v9(company_id,code,name,warehouse,category,status,user_name,department,source,serial_number,asset_type,amount,specification,location,purchase_date,supplier,note,import_id,updated_at)
            SELECT ?,code,name,warehouse,category,status,user_name,department,source,serial_number,asset_type,amount,specification,location,purchase_date,supplier,note,import_id,updated_at FROM asset_ledger""", arrayOf(companyId))
        db.execSQL("DROP TABLE asset_ledger")
        db.execSQL("ALTER TABLE asset_ledger_v9 RENAME TO asset_ledger")
        db.execSQL("CREATE INDEX idx_ledger_company_code ON asset_ledger(company_id,code)")
    }

    private fun createLedgerTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ledger_import (
                id TEXT PRIMARY KEY, file_name TEXT NOT NULL, imported_at INTEGER NOT NULL,
                operator_name TEXT NOT NULL, valid_count INTEGER NOT NULL, issue_count INTEGER NOT NULL,
                company_id TEXT NOT NULL, FOREIGN KEY(company_id) REFERENCES company(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS asset_ledger (
                company_id TEXT NOT NULL, code TEXT NOT NULL, name TEXT, warehouse TEXT, category TEXT, status TEXT,
                user_name TEXT, department TEXT, source TEXT, serial_number TEXT, asset_type TEXT,
                amount TEXT, specification TEXT, location TEXT, purchase_date TEXT, supplier TEXT,
                note TEXT, import_id TEXT NOT NULL, updated_at INTEGER NOT NULL,
                PRIMARY KEY(company_id,code), FOREIGN KEY(company_id) REFERENCES company(id),
                FOREIGN KEY(import_id) REFERENCES ledger_import(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ledger_issue (
                id TEXT PRIMARY KEY, import_id TEXT NOT NULL, row_number INTEGER NOT NULL,
                raw_code TEXT NOT NULL, reason TEXT NOT NULL,
                FOREIGN KEY(import_id) REFERENCES ledger_import(id)
            )
        """.trimIndent())
    }

    private fun createMasterTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS company (
                id TEXT PRIMARY KEY, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL
            )
        """.trimIndent())
        createCompanyHistory(db)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS area (
                id TEXT PRIMARY KEY, company_id TEXT NOT NULL, code TEXT NOT NULL,
                name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL,
                UNIQUE(company_id, code), FOREIGN KEY(company_id) REFERENCES company(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inventory_task (
                id TEXT PRIMARY KEY, company_id TEXT NOT NULL, name TEXT NOT NULL,
                status TEXT NOT NULL, created_at INTEGER NOT NULL,
                FOREIGN KEY(company_id) REFERENCES company(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS task_area (
                task_id TEXT NOT NULL, area_id TEXT NOT NULL, area_code_snapshot TEXT NOT NULL,
                area_name_snapshot TEXT NOT NULL, result_received_at INTEGER,
                PRIMARY KEY(task_id, area_id),
                FOREIGN KEY(task_id) REFERENCES inventory_task(id),
                FOREIGN KEY(area_id) REFERENCES area(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS work_session (
                id TEXT PRIMARY KEY, task_id TEXT NOT NULL, operator_name TEXT NOT NULL,
                started_at INTEGER NOT NULL, ended_at INTEGER, handover_note TEXT,
                FOREIGN KEY(task_id) REFERENCES inventory_task(id)
            )
        """.trimIndent())
    }

    private fun createCompanyHistory(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS company_code_history(
            company_id TEXT NOT NULL, code TEXT NOT NULL COLLATE NOCASE UNIQUE,
            changed_at INTEGER NOT NULL, operator_name TEXT NOT NULL, reason TEXT NOT NULL,
            PRIMARY KEY(company_id,code), FOREIGN KEY(company_id) REFERENCES company(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS company_change_audit(
            id TEXT PRIMARY KEY, company_id TEXT NOT NULL, old_code TEXT NOT NULL, new_code TEXT NOT NULL,
            old_name TEXT NOT NULL, new_name TEXT NOT NULL, operator_name TEXT NOT NULL,
            reason TEXT NOT NULL, changed_at INTEGER NOT NULL,
            FOREIGN KEY(company_id) REFERENCES company(id))""")
    }

    private fun createDeliveryTracking(db: SQLiteDatabase) {
        runCatching { db.execSQL("ALTER TABLE task_area ADD COLUMN result_received_at INTEGER") }
    }

    fun areaDeliveryStatuses(taskId: String): List<AreaDeliveryStatus> = readableDatabase.rawQuery("""
        SELECT a.id,a.company_id,ta.area_code_snapshot,ta.area_name_snapshot,
               ta.result_received_at,
               (SELECT COUNT(*) FROM scan_event s WHERE s.task_id=ta.task_id AND s.area_code=ta.area_code_snapshot AND COALESCE(s.source_package,'')='' AND s.duplicate_in_area=0 AND s.revoked_at IS NULL),
               (SELECT COUNT(*) FROM scan_event s WHERE s.task_id=ta.task_id AND s.area_code=ta.area_code_snapshot AND COALESCE(s.source_package,'')<>'' AND s.duplicate_in_area=0 AND s.revoked_at IS NULL)
        FROM task_area ta JOIN area a ON a.id=ta.area_id WHERE ta.task_id=? ORDER BY ta.area_name_snapshot
    """.trimIndent(), arrayOf(taskId)).use { c -> buildList { while(c.moveToNext()) {
        val area=Area(c.getString(0),c.getString(1),c.getString(2),c.getString(3))
        val received=if(c.isNull(4))null else c.getLong(4);val localCount=c.getInt(5);val receivedCount=c.getInt(6)
        val status=when { receivedCount>0->"已收到结果";localCount>0->"主手机已盘";received!=null->"已收到空结果";else->"尚未回收" }
        add(AreaDeliveryStatus(area,status,received,localCount,receivedCount))
    } } }

    private fun createScanTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE scan_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT, event_uuid TEXT NOT NULL UNIQUE,
                task_id TEXT NOT NULL, area_code TEXT NOT NULL, asset_code TEXT NOT NULL,
                raw_value TEXT NOT NULL, operator_name TEXT NOT NULL, scanned_at INTEGER NOT NULL,
                duplicate_in_area INTEGER NOT NULL DEFAULT 0, revoked_at INTEGER, revoke_reason TEXT
                , outside_ledger INTEGER NOT NULL DEFAULT 0, revoke_operator TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_scan_task_area_asset ON scan_event(task_id, area_code, asset_code)")
        createAnomalyStorage(db)
        createCorrectionStorage(db)
        createSourceStorage(db)
    }
    private fun createSourceStorage(db:SQLiteDatabase){runCatching{db.execSQL("ALTER TABLE scan_event ADD COLUMN source_device TEXT")};runCatching{db.execSQL("ALTER TABLE scan_event ADD COLUMN source_package TEXT")}}

    private fun createCorrectionStorage(db: SQLiteDatabase) {
        fun addColumn(name: String, type: String) { runCatching { db.execSQL("ALTER TABLE scan_event ADD COLUMN $name $type") } }
        addColumn("original_asset_code", "TEXT")
        addColumn("correction_operator", "TEXT")
        addColumn("correction_reason", "TEXT")
        addColumn("corrected_at", "INTEGER")
    }

    private fun createAnomalyStorage(db: SQLiteDatabase) {
        fun addColumn(name: String, type: String) {
            runCatching { db.execSQL("ALTER TABLE scan_event ADD COLUMN $name $type") }
        }
        addColumn("anomaly_type", "TEXT")
        addColumn("anomaly_note", "TEXT")
        addColumn("anomaly_operator", "TEXT")
        addColumn("anomaly_at", "INTEGER")
        db.execSQL("""CREATE TABLE IF NOT EXISTS anomaly_photo(
            id TEXT PRIMARY KEY, scan_id INTEGER NOT NULL, path TEXT NOT NULL, created_at INTEGER NOT NULL,
            FOREIGN KEY(scan_id) REFERENCES scan_event(id)
        )""")
    }

    fun createCompany(code: String, name: String): Company {
        val company = Company(UUID.randomUUID().toString(), code.trim().uppercase(), name.trim())
        require(company.code.isNotBlank() && company.name.isNotBlank()) { "公司编号和名称不能为空" }
        require(readableDatabase.rawQuery("SELECT 1 FROM company_code_history WHERE code=? COLLATE NOCASE", arrayOf(company.code)).use { !it.moveToFirst() }) {
            "该编号曾由其他公司使用，不能重复使用"
        }
        writableDatabase.insertOrThrow("company", null, ContentValues().apply {
            put("id", company.id); put("code", company.code); put("name", company.name)
            put("created_at", System.currentTimeMillis())
        })
        return company
    }

    fun updateCompany(companyId:String, code:String, name:String, operator:String, reason:String) {
        val newCode=code.trim().uppercase(); val newName=name.trim(); val who=operator.trim(); val why=reason.trim()
        require(newCode.isNotBlank()&&newName.isNotBlank()){ "公司编号和名称不能为空" }
        require(who.isNotBlank()){ "请填写操作人" }; require(why.isNotBlank()){ "请填写修改原因" }
        val old=company(companyId); require(old.code!=newCode||old.name!=newName){ "公司资料没有变化" }
        val db=writableDatabase; db.beginTransaction()
        try {
            require(!db.rawQuery("SELECT 1 FROM company WHERE code=? COLLATE NOCASE AND id<>?",arrayOf(newCode,companyId)).use{it.moveToFirst()}){"公司编号已被使用"}
            val aliasOwner=db.rawQuery("SELECT company_id FROM company_code_history WHERE code=? COLLATE NOCASE",arrayOf(newCode)).use{if(it.moveToFirst())it.getString(0)else null}
            require(aliasOwner==null||aliasOwner==companyId){"公司编号曾由其他公司使用，不能重复使用"}
            if(old.code!=newCode){
                db.delete("company_code_history","company_id=? AND code=? COLLATE NOCASE",arrayOf(companyId,newCode))
                db.insertWithOnConflict("company_code_history",null,ContentValues().apply{
                    put("company_id",companyId);put("code",old.code);put("changed_at",System.currentTimeMillis());put("operator_name",who);put("reason",why)
                },SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.update("company",ContentValues().apply{put("code",newCode);put("name",newName)},"id=?",arrayOf(companyId))
            db.insertOrThrow("company_change_audit",null,ContentValues().apply{
                put("id",UUID.randomUUID().toString());put("company_id",companyId);put("old_code",old.code);put("new_code",newCode)
                put("old_name",old.name);put("new_name",newName);put("operator_name",who);put("reason",why);put("changed_at",System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun companyAcceptsCode(companyId:String, code:String):Boolean {
        val normalized=code.trim().uppercase()
        return readableDatabase.rawQuery("""SELECT 1 FROM company WHERE id=? AND code=? COLLATE NOCASE
            UNION SELECT 1 FROM company_code_history WHERE company_id=? AND code=? COLLATE NOCASE LIMIT 1""",
            arrayOf(companyId,normalized,companyId,normalized)).use{it.moveToFirst()}
    }

    fun companies(): List<Company> = readableDatabase.rawQuery(
        "SELECT id,code,name FROM company WHERE enabled=1 ORDER BY created_at", null
    ).use { c -> buildList { while (c.moveToNext()) add(Company(c.getString(0), c.getString(1), c.getString(2))) } }

    fun addArea(companyId: String, code: String, name: String): Area {
        val area = Area(UUID.randomUUID().toString(), companyId, code.trim().uppercase(), name.trim())
        writableDatabase.insertOrThrow("area", null, ContentValues().apply {
            put("id", area.id); put("company_id", companyId); put("code", area.code); put("name", area.name)
            put("created_at", System.currentTimeMillis())
        })
        return area
    }

    fun updateArea(areaId: String, code: String, name: String) {
        val db = writableDatabase
        val normalizedCode = code.trim().uppercase()
        val normalizedName = name.trim()
        val oldCode = db.rawQuery("SELECT code FROM area WHERE id=?", arrayOf(areaId)).use {
            if (!it.moveToFirst()) error("没有找到需要修改的区域")
            it.getString(0)
        }
        db.beginTransaction()
        try {
            db.update("area", ContentValues().apply {
                put("code", normalizedCode); put("name", normalizedName)
            }, "id=?", arrayOf(areaId))
            val activeTaskIds = db.rawQuery("""
                SELECT ta.task_id FROM task_area ta
                JOIN inventory_task t ON t.id=ta.task_id
                WHERE ta.area_id=? AND t.status='进行中'
            """.trimIndent(), arrayOf(areaId)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            activeTaskIds.forEach { taskId ->
                db.update("task_area", ContentValues().apply {
                    put("area_code_snapshot", normalizedCode); put("area_name_snapshot", normalizedName)
                }, "task_id=? AND area_id=?", arrayOf(taskId, areaId))
                db.update("scan_event", ContentValues().apply { put("area_code", normalizedCode) },
                    "task_id=? AND area_code=?", arrayOf(taskId, oldCode))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun areas(companyId: String): List<Area> = readableDatabase.rawQuery(
        "SELECT id,company_id,code,name FROM area WHERE company_id=? AND enabled=1 ORDER BY created_at",
        arrayOf(companyId)
    ).use { c -> buildList { while (c.moveToNext()) add(Area(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }
    fun allAreas():List<Area> = readableDatabase.rawQuery("SELECT id,company_id,code,name FROM area WHERE enabled=1 ORDER BY name",null).use{c->buildList{while(c.moveToNext())add(Area(c.getString(0),c.getString(1),c.getString(2),c.getString(3)))}}

    fun createTask(companyId: String, name: String): InventoryTask {
        val finalName=name.trim().ifBlank{suggestedTaskName(companyId)}
        val task = InventoryTask(UUID.randomUUID().toString(), companyId, finalName, "进行中")
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertOrThrow("inventory_task", null, ContentValues().apply {
                put("id", task.id); put("company_id", companyId); put("name", task.name)
                put("status", task.status); put("created_at", System.currentTimeMillis())
            })
            areas(companyId).forEach { area ->
                writableDatabase.insertOrThrow("task_area", null, ContentValues().apply {
                    put("task_id", task.id); put("area_id", area.id)
                    put("area_code_snapshot", area.code); put("area_name_snapshot", area.name)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return task
    }

    fun suggestedTaskName(companyId:String):String{
        val companyName=company(companyId).name
        val date=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.CHINA).format(java.util.Date())
        val base="$companyName $date 盘点"
        val existing=readableDatabase.rawQuery("SELECT name FROM inventory_task WHERE company_id=? AND (name=? OR name LIKE ?)",arrayOf(companyId,base,"$base-%")).use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(0))}}
        if(base !in existing)return base
        var sequence=2
        while("$base-$sequence" in existing)sequence++
        return "$base-$sequence"
    }

    fun tasks(): List<InventoryTask> = readableDatabase.rawQuery(
        "SELECT id,company_id,name,status FROM inventory_task ORDER BY created_at DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(InventoryTask(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }

    fun taskCompletionCheck(taskId:String):TaskCompletionCheck{
        val duplicates=crossRegionDuplicateCodes(taskId).size
        val incomplete=readableDatabase.rawQuery("SELECT COUNT(*) FROM scan_event WHERE task_id=? AND revoked_at IS NULL AND COALESCE(anomaly_type,'')<>'' AND COALESCE(anomaly_operator,'')=''",arrayOf(taskId)).use{if(it.moveToFirst())it.getInt(0)else 0}
        return TaskCompletionCheck(duplicates==0&&incomplete==0,duplicates,incomplete)
    }
    fun completeTask(taskId:String){require(taskCompletionCheck(taskId).canComplete){"仍有待处理事项"};writableDatabase.update("inventory_task",ContentValues().apply{put("status","已完成")},"id=? AND status='进行中'",arrayOf(taskId))}
    fun reopenTask(taskId:String,operator:String,reason:String){require(operator.isNotBlank()&&reason.isNotBlank());writableDatabase.beginTransaction();try{writableDatabase.update("inventory_task",ContentValues().apply{put("status","进行中")},"id=? AND status IN ('已完成','已归档')",arrayOf(taskId));writableDatabase.insert("work_session",null,ContentValues().apply{put("id",UUID.randomUUID().toString());put("task_id",taskId);put("operator_name",operator.trim());put("started_at",System.currentTimeMillis());put("ended_at",System.currentTimeMillis());put("handover_note","重新开启：${reason.trim()}")});writableDatabase.setTransactionSuccessful()}finally{writableDatabase.endTransaction()}}
    fun archiveTask(taskId:String){writableDatabase.update("inventory_task",ContentValues().apply{put("status","已归档")},"id=? AND status='已完成'",arrayOf(taskId))}

    fun deleteTask(taskId: String, operator: String, reason: String) {
        val who=operator.trim();val why=reason.trim();require(who.isNotBlank()){ "请填写操作人" };require(why.isNotBlank()){ "请填写删除原因" }
        val target=task(taskId);val photos=readableDatabase.rawQuery("SELECT p.path FROM anomaly_photo p JOIN scan_event s ON s.id=p.scan_id WHERE s.task_id=?",arrayOf(taskId)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        val scans=readableDatabase.rawQuery("SELECT COUNT(*) FROM scan_event WHERE task_id=?",arrayOf(taskId)).use{it.moveToFirst();it.getInt(0)}
        val db=writableDatabase;db.beginTransaction()
        try{
            db.delete("anomaly_photo","scan_id IN (SELECT id FROM scan_event WHERE task_id=?)",arrayOf(taskId))
            db.delete("scan_event","task_id=?",arrayOf(taskId));db.delete("work_session","task_id=?",arrayOf(taskId));db.delete("task_area","task_id=?",arrayOf(taskId));db.delete("inventory_task","id=?",arrayOf(taskId))
            db.insertOrThrow("deletion_audit",null,ContentValues().apply{put("id",UUID.randomUUID().toString());put("entity_type","TASK");put("entity_id",target.id);put("entity_code",target.id);put("entity_name",target.name);put("operator_name",who);put("reason",why);put("impact_summary","扫码记录 $scans 条，照片 ${photos.size} 张");put("deleted_at",System.currentTimeMillis())})
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        photos.forEach{runCatching{File(it).delete()}}
    }

    fun deleteCompany(companyId: String, operator: String, reason: String) {
        val who=operator.trim();val why=reason.trim();require(who.isNotBlank()){ "请填写操作人" };require(why.isNotBlank()){ "请填写删除原因" }
        val target=company(companyId)
        val taskCount=readableDatabase.rawQuery("SELECT COUNT(*) FROM inventory_task WHERE company_id=?",arrayOf(companyId)).use{it.moveToFirst();it.getInt(0)}
        val assetCount=ledgerAssetCount(companyId);val areaCount=areas(companyId).size
        val photos=readableDatabase.rawQuery("SELECT p.path FROM anomaly_photo p JOIN scan_event s ON s.id=p.scan_id JOIN inventory_task t ON t.id=s.task_id WHERE t.company_id=?",arrayOf(companyId)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        val db=writableDatabase;db.beginTransaction()
        try{
            db.delete("anomaly_photo","scan_id IN (SELECT s.id FROM scan_event s JOIN inventory_task t ON t.id=s.task_id WHERE t.company_id=?)",arrayOf(companyId))
            db.delete("scan_event","task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(companyId))
            db.delete("work_session","task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(companyId))
            db.delete("task_area","task_id IN (SELECT id FROM inventory_task WHERE company_id=?)",arrayOf(companyId))
            db.delete("inventory_task","company_id=?",arrayOf(companyId))
            db.delete("ledger_issue","import_id IN (SELECT id FROM ledger_import WHERE company_id=?)",arrayOf(companyId))
            db.delete("asset_ledger","company_id=?",arrayOf(companyId));db.delete("ledger_import","company_id=?",arrayOf(companyId))
            db.delete("company_change_audit","company_id=?",arrayOf(companyId));db.delete("company_code_history","company_id=?",arrayOf(companyId))
            db.delete("area","company_id=?",arrayOf(companyId));db.delete("company","id=?",arrayOf(companyId))
            db.insertOrThrow("deletion_audit",null,ContentValues().apply{put("id",UUID.randomUUID().toString());put("entity_type","COMPANY");put("entity_id",target.id);put("entity_code",target.code);put("entity_name",target.name);put("operator_name",who);put("reason",why);put("impact_summary","台账 $assetCount 项，区域 $areaCount 个，任务 $taskCount 个，照片 ${photos.size} 张");put("deleted_at",System.currentTimeMillis())})
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        photos.forEach{runCatching{File(it).delete()}}
    }

    fun company(id: String): Company = readableDatabase.rawQuery("SELECT id,code,name FROM company WHERE id=?", arrayOf(id)).use { c ->
        require(c.moveToFirst()); Company(c.getString(0), c.getString(1), c.getString(2))
    }

    fun importTaskPackage(pkg: OfflineTaskPackage): InventoryTask {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var companyId = db.rawQuery("SELECT id FROM company WHERE id=?",arrayOf(pkg.company.id)).use{if(it.moveToFirst())it.getString(0)else null}
            if(companyId==null) companyId=db.rawQuery("""SELECT id FROM company WHERE code=? COLLATE NOCASE UNION
                SELECT company_id FROM company_code_history WHERE code=? COLLATE NOCASE LIMIT 1""",arrayOf(pkg.company.code,pkg.company.code)).use{if(it.moveToFirst())it.getString(0)else null}
            if(companyId==null){
                require(!db.rawQuery("SELECT 1 FROM company_code_history WHERE code=? COLLATE NOCASE",arrayOf(pkg.company.code)).use{it.moveToFirst()}){"任务包公司编号与历史编号冲突"}
                db.insertOrThrow("company", null, ContentValues().apply { put("id",pkg.company.id);put("code",pkg.company.code.uppercase());put("name",pkg.company.name);put("enabled",1);put("created_at",pkg.createdAt) })
                companyId=pkg.company.id
            }
            db.insertWithOnConflict("inventory_task", null, ContentValues().apply {
                put("id",pkg.task.id); put("company_id",companyId); put("name",pkg.task.name); put("status","进行中"); put("created_at",pkg.createdAt)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            pkg.areas.forEach { area ->
                db.insertWithOnConflict("area",null,ContentValues().apply { put("id",area.id);put("company_id",companyId);put("code",area.code);put("name",area.name);put("enabled",1);put("created_at",pkg.createdAt) },SQLiteDatabase.CONFLICT_IGNORE)
                val areaId=db.rawQuery("SELECT id FROM area WHERE company_id=? AND code=?",arrayOf(companyId,area.code)).use{c->require(c.moveToFirst());c.getString(0)}
                db.insertWithOnConflict("task_area",null,ContentValues().apply{put("task_id",pkg.task.id);put("area_id",areaId);put("area_code_snapshot",area.code);put("area_name_snapshot",area.name)},SQLiteDatabase.CONFLICT_IGNORE)
            }
            val importId="TASK-${pkg.task.id}"
            db.insertWithOnConflict("ledger_import",null,ContentValues().apply{put("id",importId);put("file_name","离线任务包");put("imported_at",pkg.createdAt);put("operator_name","任务包导入");put("valid_count",pkg.assets.size);put("issue_count",0);put("company_id",companyId)},SQLiteDatabase.CONFLICT_IGNORE)
            pkg.assets.forEach { a -> db.insertWithOnConflict("asset_ledger",null,ContentValues().apply {
                put("company_id",companyId);put("code",a.code);put("name",a.name);put("warehouse",a.warehouse);put("category",a.category);put("status",a.status);put("user_name",a.user);put("department",a.department);put("source",a.source);put("serial_number",a.serialNumber);put("asset_type",a.assetType);put("amount",a.amount);put("specification",a.specification);put("location",a.location);put("purchase_date",a.purchaseDate);put("supplier",a.supplier);put("note",a.note);put("import_id",importId);put("updated_at",pkg.createdAt)
            },SQLiteDatabase.CONFLICT_IGNORE) }
            db.setTransactionSuccessful()
            return InventoryTask(pkg.task.id,companyId,pkg.task.name,"进行中")
        } finally { db.endTransaction() }
    }

    fun task(id:String): InventoryTask = readableDatabase.rawQuery("SELECT id,company_id,name,status FROM inventory_task WHERE id=?",arrayOf(id)).use{c->require(c.moveToFirst());InventoryTask(c.getString(0),c.getString(1),c.getString(2),c.getString(3))}

    fun transferScans(taskId:String):List<TransferScan> = readableDatabase.rawQuery("""
        SELECT event_uuid,task_id,area_code,asset_code,raw_value,operator_name,scanned_at,duplicate_in_area,outside_ledger,
        revoked_at,COALESCE(revoke_operator,''),COALESCE(revoke_reason,''),COALESCE(anomaly_type,''),COALESCE(anomaly_note,''),COALESCE(anomaly_operator,''),anomaly_at
        FROM scan_event WHERE task_id=? ORDER BY scanned_at
    """.trimIndent(),arrayOf(taskId)).use{c->buildList{while(c.moveToNext())add(TransferScan(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getInt(7)!=0,c.getInt(8)!=0,if(c.isNull(9))null else c.getLong(9),c.getString(10),c.getString(11),c.getString(12),c.getString(13),c.getString(14),if(c.isNull(15))null else c.getLong(15)))}}

    fun scanPhotosByUuid(taskId:String):Map<String,List<AnomalyPhoto>> = readableDatabase.rawQuery("""
        SELECT s.event_uuid,p.id,p.scan_id,p.path,p.created_at FROM anomaly_photo p JOIN scan_event s ON s.id=p.scan_id WHERE s.task_id=?
    """.trimIndent(),arrayOf(taskId)).use{c->buildMap<String,MutableList<AnomalyPhoto>>{while(c.moveToNext())getOrPut(c.getString(0)){mutableListOf()}.add(AnomalyPhoto(c.getString(1),c.getLong(2),c.getString(3),c.getLong(4)))}}

    fun importResultPackage(pkg:OfflineResultPackage,photoPaths:Map<String,List<String>>):Pair<Int,Int>{
        val db=writableDatabase;var added=0;var skipped=0
        validateResultPackage(pkg)
        db.beginTransaction();try{
            pkg.scans.forEach{s->
                val values=ContentValues().apply{put("event_uuid",s.uuid);put("task_id",s.taskId);put("area_code",s.areaCode);put("asset_code",s.assetCode);put("raw_value",s.rawValue);put("operator_name",s.operator);put("scanned_at",s.scannedAt);put("duplicate_in_area",if(s.duplicate)1 else 0);put("outside_ledger",if(s.outside)1 else 0);if(s.revokedAt!=null)put("revoked_at",s.revokedAt);put("revoke_operator",s.revokeOperator);put("revoke_reason",s.revokeReason);put("anomaly_type",s.anomalyType);put("anomaly_note",s.anomalyNote);put("anomaly_operator",s.anomalyOperator);if(s.anomalyAt!=null)put("anomaly_at",s.anomalyAt);put("source_device",pkg.deviceName);put("source_package",pkg.packageId)}
                val id=db.insertWithOnConflict("scan_event",null,values,SQLiteDatabase.CONFLICT_IGNORE)
                if(id<0)skipped++ else {added++;photoPaths[s.uuid].orEmpty().forEach{path->db.insertWithOnConflict("anomaly_photo",null,ContentValues().apply{put("id",UUID.randomUUID().toString());put("scan_id",id);put("path",path);put("created_at",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_IGNORE)}}
            }
            val receivedAreas=(pkg.areaCodes.ifEmpty { pkg.scans.map { it.areaCode } }).distinct()
            if(receivedAreas.isNotEmpty()) {
                val placeholders=receivedAreas.joinToString(","){"?"}
                db.execSQL("UPDATE task_area SET result_received_at=? WHERE task_id=? AND area_code_snapshot IN ($placeholders)",buildList<Any>{add(System.currentTimeMillis());add(pkg.taskId);addAll(receivedAreas)}.toTypedArray())
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()};return added to skipped
    }

    fun resultImportPreview(pkg: OfflineResultPackage): ResultImportPreview {
        val target = validateResultPackage(pkg)
        val existing = pkg.scans.count { scan ->
            readableDatabase.rawQuery("SELECT 1 FROM scan_event WHERE event_uuid=? LIMIT 1", arrayOf(scan.uuid)).use { it.moveToFirst() }
        }
        val areaMap = taskAreas(target.id).associateBy { it.code }
        return ResultImportPreview(
            company(target.companyId), target, pkg.scans.size, pkg.scans.size - existing, existing,
            pkg.scans.mapNotNull { areaMap[it.areaCode]?.name }.distinct().sorted(),
            pkg.scans.map { it.operator.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        )
    }

    private fun validateResultPackage(pkg: OfflineResultPackage): InventoryTask {
        require(pkg.packageId.isNotBlank()) { "结果包缺少唯一标识" }
        val target = readableDatabase.rawQuery(
            "SELECT id,company_id,name,status FROM inventory_task WHERE id=?", arrayOf(pkg.taskId)
        ).use { c ->
            require(c.moveToFirst()) { "主手机中没有对应任务，请先导入或创建对应任务" }
            InventoryTask(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
        }
        val targetCompany = company(target.companyId)
        if (pkg.companyCode.isNotBlank()) require(companyAcceptsCode(targetCompany.id, pkg.companyCode)) {
            "结果包属于公司 ${pkg.companyCode}，不能合并到 ${targetCompany.code}"
        }
        require(target.status == "进行中") { "任务已完成或归档，不能继续合并结果" }
        require(pkg.scans.map { it.uuid }.distinct().size == pkg.scans.size) { "结果包内存在重复记录，无法合并" }
        require(pkg.scans.all { it.uuid.isNotBlank() && it.taskId == pkg.taskId }) { "结果包内存在不属于该任务的记录" }
        val allowedAreas = taskAreas(target.id).map { it.code }.toSet()
        val invalidAreas = (pkg.areaCodes + pkg.scans.map { it.areaCode }).filter { it !in allowedAreas }.distinct()
        require(invalidAreas.isEmpty()) { "结果包包含未分配给该任务的区域：${invalidAreas.joinToString("、")}" }
        require(pkg.scans.all { it.assetCode.isNotBlank() }) { "结果包内存在空资产编号" }
        return target
    }

    fun taskAreas(taskId: String): List<Area> = readableDatabase.rawQuery("""
        SELECT a.id,a.company_id,ta.area_code_snapshot,ta.area_name_snapshot
        FROM task_area ta JOIN area a ON a.id=ta.area_id WHERE ta.task_id=? ORDER BY ta.area_name_snapshot
    """.trimIndent(), arrayOf(taskId)).use { c ->
        buildList { while (c.moveToNext()) add(Area(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
    }

    fun startSession(taskId: String, operatorName: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("work_session", null, ContentValues().apply {
            put("id", id); put("task_id", taskId); put("operator_name", operatorName.trim())
            put("started_at", System.currentTimeMillis())
        })
        return id
    }

    fun endSession(sessionId: String, note: String = "") {
        writableDatabase.update("work_session", ContentValues().apply {
            put("ended_at", System.currentTimeMillis()); put("handover_note", note)
        }, "id=?", arrayOf(sessionId))
    }

    fun recordScan(taskId: String, areaCode: String, assetCode: String, rawValue: String,
                   operatorName: String, scannedAt: Long = System.currentTimeMillis()): ScanResultRecord {
        val db = writableDatabase
        val duplicate = db.rawQuery(
            "SELECT 1 FROM scan_event WHERE task_id=? AND area_code=? AND asset_code=? AND duplicate_in_area=0 AND revoked_at IS NULL LIMIT 1",
            arrayOf(taskId, areaCode, assetCode)
        ).use { it.moveToFirst() }
        val companyId = task(taskId).companyId
        val outsideLedger = db.rawQuery("SELECT 1 FROM asset_ledger WHERE company_id=? AND code=? LIMIT 1", arrayOf(companyId, assetCode))
            .use { !it.moveToFirst() }
        db.insertOrThrow("scan_event", null, ContentValues().apply {
            put("event_uuid", UUID.randomUUID().toString()); put("task_id", taskId)
            put("area_code", areaCode); put("asset_code", assetCode); put("raw_value", rawValue)
            put("operator_name", operatorName); put("scanned_at", scannedAt)
            put("duplicate_in_area", if (duplicate) 1 else 0)
            put("outside_ledger", if (outsideLedger) 1 else 0)
        })
        return ScanResultRecord(assetCode, areaCode, operatorName, scannedAt, duplicate, outsideLedger)
    }

    fun scanRecords(taskId: String, query: String = ""): List<ScanEventRecord> {
        val keyword = query.trim()
        val extra = if (keyword.isBlank()) "" else "AND (s.asset_code LIKE ? OR s.operator_name LIKE ? OR s.area_code LIKE ? OR ta.area_name_snapshot LIKE ?)"
        val args = mutableListOf(taskId).apply { if (keyword.isNotBlank()) repeat(4) { add("%$keyword%") } }.toTypedArray()
        return readableDatabase.rawQuery("""
            SELECT s.id,s.asset_code,s.area_code,COALESCE(ta.area_name_snapshot,s.area_code),s.operator_name,
                   s.scanned_at,s.duplicate_in_area,s.outside_ledger,s.revoked_at,
                   COALESCE(s.revoke_operator,''),COALESCE(s.revoke_reason,''),
                   COALESCE(s.anomaly_type,''),COALESCE(s.anomaly_note,''),COALESCE(s.anomaly_operator,''),s.anomaly_at,COALESCE(s.source_device,'本机')
            FROM scan_event s LEFT JOIN task_area ta ON ta.task_id=s.task_id AND ta.area_code_snapshot=s.area_code
            WHERE s.task_id=? $extra ORDER BY s.scanned_at DESC
        """.trimIndent(), args).use { c -> buildList { while (c.moveToNext()) add(ScanEventRecord(
            c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5),
            c.getInt(6) != 0, c.getInt(7) != 0, if (c.isNull(8)) null else c.getLong(8), c.getString(9), c.getString(10),
            c.getString(11), c.getString(12), c.getString(13), if (c.isNull(14)) null else c.getLong(14),c.getString(15)
        )) } }
    }

    fun saveAnomaly(scanId: Long, type: String, note: String, operator: String) {
        require(type.isNotBlank() && operator.isNotBlank())
        writableDatabase.update("scan_event", ContentValues().apply {
            put("anomaly_type", type); put("anomaly_note", note.trim()); put("anomaly_operator", operator.trim()); put("anomaly_at", System.currentTimeMillis())
        }, "id=? AND revoked_at IS NULL", arrayOf(scanId.toString()))
    }

    fun clearAnomaly(scanId: Long) {
        writableDatabase.update("scan_event", ContentValues().apply {
            putNull("anomaly_type"); putNull("anomaly_note"); putNull("anomaly_operator"); putNull("anomaly_at")
        }, "id=?", arrayOf(scanId.toString()))
    }

    fun anomalyPhotos(scanId: Long): List<AnomalyPhoto> = readableDatabase.rawQuery(
        "SELECT id,scan_id,path,created_at FROM anomaly_photo WHERE scan_id=? ORDER BY created_at", arrayOf(scanId.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(AnomalyPhoto(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3))) } }

    fun addAnomalyPhoto(scanId: Long, path: String) {
        writableDatabase.insertOrThrow("anomaly_photo", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("scan_id", scanId); put("path", path); put("created_at", System.currentTimeMillis())
        })
    }

    fun removeAnomalyPhoto(id: String) { writableDatabase.delete("anomaly_photo", "id=?", arrayOf(id)) }

    fun revokeScan(id: Long, operator: String, reason: String) {
        require(operator.isNotBlank() && reason.isNotBlank())
        writableDatabase.update("scan_event", ContentValues().apply {
            put("revoked_at", System.currentTimeMillis()); put("revoke_operator", operator.trim()); put("revoke_reason", reason.trim())
        }, "id=? AND revoked_at IS NULL", arrayOf(id.toString()))
    }

    fun crossRegionDuplicateCodes(taskId: String): List<String> = readableDatabase.rawQuery("""
        SELECT asset_code FROM scan_event WHERE task_id=? AND duplicate_in_area=0 AND revoked_at IS NULL
        GROUP BY asset_code HAVING COUNT(DISTINCT area_code)>1 ORDER BY asset_code
    """.trimIndent(), arrayOf(taskId)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun activeOtherAreaNames(taskId:String,assetCode:String,currentAreaCode:String):List<String> = readableDatabase.rawQuery("""
        SELECT DISTINCT COALESCE(ta.area_name_snapshot,s.area_code)
        FROM scan_event s LEFT JOIN task_area ta ON ta.task_id=s.task_id AND ta.area_code_snapshot=s.area_code
        WHERE s.task_id=? AND s.asset_code=? AND s.area_code<>? AND s.duplicate_in_area=0 AND s.revoked_at IS NULL
        ORDER BY s.scanned_at DESC
    """.trimIndent(),arrayOf(taskId,assetCode,currentAreaCode)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}

    fun correctAssetCode(id: Long, newCode: String, operator: String, reason: String) {
        val code = XlsxImporter.normalizedCode(newCode)
        require(XlsxImporter.isValidCode(code) && operator.isNotBlank() && reason.isNotBlank())
        val db = writableDatabase
        val row = db.rawQuery("SELECT task_id,area_code,asset_code FROM scan_event WHERE id=? AND revoked_at IS NULL", arrayOf(id.toString()))
            .use { c -> require(c.moveToFirst()); Triple(c.getString(0), c.getString(1), c.getString(2)) }
        val exists = db.rawQuery("SELECT 1 FROM scan_event WHERE task_id=? AND area_code=? AND asset_code=? AND revoked_at IS NULL LIMIT 1", arrayOf(row.first, row.second, code)).use { it.moveToFirst() }
        require(!exists) { "该区域已经存在此编号" }
        val outside = db.rawQuery("SELECT 1 FROM asset_ledger WHERE company_id=(SELECT company_id FROM inventory_task WHERE id=?) AND code=? LIMIT 1", arrayOf(row.first,code)).use { !it.moveToFirst() }
        db.update("scan_event", ContentValues().apply {
            put("original_asset_code", row.third); put("asset_code", code); put("outside_ledger", if (outside) 1 else 0)
            put("correction_operator", operator.trim()); put("correction_reason", reason.trim()); put("corrected_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun confirmAssetMoved(taskId:String,assetCode:String,finalAreaCode:String,operator:String,reason:String){
        val who=operator.trim();val why=reason.trim();require(who.isNotBlank()){ "请填写处理人" };require(why.isNotBlank()){ "请填写判断依据或处理原因" }
        val db=writableDatabase
        val areas=db.rawQuery("SELECT DISTINCT area_code FROM scan_event WHERE task_id=? AND asset_code=? AND duplicate_in_area=0 AND revoked_at IS NULL",arrayOf(taskId,assetCode)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        require(finalAreaCode in areas&&areas.size>1){"该资产当前不是未处理的跨区域重复"}
        db.update("scan_event",ContentValues().apply{put("revoked_at",System.currentTimeMillis());put("revoke_operator",who);put("revoke_reason","确认设备移动，最终区域为 $finalAreaCode：$why")},"task_id=? AND asset_code=? AND area_code<>? AND duplicate_in_area=0 AND revoked_at IS NULL",arrayOf(taskId,assetCode,finalAreaCode))
    }

    fun validCount(taskId: String, areaCode: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM scan_event WHERE task_id=? AND area_code=? AND duplicate_in_area=0 AND revoked_at IS NULL",
        arrayOf(taskId, areaCode)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun taskValidCount(taskId: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM scan_event WHERE task_id=? AND duplicate_in_area=0 AND revoked_at IS NULL",
        arrayOf(taskId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun taskSummary(taskId: String): TaskSummary {
        fun count(sql: String): Int = readableDatabase.rawQuery(sql, arrayOf(taskId)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val total = ledgerAssetCount(task(taskId).companyId)
        val scanned = count("SELECT COUNT(DISTINCT asset_code) FROM scan_event WHERE task_id=? AND duplicate_in_area=0 AND revoked_at IS NULL AND outside_ledger=0")
        return TaskSummary(
            total, scanned, (total - scanned).coerceAtLeast(0),
            count("SELECT COUNT(*) FROM scan_event WHERE task_id=? AND duplicate_in_area=0 AND revoked_at IS NULL AND outside_ledger=1"),
            count("SELECT COUNT(*) FROM scan_event WHERE task_id=? AND duplicate_in_area=0 AND revoked_at IS NULL AND COALESCE(anomaly_type,'')<>''"),
            crossRegionDuplicateCodes(taskId).size,
            count("SELECT COUNT(*) FROM scan_event WHERE task_id=? AND revoked_at IS NOT NULL")
        )
    }

    fun areaProgress(taskId: String): List<AreaProgress> = readableDatabase.rawQuery("""
        SELECT ta.area_code_snapshot,ta.area_name_snapshot,
          SUM(CASE WHEN s.duplicate_in_area=0 AND s.revoked_at IS NULL THEN 1 ELSE 0 END),
          SUM(CASE WHEN s.duplicate_in_area=0 AND s.revoked_at IS NULL AND s.outside_ledger=1 THEN 1 ELSE 0 END),
          SUM(CASE WHEN s.duplicate_in_area=0 AND s.revoked_at IS NULL AND COALESCE(s.anomaly_type,'')<>'' THEN 1 ELSE 0 END),
          GROUP_CONCAT(DISTINCT CASE WHEN s.revoked_at IS NULL THEN s.operator_name END), MAX(s.scanned_at)
        FROM task_area ta LEFT JOIN scan_event s ON s.task_id=ta.task_id AND s.area_code=ta.area_code_snapshot
        WHERE ta.task_id=? GROUP BY ta.area_code_snapshot,ta.area_name_snapshot ORDER BY ta.area_name_snapshot
    """.trimIndent(), arrayOf(taskId)).use { c -> buildList { while(c.moveToNext()) add(AreaProgress(
        c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),c.getInt(4),c.getString(5).orEmpty(),if(c.isNull(6)) null else c.getLong(6)
    )) } }

    fun missingLedgerAssets(taskId: String): List<ImportedAsset> {
        val scanned = readableDatabase.rawQuery("SELECT DISTINCT asset_code FROM scan_event WHERE task_id=? AND duplicate_in_area=0 AND revoked_at IS NULL AND outside_ledger=0", arrayOf(taskId))
            .use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
        return ledgerAssets(task(taskId).companyId).filterNot { it.code in scanned }
    }

    fun importLedger(companyId:String, parsed: ParsedLedger, operatorName: String): String {
        val db = writableDatabase
        val importId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.insertOrThrow("ledger_import", null, ContentValues().apply {
                put("id", importId); put("file_name", parsed.fileName); put("imported_at", now)
                put("operator_name", operatorName); put("valid_count", parsed.assets.size); put("issue_count", parsed.issues.size); put("company_id",companyId)
            })
            parsed.assets.forEach { asset ->
                db.insertWithOnConflict("asset_ledger", null, ContentValues().apply {
                    put("company_id",companyId); put("code", asset.code); put("name", asset.name); put("warehouse", asset.warehouse)
                    put("category", asset.category); put("status", asset.status); put("user_name", asset.user)
                    put("department", asset.department); put("source", asset.source); put("serial_number", asset.serialNumber)
                    put("asset_type", asset.assetType); put("amount", asset.amount); put("specification", asset.specification)
                    put("location", asset.location); put("purchase_date", asset.purchaseDate); put("supplier", asset.supplier)
                    put("note", asset.note); put("import_id", importId); put("updated_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            parsed.issues.forEach { issue ->
                db.insertOrThrow("ledger_issue", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("import_id", importId)
                    put("row_number", issue.row); put("raw_code", issue.rawCode); put("reason", issue.reason)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return importId
    }
    fun replaceLedger(companyId:String,parsed:ParsedLedger,operator:String):String{val db=writableDatabase;db.beginTransaction();return try{db.delete("asset_ledger","company_id=?",arrayOf(companyId));val id=importLedgerWithoutTransaction(db,companyId,parsed,operator);db.setTransactionSuccessful();id}finally{db.endTransaction()}}
    private fun importLedgerWithoutTransaction(db:SQLiteDatabase,companyId:String,parsed:ParsedLedger,operatorName:String):String{val id=UUID.randomUUID().toString();val now=System.currentTimeMillis();db.insertOrThrow("ledger_import",null,ContentValues().apply{put("id",id);put("file_name",parsed.fileName);put("imported_at",now);put("operator_name",operatorName);put("valid_count",parsed.assets.size);put("issue_count",parsed.issues.size);put("company_id",companyId)});parsed.assets.forEach{a->db.insertWithOnConflict("asset_ledger",null,ContentValues().apply{put("company_id",companyId);put("code",a.code);put("name",a.name);put("warehouse",a.warehouse);put("category",a.category);put("status",a.status);put("user_name",a.user);put("department",a.department);put("source",a.source);put("serial_number",a.serialNumber);put("asset_type",a.assetType);put("amount",a.amount);put("specification",a.specification);put("location",a.location);put("purchase_date",a.purchaseDate);put("supplier",a.supplier);put("note",a.note);put("import_id",id);put("updated_at",now)},SQLiteDatabase.CONFLICT_REPLACE)};return id}
    fun ledgerImports(companyId:String):List<LedgerImportRecord> = readableDatabase.rawQuery("SELECT id,file_name,imported_at,operator_name,valid_count,issue_count FROM ledger_import WHERE company_id=? ORDER BY imported_at DESC",arrayOf(companyId)).use{c->buildList{while(c.moveToNext())add(LedgerImportRecord(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getInt(4),c.getInt(5)))}}
    fun pendingNewAssets(taskId:String):List<ScanEventRecord> = scanRecords(taskId).filter{it.revokedAt==null&&!it.duplicateInArea&&it.outsideLedger}.distinctBy{it.assetCode}
    fun promoteNewAsset(taskId:String,record:ScanEventRecord,name:String,category:String,operator:String){require(operator.isNotBlank());val db=writableDatabase;val companyId=task(taskId).companyId;val importId="PROMOTE-${UUID.randomUUID()}";val now=System.currentTimeMillis();db.beginTransaction();try{db.insert("ledger_import",null,ContentValues().apply{put("id",importId);put("file_name","任务新增资产入账");put("imported_at",now);put("operator_name",operator);put("valid_count",1);put("issue_count",0);put("company_id",companyId)});db.insert("asset_ledger",null,ContentValues().apply{put("company_id",companyId);put("code",record.assetCode);put("name",name.trim());put("category",category.trim());put("source","盘点新增入账");put("location",record.areaName);put("note","来源任务扫码记录 ${record.id}");put("import_id",importId);put("updated_at",now)});db.setTransactionSuccessful()}finally{db.endTransaction()}}

    fun ledgerAssetCount(companyId:String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM asset_ledger WHERE company_id=?", arrayOf(companyId))
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun ledgerAssetCountAll(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM asset_ledger",null).use{if(it.moveToFirst())it.getInt(0)else 0}

    fun ledgerAssets(companyId:String, query: String = ""): List<ImportedAsset> {
        val keyword = query.trim()
        val where = if (keyword.isBlank()) "WHERE company_id=?" else "WHERE company_id=? AND (code LIKE ? OR name LIKE ? OR user_name LIKE ? OR department LIKE ? OR serial_number LIKE ?)"
        val args = if (keyword.isBlank()) arrayOf(companyId) else arrayOf(companyId,*Array(5) { "%$keyword%" })
        return readableDatabase.rawQuery(
            """SELECT code,name,warehouse,category,status,user_name,department,source,serial_number,
                asset_type,amount,specification,location,purchase_date,supplier,note
                FROM asset_ledger $where ORDER BY code COLLATE NOCASE LIMIT 1000""".trimIndent(), args
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ImportedAsset(
                    code = clean(cursor.getString(0)), name = clean(cursor.getString(1)),
                    warehouse = cursor.getString(2).orEmpty(), category = cursor.getString(3).orEmpty(),
                    status = cursor.getString(4).orEmpty(), user = cursor.getString(5).orEmpty(),
                    department = cursor.getString(6).orEmpty(), source = cursor.getString(7).orEmpty(),
                    serialNumber = cursor.getString(8).orEmpty(), assetType = cursor.getString(9).orEmpty(),
                    amount = cursor.getString(10).orEmpty(), specification = cursor.getString(11).orEmpty(),
                    location = cursor.getString(12).orEmpty(), purchaseDate = cursor.getString(13).orEmpty(),
                    supplier = clean(cursor.getString(14)), note = clean(cursor.getString(15))
                ))
            }
        }
    }
    private fun clean(v:String?)=v.orEmpty().let{if(it.equals("nan",true)||it.equals("null",true))"" else it}
}
