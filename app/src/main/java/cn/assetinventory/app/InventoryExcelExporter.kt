package cn.assetinventory.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object InventoryExcelExporter {
    private val time=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA)
    fun create(context:Context,db:InventoryDatabase,task:InventoryTask):File{
        val company=db.company(task.companyId);val records=db.scanRecords(task.id);val valid=records.filter{it.revokedAt==null&&!it.duplicateInArea};val missing=db.missingLedgerAssets(task.id);val summary=db.taskSummary(task.id);val areas=db.areaProgress(task.id)
        val sheets=linkedMapOf<String,List<List<Any?>>>()
        sheets["盘点汇总"]=listOf(listOf("公司名称",company.name),listOf("公司编号",company.code),listOf("盘点任务",task.name),listOf("导出时间",time.format(Date())),listOf("台账总数",summary.ledgerTotal),listOf("台账内已盘",summary.scannedLedger),listOf("台账内未盘",summary.missing),listOf("新增待入账",summary.newAssets),listOf("异常资产",summary.anomalies),listOf("跨区域重复",summary.duplicates),listOf("已撤销记录",summary.revoked), emptyList(),listOf("区域编号","区域名称","有效数量","新增待入账","异常数量","盘点人员","最近扫码时间"))+areas.map{listOf(it.code,it.name,it.valid,it.newAssets,it.anomalies,it.operators,it.lastScanAt?.let{x->time.format(Date(x))}.orEmpty())}
        val scanHeader=listOf("资产编号","区域编号","区域名称","盘点人员","扫码时间","台账状态","异常类型","异常备注","数据来源")
        sheets["全部有效记录"]=listOf(scanHeader)+valid.map{listOf(it.assetCode,it.areaCode,it.areaName,it.operatorName,time.format(Date(it.scannedAt)),if(it.outsideLedger)"新增待入账" else "台账内",it.anomalyType,it.anomalyNote,it.sourceDevice)}
        sheets["各区域明细"]=sheets["全部有效记录"]!!
        sheets["台账内未盘点"]=listOf(listOf("资产编号","资产名称","资产分类","规格型号","使用人","使用部门","存放地点"))+missing.map{listOf(it.code,it.name,it.category,it.specification,it.user,it.department,it.location)}
        sheets["新增待入账资产"]=listOf(scanHeader)+valid.filter{it.outsideLedger}.map{listOf(it.assetCode,it.areaCode,it.areaName,it.operatorName,time.format(Date(it.scannedAt)),"新增待入账",it.anomalyType,it.anomalyNote,it.sourceDevice)}
        sheets["异常资产"]=listOf(scanHeader+"照片数量")+valid.filter{it.anomalyType.isNotBlank()}.map{listOf(it.assetCode,it.areaCode,it.areaName,it.operatorName,time.format(Date(it.scannedAt)),if(it.outsideLedger)"新增待入账" else "台账内",it.anomalyType,it.anomalyNote,it.sourceDevice,db.anomalyPhotos(it.id).size)}
        sheets["跨区域重复"]=listOf(listOf("资产编号","出现区域","待处理状态"))+db.crossRegionDuplicateCodes(task.id).map{code->listOf(code,valid.filter{it.assetCode==code}.joinToString("、"){it.areaName},"待人工处理")}
        sheets["撤销与删除审计"]=listOf(listOf("资产编号","区域","原盘点人员","扫码时间","操作人","撤销原因","撤销时间"))+records.filter{it.revokedAt!=null}.map{listOf(it.assetCode,it.areaName,it.operatorName,time.format(Date(it.scannedAt)),it.revokeOperator,it.revokeReason,time.format(Date(it.revokedAt!!)))}
        sheets["人员及交接记录"]=listOf(listOf("区域","盘点人员","有效数量","最近扫码时间"))+areas.map{listOf(it.name,it.operators,it.valid,it.lastScanAt?.let{x->time.format(Date(x))}.orEmpty())}
        val dir=File(context.filesDir,"task_packages").apply{mkdirs()};val safe=(company.code+"_"+task.name).replace(Regex("[^A-Za-z0-9一-龥_-]"),"_");val file=File(dir,"${safe}_盘点总表_${System.currentTimeMillis()}.xlsx");writeXlsx(file,sheets);return file
    }
    private fun writeXlsx(file:File,sheets:LinkedHashMap<String,List<List<Any?>>>){ZipOutputStream(file.outputStream()).use{z->
        fun add(name:String,text:String){z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}
        add("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.keys.indices.joinToString(""){"<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"}}</Types>""")
        add("_rels/.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        add("xl/workbook.xml","""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.keys.mapIndexed{i,n->"<sheet name=\"${esc(n)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>"}.joinToString("")}</sheets></workbook>""")
        add("xl/_rels/workbook.xml.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.keys.indices.joinToString(""){"<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>"}}<Relationship Id="rId${sheets.size+1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        add("xl/styles.xml","""<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="10"/><name val="Microsoft YaHei"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="10"/><name val="Microsoft YaHei"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF075E54"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0" fillId="0" borderId="0" xfId="0"/><xf fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs></styleSheet>""")
        sheets.values.forEachIndexed{i,rows->add("xl/worksheets/sheet${i+1}.xml",sheetXml(rows))}
    }}
    private fun sheetXml(rows:List<List<Any?>>):String{val max=(rows.maxOfOrNull{it.size}?:1);return """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews><cols>${(1..max).joinToString(""){"<col min=\"$it\" max=\"$it\" width=\"${if(it==1)18 else 22}\" customWidth=\"1\"/>"}}</cols><sheetData>${rows.mapIndexed{r,row->"<row r=\"${r+1}\">"+row.mapIndexed{c,v->cell(c,r,v,r==0)}.joinToString("")+"</row>"}.joinToString("")}</sheetData><autoFilter ref="A1:${col(max-1)}${rows.size.coerceAtLeast(1)}"/></worksheet>"""}
    private fun cell(c:Int,r:Int,v:Any?,header:Boolean):String{val ref="${col(c)}${r+1}";return if(v is Number)"<c r=\"$ref\"${if(header)" s=\"1\"" else ""}><v>$v</v></c>" else "<c r=\"$ref\" t=\"inlineStr\"${if(header)" s=\"1\"" else ""}><is><t>${esc(v?.toString().orEmpty())}</t></is></c>"}
    private fun col(index:Int):String{var n=index+1;var s="";while(n>0){s=('A'+(n-1)%26)+s;n=(n-1)/26};return s}
    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
}
