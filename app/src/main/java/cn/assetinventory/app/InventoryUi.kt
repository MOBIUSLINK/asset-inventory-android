package cn.assetinventory.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import android.util.Size
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private val LocalOpenDrawer=staticCompositionLocalOf<()->Unit>{{}}
private val LocalCurrentCompany=staticCompositionLocalOf<Company?>{null}
private val LocalCompanies=staticCompositionLocalOf<List<Company>>{emptyList()}
private val LocalSwitchCompany=staticCompositionLocalOf<(Company)->Unit>{{}}
private val LocalAddCompany=staticCompositionLocalOf<()->Unit>{{}}
private val LocalManageCompany=staticCompositionLocalOf<(Company)->Unit>{{}}

private sealed interface Screen {
    data object Home : Screen
    data object SettingsHome : Screen
    data object CompanySetup : Screen
    data class ImportLedger(val company: Company) : Screen
    data class Ledger(val company: Company) : Screen
    data object ImportTask : Screen
    data object ImportResult : Screen
    data object FileTransfer : Screen
    data class ExportTaskFile(val task:InventoryTask):Screen
    data class ExportResultFile(val task:InventoryTask):Screen
    data class BatchQr(val company: Company) : Screen
    data object Backup : Screen
    data object ReceiveTaskQr : Screen
    data object TaskPreparation : Screen
    data class EditCompany(val company: Company) : Screen
    data class Areas(val company: Company) : Screen
    data class NewTask(val company: Company) : Screen
    data class TaskDetail(val task: InventoryTask) : Screen
    data class ScanRecords(val task: InventoryTask, val filter: RecordFilter = RecordFilter.ALL) : Screen
    data class Pending(val task: InventoryTask) : Screen
    data class Missing(val task: InventoryTask) : Screen
    data class Duplicates(val task: InventoryTask) : Screen
    data class Summary(val task: InventoryTask) : Screen
    data class DeliveryStatus(val task: InventoryTask) : Screen
    data class ExportTask(val task: InventoryTask) : Screen
    data class ExportResult(val task: InventoryTask) : Screen
    data class ExportExcel(val task: InventoryTask) : Screen
    data class Scanner(val task: InventoryTask, val operator: String, val sessionId: String) : Screen
}

private enum class RecordFilter { ALL, ANOMALY, NEW_ASSET }

@Composable
fun InventoryRoot(database: InventoryDatabase, voiceStatus: String, speak: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE) }
    var navigation by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    val screen = navigation.last()
    fun navigate(target: Screen) {
        if (navigation.last() != target) navigation = navigation + target
    }
    fun navigateRoot(target: Screen) {
        navigation = listOf(target)
    }
    fun goBack() {
        if (navigation.size > 1) navigation = navigation.dropLast(1)
    }
    var refresh by remember { mutableIntStateOf(0) }
    var lastBackAt by remember { mutableLongStateOf(0L) }
    val operator = prefs.getString("operator", "") ?: ""
    val companies = remember(refresh) { database.companies() }
    val tasks = remember(refresh) { database.tasks() }
    val preferredCompanyId=prefs.getString("current_company_id",null)
    val selectedCompany=companies.firstOrNull{it.id==preferredCompanyId}?:companies.firstOrNull()
    val activeTasks = tasks.filter { it.status == "进行中" && (selectedCompany==null||it.companyId==selectedCompany.id) }
    val preferredTaskId = prefs.getString("current_task_id", null)
    val selectedTask = activeTasks.firstOrNull { it.id == preferredTaskId } ?: activeTasks.firstOrNull()
    val drawerState=rememberDrawerState(DrawerValue.Closed);val scope=rememberCoroutineScope()

    BackHandler(enabled = screen == Screen.Home || screen == Screen.SettingsHome) {
        if (navigation.size > 1) {
            goBack()
        } else if (screen != Screen.Home) {
            navigateRoot(Screen.Home)
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000) (context as? ComponentActivity)?.finish()
            else {
                lastBackAt = now
                Toast.makeText(context, "再按一次退出应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun notify(message:String)=Toast.makeText(context,message,Toast.LENGTH_SHORT).show()
    CompositionLocalProvider(LocalOpenDrawer provides {scope.launch{drawerState.open()}},LocalCurrentCompany provides selectedCompany,LocalCompanies provides companies,LocalSwitchCompany provides {company->prefs.edit().putString("current_company_id",company.id).remove("current_task_id").apply();refresh++;navigateRoot(Screen.Home);notify("已切换到 ${company.name}")},LocalAddCompany provides {navigate(Screen.CompanySetup)},LocalManageCompany provides {navigate(Screen.EditCompany(it))}) {
    ModalNavigationDrawer(drawerState=drawerState,gesturesEnabled=screen==Screen.Home||screen==Screen.SettingsHome,drawerContent={ModalDrawerSheet{AppDrawer(operator,selectedCompany,onOperator={prefs.edit().putString("operator",it).apply();refresh++},onClose={scope.launch{drawerState.close()}},onAsset={scope.launch{drawerState.close()};selectedCompany?.let{navigate(Screen.Ledger(it))}?:navigate(Screen.CompanySetup)},onTasks={scope.launch{drawerState.close()};navigate(Screen.TaskPreparation)},onFileTransfer={scope.launch{drawerState.close()};navigate(Screen.FileTransfer)},onBackup={scope.launch{drawerState.close()};navigate(Screen.Backup)})}}){
    when (val current=screen) {
        Screen.Home -> HomeScreen(
            database = database,
            companies = companies,
            tasks = tasks,
            selectedTask = selectedTask,
            operator = operator,
            onSetup = { navigate(Screen.CompanySetup) },
            onReceiveTask = { navigate(Screen.ReceiveTaskQr) },
            onNewTask = { navigate(Screen.NewTask(it)) },
            onTask = { navigate(Screen.TaskDetail(it)) },
            onContinue = { task, name ->
                val sessionId = database.startSession(task.id, name)
                navigate(Screen.Scanner(task, name, sessionId))
            },
            onViewRecords = { navigate(Screen.ScanRecords(it)) },
            onPending = { navigate(Screen.Pending(it)) },
            onMissing = { navigate(Screen.Missing(it)) },
            onSettings = { navigate(Screen.SettingsHome) },
            onSummary={navigate(Screen.Summary(it))},
            onSelectTask = { prefs.edit().putString("current_task_id",it.id).apply(); refresh++ },
            onOperator = { prefs.edit().putString("operator", it).apply(); refresh++ }
        )
        Screen.TaskPreparation -> TaskPreparationScreen(selectedCompany,tasks,{goBack()},{navigate(Screen.Areas(it))},{navigate(Screen.NewTask(it))},{navigate(Screen.TaskDetail(it))})
        is Screen.EditCompany -> CompanyEditScreen(
            company=remember(refresh){database.company(current.company.id)},
            ledgerCount=remember(refresh){database.ledgerAssetCount(current.company.id)},
            areaCount=remember(refresh){database.areas(current.company.id).size},
            taskCount=remember(refresh){database.tasks().count{it.companyId==current.company.id}},
            defaultOperator=operator,
            onBack={goBack()},
            onSave={code,name,who,reason->runCatching{database.updateCompany(current.company.id,code,name,who,reason)}.onSuccess{prefs.edit().putString("operator",who.trim()).apply();refresh++;goBack();notify("公司资料已保存")}.onFailure{notify("保存失败：${it.message}")}},
            onDelete={who,reason->runCatching{val backup=InventoryBackup.create(context,database);database.deleteCompany(current.company.id,who,reason);backup.name}.onSuccess{prefs.edit().remove("current_company_id").remove("current_task_id").putString("operator",who.trim()).apply();refresh++;navigateRoot(Screen.Home);notify("公司已删除，保护性备份：$it")}.onFailure{notify("删除失败：${it.message}")}}
        )
        Screen.SettingsHome -> SettingsHomeScreen(
            company = selectedCompany,
            tasks = tasks.filter{it.companyId==selectedCompany?.id},
            onScan = { navigateRoot(Screen.Home) },
            onNewTask = { navigate(Screen.NewTask(it)) },
            onTask = { navigate(Screen.TaskDetail(it)) },
            onExportTask = { navigate(Screen.ExportTask(it)) },
            onExportResult = { navigate(Screen.ExportResult(it)) },
            onDeliveryStatus={navigate(Screen.DeliveryStatus(it))}
        )
        Screen.CompanySetup -> CompanySetupScreen(
            onBack = { goBack() },
            onSave = { code, name ->
                runCatching { database.createCompany(code, name) }.onSuccess {
                    prefs.edit().putString("current_company_id",it.id).apply();refresh++;navigate(Screen.Areas(it));notify("公司已创建，请添加盘点区域")
                }.onFailure{notify("创建公司失败：${it.message}")}
            }
        )
        is Screen.ImportLedger -> ImportLedgerScreen(database,current.company,operator,onBack={refresh++;goBack()},onDone={refresh++;navigateRoot(Screen.Ledger(current.company));notify("台账已导入，可以查询或打印标签")})
        is Screen.Ledger -> LedgerScreen(database,current.company,onBack={goBack()},onImport={navigate(Screen.ImportLedger(current.company))},onBatchPrint={navigate(Screen.BatchQr(current.company))})
        Screen.ImportTask -> ImportTaskPackageScreen(database,onBack={refresh++;goBack()},onDone={refresh++;navigateRoot(Screen.Home);notify("任务已导入，可以开始盘点")})
        Screen.ImportResult -> ImportResultPackageScreen(database,onBack={refresh++;goBack()},onDone={refresh++;goBack();notify("盘点结果已合并")})
        Screen.FileTransfer -> FileTransferScreen(tasks.filter{it.companyId==selectedCompany?.id},onBack={goBack()},onSendTask={navigate(Screen.ExportTaskFile(it))},onSendResult={navigate(Screen.ExportResultFile(it))},onImportTask={navigate(Screen.ImportTask)},onImportResult={navigate(Screen.ImportResult)})
        is Screen.ExportTaskFile -> ExportTaskFileScreen(database,current.task,onBack={goBack()})
        is Screen.ExportResultFile -> ExportResultFileScreen(database,current.task,onBack={goBack()})
        is Screen.BatchQr -> BatchQrScreen(database,current.company,onBack={goBack()})
        Screen.Backup -> BackupScreen(database,onBack={refresh++;goBack()})
        Screen.ReceiveTaskQr -> ReceiveTaskQrScreen(database,onBack={goBack()},onDone={refresh++;navigateRoot(Screen.Home);notify("盘点任务已导入，可以开始盘点")})
        is Screen.Areas -> AreasScreen(
            company = current.company,
            areas = remember(refresh) { database.areas(current.company.id) },
            onBack = { refresh++; goBack() },
            onAdd = { code, name -> database.addArea(current.company.id, code, name); refresh++;notify("区域已添加") },
            onUpdate = { area, code, name -> database.updateArea(area.id, code, name); refresh++;notify("区域资料已保存") },
            onDone={navigate(Screen.NewTask(current.company))}
        )
        is Screen.NewTask -> NewTaskScreen(current.company,database.areas(current.company.id),database.ledgerAssetCount(current.company.id),remember(refresh){database.suggestedTaskName(current.company.id)},
            onBack = { goBack() },
            onSave = { name ->
                runCatching { database.createTask(current.company.id, name) }.onSuccess {
                    prefs.edit().putString("current_task_id",it.id).apply();refresh++;navigateRoot(Screen.Home);notify("盘点任务已创建，请扫描区域二维码开始盘点")
                }.onFailure{notify("创建任务失败：${it.message}")}
            })
        is Screen.TaskDetail -> TaskDetailScreen(
            task = remember(refresh){database.task(current.task.id)},
            areas = remember(refresh) { database.taskAreas(current.task.id) },
            areaProgress = remember(refresh) { database.areaProgress(current.task.id) },
            count = remember(refresh) { database.taskValidCount(current.task.id) },
            operator = operator,
            onBack = { refresh++; goBack() },
            onRecords = { navigate(Screen.ScanRecords(current.task)) },
            duplicateCount = remember(refresh) { database.crossRegionDuplicateCodes(current.task.id).size },
            onDuplicates = { navigate(Screen.Duplicates(current.task)) },
            onExportExcel={navigate(Screen.ExportExcel(current.task))},
            completionCheck=remember(refresh){database.taskCompletionCheck(current.task.id)},onComplete={database.completeTask(current.task.id);prefs.edit().remove("current_task_id").apply();refresh++;notify("盘点任务已完成，可导出 Excel")},onArchive={database.archiveTask(current.task.id);prefs.edit().remove("current_task_id").apply();refresh++;notify("任务已归档，仍可查看和导出")},onReopen={op,reason->database.reopenTask(current.task.id,op,reason);prefs.edit().putString("current_task_id",current.task.id).apply();refresh++;notify("任务已重新开启")},
            onDelete={who,reason->runCatching{val backup=InventoryBackup.create(context,database);database.deleteTask(current.task.id,who,reason);backup.name}.onSuccess{prefs.edit().remove("current_task_id").putString("operator",who.trim()).apply();refresh++;navigateRoot(Screen.Home);notify("任务已删除，保护性备份：$it")}.onFailure{notify("删除失败：${it.message}")}}
        )
        is Screen.ScanRecords -> ScanRecordsScreen(database, current.task, operator, current.filter) {
            refresh++; goBack()
        }
        is Screen.Pending -> PendingIssuesScreen(
            database = database,
            task = current.task,
            onBack = { goBack() },
            onDuplicates = { navigate(Screen.Duplicates(current.task)) },
            onAnomalies = { navigate(Screen.ScanRecords(current.task, RecordFilter.ANOMALY)) },
            onNewAssets = { navigate(Screen.ScanRecords(current.task, RecordFilter.NEW_ASSET)) }
        )
        is Screen.Missing -> MissingAssetsScreen(database, current.task) { goBack() }
        is Screen.Duplicates -> DuplicateResolutionScreen(database, current.task, operator) {
            refresh++; goBack()
        }
        is Screen.Summary -> TaskSummaryScreen(database, current.task) { goBack() }
        is Screen.DeliveryStatus -> DeliveryStatusScreen(database,current.task){goBack()}
        is Screen.ExportTask -> ExportTaskPackageScreen(database,current.task) { goBack() }
        is Screen.ExportResult -> ExportResultPackageScreen(database,current.task) { goBack() }
        is Screen.ExportExcel -> ExportExcelScreen(database,current.task){goBack()}
        is Screen.Scanner -> ScannerScreen(database, current.task, current.operator, current.sessionId, voiceStatus, speak,onDataImported={refresh++}) { activeSessionId ->
            database.endSession(activeSessionId, "退出连续扫码")
            refresh++; goBack()
        }
    }} }
}

@Composable
private fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    Scaffold(topBar = {
        Surface(color = MaterialTheme.colorScheme.primary) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Text("←", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                else Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier=Modifier.padding(start=if(onBack!=null)2.dp else 0.dp))
            }
        }
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), content = content) }
}

private enum class MainDestination { Scan, Settings }

@Composable
private fun MainPage(title: String, current: MainDestination, onScan: () -> Unit,
                     onSettings: () -> Unit,showBottomBar:Boolean=true,centerContent:Boolean=false,content: @Composable ColumnScope.() -> Unit) {
    val openDrawer=LocalOpenDrawer.current;val company=LocalCurrentCompany.current;val companies=LocalCompanies.current;val switchCompany=LocalSwitchCompany.current
    val addCompany=LocalAddCompany.current;val manageCompany=LocalManageCompany.current;var chooseCompany by remember{mutableStateOf(false)}
    Scaffold(
        topBar = { Surface(color = MaterialTheme.colorScheme.primary) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick=openDrawer){Icon(Icons.Rounded.Menu,"打开侧边栏",tint=Color.White)}
                Box(Modifier.weight(1f)){Column(Modifier.fillMaxWidth().clickable{chooseCompany=true}){Text(company?.name?:"选择公司",color=Color.White,style=MaterialTheme.typography.titleMedium);Text(if(company==null)"点击创建或选择公司 ▼" else "点击切换公司 ▼",color=Color.White.copy(alpha=.78f),style=MaterialTheme.typography.labelSmall)}
                    DropdownMenu(expanded=chooseCompany,onDismissRequest={chooseCompany=false},modifier=Modifier.widthIn(min=230.dp)){companies.forEach{c->DropdownMenuItem(text={Column{Text(c.name);Text(c.code,style=MaterialTheme.typography.bodySmall)}},leadingIcon={if(c.id==company?.id)Icon(Icons.Rounded.Check,null)},onClick={chooseCompany=false;if(c.id!=company?.id)switchCompany(c)})};if(companies.isNotEmpty()){HorizontalDivider();company?.let{current->DropdownMenuItem(text={Text("管理当前公司")},leadingIcon={Icon(Icons.Rounded.Business,null)},onClick={chooseCompany=false;manageCompany(current)})}};DropdownMenuItem(text={Text("添加公司")},leadingIcon={Icon(Icons.Rounded.AddBusiness,null)},onClick={chooseCompany=false;addCompany()})}}
            }
        } },
        bottomBar={if(showBottomBar)NavigationBar{NavigationBarItem(current==MainDestination.Scan,{onScan()},icon={Icon(Icons.Rounded.QrCodeScanner,"盘点")},label={Text("盘点")});NavigationBarItem(current==MainDestination.Settings,{onSettings()},icon={Icon(Icons.Rounded.Send,"交付")},label={Text("交付")})}}
    ) { padding ->
        if(centerContent)Box(Modifier.fillMaxSize().padding(padding).padding(24.dp),contentAlignment=Alignment.Center){Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,content=content)}
        else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), content = content)
    }
}

@Composable
private fun AppDrawer(operator:String,company:Company?,onOperator:(String)->Unit,onClose:()->Unit,onAsset:()->Unit,onTasks:()->Unit,onFileTransfer:()->Unit,onBackup:()->Unit){
    var editOperator by remember{mutableStateOf(false)};var name by remember(operator){mutableStateOf(operator)}
    Column(Modifier.fillMaxHeight().width(310.dp).padding(vertical=18.dp)){
        Row(Modifier.fillMaxWidth().clickable{editOperator=true}.padding(20.dp),verticalAlignment=Alignment.CenterVertically){Surface(shape=RoundedCornerShape(40.dp),color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.size(54.dp)){Box(contentAlignment=Alignment.Center){Text(operator.takeLast(2).ifBlank{"未设"},style=MaterialTheme.typography.titleMedium)}};Column(Modifier.padding(start=14.dp)){Text(operator.ifBlank{"填写盘点人员"},style=MaterialTheme.typography.titleMedium);Text(company?.name?:"尚未创建公司",style=MaterialTheme.typography.bodySmall)}}
        HorizontalDivider();Text("工作资料",Modifier.padding(20.dp,16.dp,20.dp,6.dp),style=MaterialTheme.typography.labelLarge)
        NavigationDrawerItem(label={Text("资产台账")},icon={Icon(Icons.Rounded.Inventory2,null)},selected=false,onClick=onAsset,modifier=Modifier.padding(horizontal=12.dp))
        NavigationDrawerItem(label={Text("任务管理")},icon={Icon(Icons.Rounded.Business,null)},selected=false,onClick=onTasks,modifier=Modifier.padding(horizontal=12.dp))
        HorizontalDivider(Modifier.padding(vertical=8.dp));NavigationDrawerItem(label={Text("文件传输")},icon={Icon(Icons.Rounded.FolderOpen,null)},selected=false,onClick=onFileTransfer,modifier=Modifier.padding(horizontal=12.dp))
        Spacer(Modifier.weight(1f));HorizontalDivider();NavigationDrawerItem(label={Text("备份与恢复")},icon={Icon(Icons.Rounded.Backup,null)},selected=false,onClick=onBackup,modifier=Modifier.padding(horizontal=12.dp))
        Text("版本 ${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=14.dp))
    }
    if(editOperator)AlertDialog(onDismissRequest={editOperator=false},title={Text("当前盘点人员")},text={OutlinedTextField(name,{name=it},label={Text("姓名或工号")},singleLine=true)},confirmButton={Button(onClick={onOperator(name.trim());editOperator=false;onClose()},enabled=name.isNotBlank()){Text("确认")}},dismissButton={TextButton(onClick={editOperator=false}){Text("取消")}})
}

@Composable
private fun HomeScreen(database: InventoryDatabase, companies: List<Company>, tasks: List<InventoryTask>, operator: String,
                       selectedTask: InventoryTask?,
                       onSetup: () -> Unit, onReceiveTask: () -> Unit, onNewTask: (Company) -> Unit,
                       onTask: (InventoryTask) -> Unit, onContinue: (InventoryTask, String) -> Unit,
                       onViewRecords: (InventoryTask) -> Unit, onPending: (InventoryTask) -> Unit,
                       onMissing: (InventoryTask) -> Unit,
                       onSettings: () -> Unit, onSummary:(InventoryTask)->Unit,
                       onSelectTask: (InventoryTask) -> Unit,
                       onOperator: (String) -> Unit) {
    var showOperator by remember { mutableStateOf(operator.isBlank()) }
    var name by remember { mutableStateOf(operator) }
    val activeTask = selectedTask?.takeIf { it.status == "进行中" }
    val currentCompanyId=LocalCurrentCompany.current?.id
    val currentCompany=companies.firstOrNull{it.id==currentCompanyId}
    val companyTasks=tasks.filter{it.companyId==currentCompanyId}
    val activeCompanyTasks=companyTasks.filter{it.status=="进行中"}
    val recentTask = activeTask
    var chooseTask by remember { mutableStateOf(false) }
    val summary = recentTask?.let { remember(it.id) { database.taskSummary(it.id) } }
    val scannedCount = summary?.let { it.scannedLedger + it.newAssets } ?: 0
    val pendingCount = recentTask?.let { remember(it.id) { database.crossRegionDuplicateCodes(it.id).size } }?.plus(summary?.anomalies ?: 0)?.plus(summary?.newAssets ?: 0) ?: 0
    if(activeTask==null){
        MainPage("资产盘点",MainDestination.Scan,{},onSettings,showBottomBar=false,centerContent=true){
            Icon(Icons.Rounded.FactCheck,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(82.dp))
            Spacer(Modifier.height(22.dp))
            Text(if(currentCompany==null)"开始第一次资产盘点" else "开始一次新盘点",style=MaterialTheme.typography.headlineSmall)
            Text(if(currentCompany==null)"先设置公司和区域，之后即可扫描盘点" else "创建任务后即可扫描区域和资产二维码",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp,bottom=24.dp))
            Button(onClick={if(currentCompany==null)onSetup() else onNewTask(currentCompany)},modifier=Modifier.fillMaxWidth()){Icon(if(currentCompany==null)Icons.Rounded.Business else Icons.Rounded.AddTask,null);Spacer(Modifier.width(8.dp));Text(if(currentCompany==null)"设置公司并开始盘点" else "开始一次新盘点")}
            OutlinedButton(onClick=onReceiveTask,modifier=Modifier.fillMaxWidth().padding(top=10.dp)){Icon(Icons.Rounded.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text("扫码接收盘点任务")}
            companyTasks.firstOrNull()?.let{TextButton(onClick={onTask(it)},modifier=Modifier.padding(top=8.dp)){Text("查看历史盘点任务")}}
        }
    }else MainPage("盘点工作台", MainDestination.Scan, {}, onSettings) {
        if (activeTask != null) {
            val company = companies.firstOrNull { it.id == activeTask.companyId }
            Surface(Modifier.fillMaxWidth().clickable(enabled = activeCompanyTasks.size > 1) { chooseTask = true },
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("当前盘点任务", style = MaterialTheme.typography.labelLarge); Text("${company?.name.orEmpty()} · ${activeTask.name}") }
                    if (activeCompanyTasks.size > 1) Text("切换", color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        if(operator.isBlank()){
            Text("盘点人员", style = MaterialTheme.typography.labelLarge)
            Surface(Modifier.fillMaxWidth().padding(top = 6.dp).clickable { showOperator = true },shape = RoundedCornerShape(12.dp),color = MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("请填写姓名");Text("填写",color=MaterialTheme.colorScheme.primary)}}
            Spacer(Modifier.height(18.dp))
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp)) {
                Text("下一步", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                when {
                    operator.isBlank() -> {
                        Text("先填写当前盘点人员，之后每条扫描记录都会自动署名。", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(14.dp)); Button({ showOperator = true }, Modifier.fillMaxWidth()) { Text("填写盘点人员") }
                    }
                    activeTask != null -> {
                        Text(activeTask.name, style = MaterialTheme.typography.titleLarge)
                        Text("下一步扫描盘点区域二维码，进入区域后连续扫描资产。", Modifier.padding(top = 6.dp))
                        Text("盘点人员：$operator",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=4.dp))
                        Spacer(Modifier.height(14.dp)); Button({ onContinue(activeTask, operator) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text("开始扫码") }
                    }
                    else -> Unit
                }
            }
        }
        if (recentTask != null && scannedCount > 0) {
            Spacer(Modifier.height(18.dp))
            Text("当前进度", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultNumber("已盘点", scannedCount,Icons.Rounded.CheckCircle,Color(0xFF2E7D32), Modifier.weight(1f)) { onViewRecords(recentTask) }
                ResultNumber("未盘点", summary?.missing ?: 0,Icons.Rounded.PendingActions,MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f)) { onMissing(recentTask) }
                ResultNumber("待处理", pendingCount,Icons.Rounded.Warning,Color(0xFFEF6C00), Modifier.weight(1f)) { onPending(recentTask) }
            }
            Spacer(Modifier.height(16.dp));Text("检查与处理",style=MaterialTheme.typography.titleMedium)
            PurposeEntry("查看区域进度","按区域核对数量、盘点人员和最后扫描时间",icon=Icons.Rounded.LocationOn){onSummary(recentTask)}
        }
    }
    if (showOperator) AlertDialog(onDismissRequest = { if (operator.isNotBlank()) showOperator = false },
        title = { Text("当前盘点人员") }, text = { OutlinedTextField(name, { name = it }, label = { Text("姓名或工号") }, singleLine = true) },
        confirmButton = { Button({ onOperator(name.trim()); showOperator = false }, enabled = name.isNotBlank()) { Text("确认") } })
    if (chooseTask) AlertDialog(onDismissRequest={chooseTask=false},title={Text("切换本公司的盘点任务")},text={LazyColumn(Modifier.heightIn(max=520.dp)){items(activeCompanyTasks,key={it.id}){task->ListItem(headlineContent={Text(task.name)},supportingContent={Text("当前公司")},modifier=Modifier.clickable{onSelectTask(task);chooseTask=false});HorizontalDivider()}}},confirmButton={TextButton(onClick={chooseTask=false}){Text("取消")}})
}

@Composable
private fun ResultNumber(label: String, value: Int, icon:ImageVector, iconColor:Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick)) { Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon,null,tint=iconColor)
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text("查看明细", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    } }
}

@Composable
private fun PendingIssuesScreen(database: InventoryDatabase, task: InventoryTask, onBack: () -> Unit,
                                onDuplicates: () -> Unit, onAnomalies: () -> Unit, onNewAssets: () -> Unit) {
    val summary = remember(task.id) { database.taskSummary(task.id) }
    val duplicates = remember(task.id) { database.crossRegionDuplicateCodes(task.id).size }
    val total = duplicates + summary.anomalies + summary.newAssets
    Page("待处理问题", onBack) {
        Text("共 $total 项需要确认", style = MaterialTheme.typography.headlineSmall)
        Text("按问题类型进入对应明细，逐项核对和处理。", Modifier.padding(top = 6.dp, bottom = 16.dp))
        if (duplicates > 0) PurposeEntry("重复资产", "$duplicates 项 · 确认保留区域或修改资产编号",Icons.Rounded.ContentCopy,onDuplicates)
        if (summary.anomalies > 0) PurposeEntry("异常资产", "${summary.anomalies} 项 · 完善说明和现场照片",Icons.Rounded.Warning,onAnomalies)
        if (summary.newAssets > 0) PurposeEntry("新增待入账资产", "${summary.newAssets} 项 · 核对并转入正式台账",Icons.Rounded.AddCircle,onNewAssets)
        if (total == 0) EmptyState(Icons.Rounded.TaskAlt,"当前没有待处理问题","所有异常、重复和新增资产均已处理完成")
    }
}

@Composable
private fun PurposeEntry(title:String,description:String,onClick:()->Unit)=PurposeEntry(title,description,null,onClick)

@Composable
private fun PurposeEntry(title: String, description: String, icon:ImageVector?,onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description) },leadingContent=icon?.let{{Icon(it,null,tint=MaterialTheme.colorScheme.primary)}},
        trailingContent = { Icon(Icons.Rounded.ChevronRight,"进入",tint=MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick))
    HorizontalDivider()
}

@Composable
private fun SettingsHomeScreen(company:Company?, tasks: List<InventoryTask>,
                               onScan: () -> Unit, onNewTask: (Company) -> Unit,
                               onTask: (InventoryTask) -> Unit,
                               onExportTask: (InventoryTask) -> Unit, onExportResult: (InventoryTask) -> Unit,
                               onDeliveryStatus:(InventoryTask)->Unit) {
    val activeTask = tasks.firstOrNull { it.status == "进行中" }
    val recentClosedTask = tasks.firstOrNull { it.status == "已完成" || it.status == "已归档" }
    MainPage(if(activeTask==null)"资产盘点" else "任务交付", MainDestination.Settings, onScan, {},showBottomBar=activeTask!=null,centerContent=activeTask==null) {
        if (activeTask != null) {
            Text(activeTask.name, style = MaterialTheme.typography.titleLarge)
            Text("完成负责区域后，在这里发送或汇总盘点数据。", Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            Text("盘点人员交付", style = MaterialTheme.typography.titleMedium)
            PurposeEntry("发送我的盘点结果", "生成二维码，让主手机使用统一扫码接收",icon=Icons.Rounded.Send) { onExportResult(activeTask) }
            Spacer(Modifier.height(18.dp))
            Text("负责人汇总", style = MaterialTheme.typography.titleMedium)
            PurposeEntry("发任务给盘点人员", "选择区域后生成二维码，让对方统一扫码接收",icon=Icons.Rounded.Assignment) { onExportTask(activeTask) }
            PurposeEntry("查看区域交付情况", "核对各区域的本机盘点、回收状态和有效记录数",icon=Icons.Rounded.FactCheck) { onDeliveryStatus(activeTask) }
            PurposeEntry("完成任务并导出总表", "处理待确认问题后生成 Excel",icon=Icons.Rounded.TableView) { onTask(activeTask) }
        } else {
            Icon(Icons.Rounded.FactCheck,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(82.dp))
            Spacer(Modifier.height(22.dp))
            Text(if(company==null)"开始第一次资产盘点" else "开始一次新盘点", style = MaterialTheme.typography.headlineSmall)
            Text(if(company==null)"先设置公司和区域，之后即可扫描盘点" else "创建任务后即可扫描区域和资产二维码",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp,bottom=20.dp))
            company?.let{Button(onClick={onNewTask(it)},modifier=Modifier.fillMaxWidth()){Text("开始一次新盘点")}}
            recentClosedTask?.let{task->
                Spacer(Modifier.height(10.dp))
                TextButton(onClick={onTask(task)}){Text("查看历史盘点任务")}
            }
        }
    }
}

@Composable
private fun TaskPreparationScreen(company:Company?,tasks:List<InventoryTask>,onBack:()->Unit,onAreas:(Company)->Unit,onNewTask:(Company)->Unit,onTask:(InventoryTask)->Unit){
    var query by rememberSaveable(company?.id){mutableStateOf("")}
    var status by rememberSaveable(company?.id){mutableStateOf("全部")}
    Page("任务管理",onBack){
        if(company!=null){Text("${company.name} · ${company.code}",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(16.dp));PurposeEntry("创建盘点任务","使用当前公司的区域和台账",Icons.Rounded.AddTask){onNewTask(company)};PurposeEntry("盘点区域","管理区域名称和区域二维码",Icons.Rounded.LocationOn){onAreas(company)}
            val companyTasks=tasks.filter{it.companyId==company.id}
            if(companyTasks.isNotEmpty()){
                Spacer(Modifier.height(18.dp));Text("已有盘点任务",style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(top=8.dp),label={Text("搜索任务名称")},singleLine=true,leadingIcon={Icon(Icons.Rounded.Search,null)})
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical=10.dp)){
                    listOf("全部","进行中","已完成","已归档").forEachIndexed{i,item->SegmentedButton(selected=status==item,onClick={status=item},shape=SegmentedButtonDefaults.itemShape(i,4)){Text(item)}}
                }
                val visible=companyTasks.filter{(status=="全部"||it.status==status)&&(query.isBlank()||it.name.contains(query,true))}
                LazyColumn(Modifier.fillMaxWidth().weight(1f)){items(visible,key={it.id}){task->PurposeEntry(task.name,task.status,if(task.status=="进行中")Icons.Rounded.PendingActions else Icons.Rounded.AssignmentTurnedIn){onTask(task)}}}
            }
        }
    }
}

@Composable
private fun CompanyEditScreen(company:Company,ledgerCount:Int,areaCount:Int,taskCount:Int,defaultOperator:String,onBack:()->Unit,onSave:(String,String,String,String)->Unit,onDelete:(String,String)->Unit){
    var code by remember(company.id){mutableStateOf(company.code)}
    var name by remember(company.id){mutableStateOf(company.name)}
    var operator by remember(company.id){mutableStateOf(defaultOperator)}
    var reason by remember(company.id){mutableStateOf("")}
    var error by remember{mutableStateOf<String?>(null)}
    var confirmDiscard by remember{mutableStateOf(false)}
    var deleteStep by remember{mutableIntStateOf(0)}
    val changed=code.trim().uppercase()!=company.code||name.trim()!=company.name
    val dirty=changed||reason.isNotBlank()||(operator.isNotBlank()&&operator!=defaultOperator)
    val safeBack={if(dirty)confirmDiscard=true else onBack()}
    Page("修改公司资料",safeBack){
        Text("修改会应用到该公司的全部资料",style=MaterialTheme.typography.titleMedium)
        Text("现有 $ledgerCount 项资产、$areaCount 个区域、$taskCount 个盘点任务仍归属于同一公司。旧公司编号会保留，用于识别以前发出的任务包和结果包。",style=MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name,{name=it;error=null},Modifier.fillMaxWidth(),label={Text("公司名称")},singleLine=true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(code,{code=it.uppercase();error=null},Modifier.fillMaxWidth(),label={Text("公司编号")},supportingText={Text("修改后，新生成的文件使用新编号")},singleLine=true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(operator,{operator=it;error=null},Modifier.fillMaxWidth(),label={Text("操作人")},singleLine=true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(reason,{reason=it;error=null},Modifier.fillMaxWidth(),label={Text("修改原因")},minLines=2)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp))}
        Spacer(Modifier.height(18.dp))
        Button(onClick={runCatching{onSave(code,name,operator,reason)}.onFailure{error=it.message?:"修改失败"}},enabled=changed&&code.isNotBlank()&&name.isNotBlank()&&operator.isNotBlank()&&reason.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("保存修改")}
        Spacer(Modifier.height(28.dp));HorizontalDivider();Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick={deleteStep=1},colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),modifier=Modifier.fillMaxWidth()){Text("删除这家公司")}
    }
    DiscardChangesDialog(confirmDiscard,{confirmDiscard=false},{confirmDiscard=false;onBack()})
    if(deleteStep==1)AlertDialog(onDismissRequest={deleteStep=0},title={Text("删除公司会清除全部相关数据")},text={Text("将删除 $ledgerCount 项台账资产、$areaCount 个区域、$taskCount 个盘点任务，以及这些任务的扫码记录和现场照片。删除前会自动生成 .invbackup 保护性备份。")},confirmButton={Button(onClick={deleteStep=2},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("我已了解，继续")}},dismissButton={TextButton(onClick={deleteStep=0}){Text("取消")}})
    if(deleteStep==2)DeleteConfirmationDialog("公司",company.name,defaultOperator,{deleteStep=0},onDelete)
}

@Composable
private fun ImportLedgerScreen(database: InventoryDatabase, company: Company, operator: String, onBack: () -> Unit,onDone:()->Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var parsed by remember { mutableStateOf<ParsedLedger?>(null) }
    var message by remember { mutableStateOf("请选择资产台账 Excel 文件") }
    var busy by remember { mutableStateOf(false) }
    var replaceMode by remember{mutableStateOf(false)}
    var imported by remember{mutableStateOf(false)}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true; message = "正在读取台账…"
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { XlsxImporter.parse(context, uri) } }
                    .onSuccess { parsed = it; message = "读取完成" }
                    .onFailure { message = "读取失败：${it.message ?: "文件格式不受支持"}" }
                busy = false
            }
        }
    }
    Page("导入资产台账", onBack) {
        val scrollState = rememberScrollState()
        // The page can contain many repair fields; keep every abnormal row reachable.
        LaunchedEffect(parsed) { scrollState.scrollTo(0) }
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Text("导入到：${company.name}", style=MaterialTheme.typography.titleMedium)
        Text("支持 .xlsx 文件。系统会自动寻找包含“资产编码”表头的工作表。")
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(replaceMode,{replaceMode=it});Column{Text("替换 ${company.name} 的全部台账");Text("其他公司的台账不受影响；替换前自动生成 .invbackup 保护性备份",style=MaterialTheme.typography.bodySmall)}}
        Spacer(Modifier.height(14.dp))
        Button(onClick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在读取…" else "选择 Excel 文件") }
        Spacer(Modifier.height(14.dp))
        FeedbackBanner(message,feedbackKind(message,busy))
        parsed?.let { result ->
            val corrections = remember(result) {
                mutableStateMapOf<Int, String>().apply {
                    result.issues.forEach { put(it.row, it.rawCode.substringBefore('（').substringBefore('(').trim()) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(result.fileName, style = MaterialTheme.typography.titleMedium)
                    Text("可导入：${result.assets.size} 项")
                    Text("格式异常：${result.issues.size} 项", color = if (result.issues.isEmpty()) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                }
            }
            if (result.issues.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("修正异常编号", style = MaterialTheme.typography.titleSmall)
                Text("确认无误的编号会随本次台账一起导入；暂时无法确认的可以留空。", style = MaterialTheme.typography.bodySmall)
                val fixedCodes = corrections.values.map(XlsxImporter::normalizedCode)
                result.issues.forEach { issue ->
                    val value = corrections[issue.row].orEmpty()
                    val normalized = XlsxImporter.normalizedCode(value)
                    val duplicate = normalized.isNotBlank() &&
                        (result.assets.any { it.code == normalized } || fixedCodes.count { it == normalized } > 1)
                    val valid = value.isBlank() || (XlsxImporter.isValidCode(value) && !duplicate)
                    Spacer(Modifier.height(10.dp))
                    Text("第${issue.row}行 · 原值：${issue.rawCode}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = value,
                        onValueChange = { corrections[issue.row] = it.uppercase() },
                        label = { Text("修正后的资产编号") },
                        supportingText = {
                            Text(when {
                                value.isBlank() -> "留空：本次暂不导入"
                                duplicate -> "该编号与本次台账中的其他资产重复"
                                !XlsxImporter.isValidCode(value) -> "格式仍不正确，例如 DP0001、BJB030、DP0001-1"
                                else -> "格式正确，可以导入"
                            })
                        },
                        isError = !valid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                val occupied = result.assets.mapTo(mutableSetOf()) { it.code }
                val repaired = mutableListOf<ImportedAsset>()
                val remaining = mutableListOf<ImportIssue>()
                result.issues.forEach { issue ->
                    val code = XlsxImporter.normalizedCode(corrections[issue.row].orEmpty())
                    if (XlsxImporter.isValidCode(code) && occupied.add(code)) {
                        repaired += issue.asset.copy(code = code)
                    } else {
                        remaining += issue
                    }
                }
                val ready = result.copy(assets = result.assets + repaired, issues = remaining)
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { if(replaceMode){InventoryBackup.create(context,database);database.replaceLedger(company.id,ready,operator.ifBlank{"未填写"})}else database.importLedger(company.id,ready, operator.ifBlank { "未填写" }) } }
                        .onSuccess { message = "导入成功：${ready.assets.size} 项资产，${ready.issues.size} 项留待处理";parsed=null;imported=true }
                        .onFailure { message = "导入失败：${it.message}" }
                    busy = false
                }
            }, enabled = !busy && result.assets.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("确认导入有效资产") }
        }
        if(imported){Spacer(Modifier.height(12.dp));Button(onClick=onDone,modifier=Modifier.fillMaxWidth()){Text("完成并查看资产台账")}}
        }
    }
}

@Composable
private fun LedgerScreen(database: InventoryDatabase, company: Company, onBack: () -> Unit,onImport:()->Unit,onBatchPrint:()->Unit) {
    val context=LocalContext.current
    var query by rememberSaveable(company.id) { mutableStateOf("") }
    var selected by remember { mutableStateOf<ImportedAsset?>(null) }
    var qrAsset by remember { mutableStateOf<ImportedAsset?>(null) }
    var printAsset by remember { mutableStateOf<ImportedAsset?>(null) }
    var history by remember{mutableStateOf(false)}
    val assets = remember(query) { database.ledgerAssets(company.id,query) }
    Page("${company.name} · 资产台账", onBack) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick=onImport,modifier=Modifier.weight(1f)){Icon(Icons.Rounded.UploadFile,null);Spacer(Modifier.width(6.dp));Text(if(database.ledgerAssetCount(company.id)==0)"导入台账" else "更新台账")}
            OutlinedButton(onClick=onBatchPrint,modifier=Modifier.weight(1f)){Icon(Icons.Rounded.Print,null);Spacer(Modifier.width(6.dp));Text("批量打印")}
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索资产编号、名称、使用人或部门") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick={history=true}){Text("查看导入历史")}
        Text(if (query.isBlank()) "共 ${database.ledgerAssetCount(company.id)} 项，按编号排列" else "找到 ${assets.size} 项")
        Spacer(Modifier.height(8.dp))
        if (assets.isEmpty()) {
            Text("没有找到匹配的资产", Modifier.padding(top = 20.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(assets, key = { it.code }) { asset ->
                    ListItem(
                        headlineContent = { Text(asset.code, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            val summary = listOf(asset.name, asset.specification, asset.user, asset.department)
                                .filter { it.isNotBlank() }.take(2).joinToString(" · ")
                            Text(summary.ifBlank { "暂无补充资料" })
                        },leadingContent={Icon(assetSemanticIcon(asset),assetSemanticLabel(asset),tint=MaterialTheme.colorScheme.primary)},
                        modifier = Modifier.clickable { selected = asset }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    selected?.let { asset ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(asset.code) },
            text = {
                val fields = listOf(
                    "资产名称" to asset.name, "资产分类" to asset.category, "资产状态" to asset.status,
                    "规格型号" to asset.specification, "SN 编码" to asset.serialNumber,
                    "使用人" to asset.user, "使用部门" to asset.department,
                    "所属仓库" to asset.warehouse, "存放地点" to asset.location,
                    "资产类型" to asset.assetType, "来源" to asset.source, "金额" to asset.amount,
                    "购入时间" to asset.purchaseDate, "供应商" to asset.supplier, "备注" to asset.note
                ).filter { it.second.isNotBlank() }
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(fields) { (label, value) ->
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(value, Modifier.padding(bottom = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } },
            dismissButton = { TextButton(onClick={qrAsset=asset;selected=null}){Text("二维码与打印")} }
        )
    }
    qrAsset?.let{asset->val bitmap=remember(asset.code){QrGenerator.bitmap(asset.code,900)}
        Dialog(onDismissRequest={qrAsset=null}){Surface(shape=RoundedCornerShape(16.dp),color=Color.White){Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(asset.code,style=MaterialTheme.typography.headlineSmall,color=Color.Black);Text("扫码内容：ASSET:${asset.code}",color=Color.DarkGray);Spacer(Modifier.height(12.dp));androidx.compose.foundation.Image(bitmap.asImageBitmap(),"资产二维码",Modifier.fillMaxWidth().aspectRatio(1f));Row{TextButton(onClick={printAsset=asset;qrAsset=null}){Text("B3S_P 打印")};TextButton(onClick={val file=QrGenerator.save(context,asset.code);val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"分享资产二维码"))}){Text("分享图片")};TextButton(onClick={qrAsset=null}){Text("关闭")}}}}}
    }
    printAsset?.let{asset->NiimbotPrintDialog(listOf(QrLabel("ASSET:${asset.code}",asset.code,asset.name,"资产"))){printAsset=null}}
    if(history)AlertDialog(onDismissRequest={history=false},title={Text("${company.name} · 台账导入历史")},text={val fmt=remember{SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA)};LazyColumn(Modifier.heightIn(max=560.dp)){items(database.ledgerImports(company.id),key={it.id}){r->ListItem(headlineContent={Text(r.fileName)},supportingContent={Text("${fmt.format(Date(r.importedAt))} · ${r.operator}\n有效 ${r.valid} · 异常 ${r.issues}")});HorizontalDivider()}}},confirmButton={TextButton(onClick={history=false}){Text("关闭")}})
}

private fun assetSemanticText(asset:ImportedAsset)=listOf(asset.name,asset.category,asset.assetType,asset.specification).joinToString(" ").lowercase()
private fun assetSemanticIcon(asset:ImportedAsset):ImageVector=when{
    listOf("笔记本","notebook","laptop").any{it in assetSemanticText(asset)}->Icons.Rounded.LaptopMac
    listOf("台式机","桌面电脑","desktop").any{it in assetSemanticText(asset)}->Icons.Rounded.Computer
    listOf("显示器","monitor","屏幕").any{it in assetSemanticText(asset)}->Icons.Rounded.DesktopWindows
    listOf("打印机","printer").any{it in assetSemanticText(asset)}->Icons.Rounded.Print
    listOf("手机","电话","phone").any{it in assetSemanticText(asset)}->Icons.Rounded.Smartphone
    listOf("平板","tablet").any{it in assetSemanticText(asset)}->Icons.Rounded.TabletMac
    listOf("服务器","server").any{it in assetSemanticText(asset)}->Icons.Rounded.Dns
    listOf("相机","摄像机","camera").any{it in assetSemanticText(asset)}->Icons.Rounded.PhotoCamera
    else->Icons.Rounded.Inventory2
}
private fun assetSemanticLabel(asset:ImportedAsset)=when(assetSemanticIcon(asset)){
    Icons.Rounded.LaptopMac->"笔记本电脑";Icons.Rounded.Computer->"台式电脑";Icons.Rounded.DesktopWindows->"显示器";Icons.Rounded.Print->"打印机";Icons.Rounded.Smartphone->"手机";Icons.Rounded.TabletMac->"平板电脑";Icons.Rounded.Dns->"服务器";Icons.Rounded.PhotoCamera->"相机";else->"资产"
}

@Composable
private fun CompanySetupScreen(onBack: () -> Unit, onSave: (String, String) -> Unit) {
    var code by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
    var confirmDiscard by remember{mutableStateOf(false)}
    val safeBack={if(code.isNotBlank()||name.isNotBlank())confirmDiscard=true else onBack()}
    Page("创建公司", safeBack) {
        Text("公司资料只需创建一次，之后的盘点任务会直接复用。")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { name = it }, label = { Text("公司名称") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(code, { code = it.uppercase() }, label = { Text("公司编号") },
            supportingText = { Text("建议使用公司英文名称或英文缩写，例如 DIANPING、DP") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(18.dp))
        Button(onClick = { onSave(code, name) }, enabled = code.isNotBlank() && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存并添加区域") }
    }
    DiscardChangesDialog(confirmDiscard,{confirmDiscard=false},{confirmDiscard=false;onBack()})
}

@Composable
private fun AreasScreen(company: Company, areas: List<Area>, onBack: () -> Unit,
                        onAdd: (String, String) -> Unit, onUpdate: (Area, String, String) -> Unit,onDone:()->Unit) {
    var code by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Area?>(null) }
    var qrArea by remember { mutableStateOf<Area?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember{mutableStateOf(false)}
    val validCode = Regex("^[A-Z0-9_-]{2,32}$")
    val safeBack={if(code.isNotBlank()||name.isNotBlank())confirmDiscard=true else onBack()}
    Page("${company.name} · 区域", safeBack) {
        Text("区域二维码格式：AREA:区域编号，例如 AREA:ITOPERATION")
        Text("编号只能包含字母、数字、横线或下划线，不能有空格。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(code, { code = it.uppercase() }, label = { Text("区域编号，如 XZB001") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(name, { name = it }, label = { Text("区域名称，如 行政部") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            runCatching { onAdd(code, name) }
                .onSuccess { code = ""; name = ""; error = null }
                .onFailure { error = "无法添加：区域编号可能已经存在" }
        }, enabled = validCode.matches(code) && name.isNotBlank()) { Text("添加区域") }
        if (code.isNotBlank() && !validCode.matches(code)) Text("编号格式不正确，不能包含空格或中文", color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(18.dp))
        Text("已有区域（${areas.size}）", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) { items(areas,key={it.id}) { area ->
            ListItem(headlineContent = { Text(area.name) },supportingContent = { Text("${area.code} · AREA:${area.code}") },leadingContent={Icon(Icons.Rounded.LocationOn,"区域",tint=MaterialTheme.colorScheme.primary)},trailingContent = { Row { TextButton(onClick={qrArea=area}){Text("二维码")};TextButton(onClick = { editing = area }) { Text("编辑") } } })
            HorizontalDivider()
        } }
        if(areas.isNotEmpty()){Spacer(Modifier.height(10.dp));Button(onClick=onDone,modifier=Modifier.fillMaxWidth()){Text("区域添加完成，创建盘点任务")}}
    }
    DiscardChangesDialog(confirmDiscard,{confirmDiscard=false},{confirmDiscard=false;onBack()})
    editing?.let { area ->
        var editCode by remember(area.id) { mutableStateOf(area.code) }
        var editName by remember(area.id) { mutableStateOf(area.name) }
        var editError by remember(area.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑区域") },
            text = {
                Column {
                    OutlinedTextField(editCode, { editCode = it.uppercase() }, label = { Text("区域编号") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(editName, { editName = it }, label = { Text("区域名称") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("二维码将使用 AREA:${editCode.ifBlank { "区域编号" }}")
                    Text("修改会同步到进行中的任务；已完成的历史任务保持原值。", style = MaterialTheme.typography.bodySmall)
                    if (editCode.isNotBlank() && !validCode.matches(editCode)) {
                        Text("编号只能使用字母、数字、横线或下划线，不能有空格", color = MaterialTheme.colorScheme.error)
                    }
                    editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    runCatching { onUpdate(area, editCode, editName) }
                        .onSuccess { editing = null }
                        .onFailure { editError = "保存失败：该编号可能已被其他区域使用" }
                }, enabled = validCode.matches(editCode) && editName.isNotBlank()) { Text("保存修改") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
    qrArea?.let{area->QrDisplayDialog(title=area.name,code=area.code,payload="AREA:${area.code}",onClose={qrArea=null})}
}

@Composable
private fun QrDisplayDialog(title:String,code:String,payload:String,onClose:()->Unit){
    val context=LocalContext.current;val bitmap=remember(payload){QrGenerator.bitmapForPayload(payload,900)}
    Dialog(onDismissRequest=onClose){Surface(shape=RoundedCornerShape(16.dp),color=Color.White){Column(Modifier.padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(title,style=MaterialTheme.typography.titleLarge,color=Color.Black);Text(code,color=Color.Black);Text("扫码内容：$payload",color=Color.DarkGray);Spacer(Modifier.height(10.dp));androidx.compose.foundation.Image(bitmap.asImageBitmap(),"二维码",Modifier.fillMaxWidth().aspectRatio(1f));Row{TextButton(onClick={val dir=File(context.filesDir,"generated_qr").apply{mkdirs()};val file=File(dir,"${payload.substringBefore(':').lowercase()}_${code}_QR.png");java.io.FileOutputStream(file).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"分享二维码"))}){Text("分享图片")};TextButton(onClick=onClose){Text("关闭")}}}}}
}

@Composable
private fun NewTaskScreen(company: Company, areas: List<Area>, ledgerCount:Int,suggestedName:String,onBack: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(suggestedName) { mutableStateOf(suggestedName) }
    var confirmDiscard by remember{mutableStateOf(false)}
    val safeBack={if(name!=suggestedName)confirmDiscard=true else onBack()}
    Page("创建盘点任务", safeBack) {
        Text("公司：${company.name}")
        Text("将自动包含当前 ${areas.size} 个有效区域，无需重新填写。")
        if(ledgerCount==0){Spacer(Modifier.height(10.dp));FeedbackBanner("当前公司尚未导入资产台账。仍可创建任务，但扫描到的资产都会标记为“新增待入账资产”。",FeedbackKind.WARNING)}
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { name = it }, label = { Text("任务名称（可选修改）") },supportingText={Text("系统已按公司和日期自动生成；同日重复会自动添加序号")}, modifier = Modifier.fillMaxWidth(),singleLine=true)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSave(name) }, enabled = areas.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("创建并开始盘点") }
        if (areas.isEmpty()) Text("请先返回并至少添加一个区域", color = MaterialTheme.colorScheme.error)
    }
    DiscardChangesDialog(confirmDiscard,{confirmDiscard=false},{confirmDiscard=false;onBack()})
}

@Composable
private fun TaskDetailScreen(task: InventoryTask, areas: List<Area>, areaProgress:List<AreaProgress>,count: Int, operator: String,
                             onBack: () -> Unit, onRecords: () -> Unit,
                             duplicateCount: Int, onDuplicates: () -> Unit,onExportExcel:()->Unit,
                             completionCheck:TaskCompletionCheck,onComplete:()->Unit,onArchive:()->Unit,onReopen:(String,String)->Unit,onDelete:(String,String)->Unit) {
                             
    var confirmComplete by remember{mutableStateOf(false)}
    var reopen by remember{mutableStateOf(false)}
    var deleteStep by remember{mutableIntStateOf(0)}
    Page(task.name, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TaskMetric(Icons.Rounded.TaskAlt,task.status,"状态",Modifier.weight(1f))
            TaskMetric(Icons.Rounded.LocationOn,"${areas.size} 个","区域",Modifier.weight(1f))
            TaskMetric(Icons.Rounded.QrCodeScanner,"$count 项","已盘点",Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Text("查看任务数据",style=MaterialTheme.typography.titleMedium)
        PurposeEntry("盘点记录","查看扫码、异常、撤销和新增资产记录",Icons.Rounded.ReceiptLong,onRecords)
        if(duplicateCount>0)PurposeEntry("处理重复资产","$duplicateCount 个跨区域重复编号需要确认",Icons.Rounded.ContentCopy,onDuplicates)
        Spacer(Modifier.height(18.dp))
        Text("任务完成检查", style = MaterialTheme.typography.titleMedium)
        when(task.status){
            "进行中"->Button(onClick={confirmComplete=true},enabled=completionCheck.canComplete,modifier=Modifier.fillMaxWidth()){Text("完成盘点任务")}
            "已完成"->{Button(onClick=onExportExcel,modifier=Modifier.fillMaxWidth()){Text("导出 Excel 盘点总表")};Spacer(Modifier.height(10.dp));OutlinedButton(onClick=onArchive,modifier=Modifier.fillMaxWidth()){Text("归档任务")};TextButton(onClick={reopen=true}){Text("重新开启任务")}}
            "已归档"->{Button(onClick=onExportExcel,modifier=Modifier.fillMaxWidth()){Text("导出 Excel 盘点总表")};Spacer(Modifier.height(10.dp));TextButton(onClick={reopen=true}){Text("重新开启归档任务")}}
        }
        if(!completionCheck.canComplete) {
            Text("暂时无法完成", color=MaterialTheme.colorScheme.error, style=MaterialTheme.typography.titleMedium)
            Text("请返回“检查结果”处理：重复资产 ${completionCheck.duplicateCount} 项，异常信息不完整 ${completionCheck.incompleteAnomalies} 项")
        }
        Spacer(Modifier.height(18.dp))
        Text("任务区域与进度", style = MaterialTheme.typography.titleMedium)
        areaProgress.forEach { area ->
            ListItem(headlineContent={Text(area.name)},supportingContent={Column{Text("${area.code} · 有效 ${area.valid} · 新增 ${area.newAssets} · 异常 ${area.anomalies}");Text("盘点人员：${area.operators.ifBlank{"尚未开始"}}",style=MaterialTheme.typography.bodySmall)}},leadingContent={Icon(if(area.valid>0)Icons.Rounded.TaskAlt else Icons.Rounded.LocationOn,"区域进度",tint=if(area.valid>0)Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary)})
            HorizontalDivider()
        }
        Spacer(Modifier.height(24.dp));HorizontalDivider();Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick={deleteStep=1},colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),modifier=Modifier.fillMaxWidth()){Text("删除此任务")}
        Spacer(Modifier.height(24.dp))
        }
    }
    if(confirmComplete)AlertDialog(onDismissRequest={confirmComplete=false},title={Text("确认完成任务？")},text={Text("完成后将停止扫码和结果合并，但仍可查看并导出数据。")},confirmButton={Button(onClick={confirmComplete=false;onComplete()}){Text("确认完成")}},dismissButton={TextButton(onClick={confirmComplete=false}){Text("取消")}})
    if(reopen){var op by remember{mutableStateOf(operator)};var reason by remember{mutableStateOf("")};AlertDialog(onDismissRequest={reopen=false},title={Text("重新开启任务")},text={Column{OutlinedTextField(op,{op=it},label={Text("操作人")});OutlinedTextField(reason,{reason=it},label={Text("重新开启原因")})}},confirmButton={Button(onClick={onReopen(op,reason);reopen=false},enabled=op.isNotBlank()&&reason.isNotBlank()){Text("确认重新开启")}},dismissButton={TextButton(onClick={reopen=false}){Text("取消")}})}
    if(deleteStep==1)AlertDialog(onDismissRequest={deleteStep=0},title={Text("删除盘点任务？")},text={Text("任务中的 $count 条有效盘点数据、扫码历史和现场照片将一并删除。删除前会自动生成 .invbackup 保护性备份。")},confirmButton={Button(onClick={deleteStep=2},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("我已了解，继续")}},dismissButton={TextButton(onClick={deleteStep=0}){Text("取消")}})
    if(deleteStep==2)DeleteConfirmationDialog("任务",task.name,operator,{deleteStep=0},onDelete)
}

@Composable
private fun DeleteConfirmationDialog(type:String,targetName:String,defaultOperator:String,onCancel:()->Unit,onConfirm:(String,String)->Unit){
    var typed by remember(targetName){mutableStateOf("")};var operator by remember{mutableStateOf(defaultOperator)};var reason by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onCancel,title={Text("最后确认删除$type")},text={Column{Text("请输入完整名称“$targetName”以确认。此操作不能在 App 内撤销。");Spacer(Modifier.height(10.dp));OutlinedTextField(typed,{typed=it},label={Text("输入${type}名称")},singleLine=true);Spacer(Modifier.height(8.dp));OutlinedTextField(operator,{operator=it},label={Text("操作人")},singleLine=true);Spacer(Modifier.height(8.dp));OutlinedTextField(reason,{reason=it},label={Text("删除原因")},minLines=2)}},confirmButton={Button(onClick={onConfirm(operator,reason);onCancel()},enabled=typed==targetName&&operator.isNotBlank()&&reason.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("永久删除")}},dismissButton={TextButton(onClick=onCancel){Text("取消")}})
}

@Composable
private fun TaskMetric(icon:ImageVector,value:String,label:String,modifier:Modifier=Modifier){
    Surface(modifier=modifier,shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.58f)){
        Column(Modifier.padding(vertical=12.dp,horizontal=6.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(24.dp));Spacer(Modifier.height(5.dp))
            Text(value,style=MaterialTheme.typography.titleMedium,maxLines=1);Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeliveryStatusScreen(database:InventoryDatabase,task:InventoryTask,onBack:()->Unit){
    val rows=remember(task.id){database.areaDeliveryStatuses(task.id)}
    val fmt=remember{SimpleDateFormat("MM-dd HH:mm",Locale.CHINA)}
    Page("区域交付情况",onBack){
        Text(task.name,style=MaterialTheme.typography.titleMedium)
        Text("记录数用于辅助核对是否齐全；“已收到”只代表已经导入过该区域结果。",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)){items(rows,key={it.area.id}){row->
            ListItem(headlineContent={Text(row.area.name)},leadingContent={Icon(if(row.status==null)Icons.Rounded.HourglassEmpty else Icons.Rounded.TaskAlt,"交付状态",tint=if(row.status==null)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)},supportingContent={Text(buildString{append(row.area.code);append("\n主手机有效 ${row.localCount} · 收到有效 ${row.receivedCount}");row.resultReceivedAt?.let{append(" · 最近接收 ${fmt.format(Date(it))}")}})},trailingContent={Text(row.status?:"尚未回收",color=if(row.status=="尚未回收")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)})
            HorizontalDivider()
        }}
    }
}

private enum class SummaryDetail { NONE, SCANNED, MISSING, NEW, ANOMALY, DUPLICATE, REVOKED }

@Composable
private fun BatchQrScreen(database:InventoryDatabase,company:Company,onBack:()->Unit){
    val context=LocalContext.current;var mode by remember{mutableStateOf("资产")};var query by remember{mutableStateOf("")};val selected=remember{mutableStateMapOf<String,Boolean>()};var message by remember{mutableStateOf("请选择需要生成的项目")};var niimbotLabels by remember{mutableStateOf<List<QrLabel>?>(null)}
    val assets=remember(query){database.ledgerAssets(company.id,query)};val areas=remember(query){database.areas(company.id).filter{query.isBlank()||it.code.contains(query,true)||it.name.contains(query,true)}}
    val labels=if(mode=="资产")assets.map{QrLabel("ASSET:${it.code}",it.code,it.name,"资产")}else areas.map{QrLabel("AREA:${it.code}",it.code,it.name,"区域")}
    Page("批量二维码",onBack){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(mode=="资产",{mode="资产";query=""},label={Text("资产")});FilterChip(mode=="区域",{mode="区域";query=""},label={Text("区域")})};OutlinedTextField(query,{query=it},label={Text("搜索编号或名称")},singleLine=true,modifier=Modifier.fillMaxWidth());Row(verticalAlignment=Alignment.CenterVertically){Checkbox(labels.isNotEmpty()&&labels.all{selected[it.payload]==true},{checked->labels.forEach{selected[it.payload]=checked}});Text("全选当前结果（${labels.size}）")};Text("已选择 ${selected.count{it.value}} 项 · $message",style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)){items(labels,key={it.payload}){label->Row(Modifier.fillMaxWidth().clickable{selected[label.payload]=selected[label.payload]!=true},verticalAlignment=Alignment.CenterVertically){Checkbox(selected[label.payload]==true,{selected[label.payload]=it});Column{Text(label.code);Text(label.name.ifBlank{label.type},style=MaterialTheme.typography.bodySmall)}};HorizontalDivider()}}
        val allLabels=remember{database.ledgerAssets(company.id).map{QrLabel("ASSET:${it.code}",it.code,it.name,"资产")}+database.areas(company.id).map{QrLabel("AREA:${it.code}",it.code,it.name,"区域")}}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={runCatching{QrLabelPdf.create(context,allLabels.filter{selected[it.payload]==true})}.onSuccess{file->val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"分享二维码PDF"));message="PDF已生成"}.onFailure{message=it.message?:"生成失败"}},enabled=selected.any{it.value},modifier=Modifier.weight(1f)){Text("分享PDF")};Button(onClick={runCatching{QrLabelPdf.create(context,allLabels.filter{selected[it.payload]==true})}.onSuccess{file->context.getSystemService(PrintManager::class.java).print("资产盘点二维码",PdfPrintAdapter(file),PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build());message="已打开系统打印"}.onFailure{message=it.message?:"生成失败"}},enabled=selected.any{it.value},modifier=Modifier.weight(1f)){Text("系统打印")}}
        Spacer(Modifier.height(8.dp));Button(onClick={niimbotLabels=allLabels.filter{selected[it.payload]==true}},enabled=selected.any{it.value},modifier=Modifier.fillMaxWidth()){Text("NIIMBOT B3S_P 打印 · T40×15")}
    }
    niimbotLabels?.let{NiimbotPrintDialog(it,onDismiss={niimbotLabels=null})}
}

@Composable
private fun BackupScreen(database:InventoryDatabase,onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var message by remember{mutableStateOf("备份包含公司、区域、台账、任务、扫描、审计和异常照片")};var preview by remember{mutableStateOf<BackupPreview?>(null)};var confirmRestore by remember{mutableStateOf(false)};var restoring by remember{mutableStateOf(false)};var refresh by remember{mutableIntStateOf(0)};var selectedFile by remember{mutableStateOf<File?>(null)};var selectedPreview by remember{mutableStateOf<BackupPreview?>(null)};var deleteFile by remember{mutableStateOf<File?>(null)};var pendingSave by remember{mutableStateOf<File?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->if(uri!=null)scope.launch{runCatching{withContext(Dispatchers.IO){InventoryBackup.inspect(context,uri)}}.onSuccess{preview=it;message="备份校验通过"}.onFailure{message="读取失败：${it.message}"}}}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri:Uri?->val source=pendingSave;if(uri!=null&&source!=null)runCatching{context.contentResolver.openOutputStream(uri)?.use{out->source.inputStream().use{it.copyTo(out)}}?:error("无法写入所选位置")}.onSuccess{message="备份已保存到你选择的位置"}.onFailure{message="保存失败：${it.message}"};pendingSave=null}
    fun share(file:File){val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/octet-stream";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"分享完整备份（.invbackup）"))}
    val backups=remember(refresh){InventoryBackup.files(context)}
    fun inspect(file:File){scope.launch{message="正在校验并读取备份…";runCatching{withContext(Dispatchers.IO){InventoryBackup.inspect(context,FileProvider.getUriForFile(context,"${context.packageName}.files",file))}}.onSuccess{selectedFile=file;selectedPreview=it;message="备份校验通过"}.onFailure{message="读取失败：${it.message}"}}}
    Page("备份与恢复",onBack){Text(message);Spacer(Modifier.height(14.dp));Button(onClick={runCatching{InventoryBackup.create(context,database)}.onSuccess{file->refresh++;inspect(file);message="保护性备份已生成"}.onFailure{message="备份失败：${it.message}"}},modifier=Modifier.fillMaxWidth()){Text("生成保护性备份")};Spacer(Modifier.height(10.dp));OutlinedButton(onClick={picker.launch(arrayOf("application/octet-stream","application/zip","*/*"))},modifier=Modifier.fillMaxWidth()){Text("从其他位置选择备份")};Spacer(Modifier.height(18.dp));Text("App 内保存的备份（${backups.size}）",style=MaterialTheme.typography.titleMedium);Text("实际目录：${File(context.filesDir,"task_packages").absolutePath}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("如需在文件管理器中查找，请使用“另存为”选择下载目录。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(8.dp));if(backups.isEmpty())Text("暂无保护性备份") else LazyColumn(Modifier.fillMaxWidth().weight(1f)){items(backups,key={it.absolutePath}){file->ListItem(headlineContent={Text(file.name)},supportingContent={Text("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(Date(file.lastModified()))} · ${"%.1f".format(file.length()/1024.0/1024.0)} MB")},trailingContent={TextButton(onClick={inspect(file)}){Text("预览")}});Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)){TextButton(onClick={share(file)}){Text("分享")};TextButton(onClick={pendingSave=file;saver.launch(file.name)}){Text("另存为")};TextButton(onClick={deleteFile=file},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("删除")}};HorizontalDivider()}};selectedFile?.let{file->selectedPreview?.let{p->AlertDialog(onDismissRequest={selectedFile=null;selectedPreview=null},title={Text("备份内容预览")},text={Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState())){Text(file.name,style=MaterialTheme.typography.titleSmall);Text("生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(Date(p.createdAt))}");Spacer(Modifier.height(8.dp));Text("公司 ${p.companies} · 任务 ${p.tasks}");Text("台账 ${p.assets} · 扫码 ${p.scans} · 照片 ${p.photos}");BackupDetailLists(p);Spacer(Modifier.height(8.dp));Text("大小：${"%.1f".format(file.length()/1024.0/1024.0)} MB");Text("路径：${file.absolutePath}",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button(onClick={preview=p;selectedFile=null;selectedPreview=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("准备恢复")}},dismissButton={Row{TextButton(onClick={share(file)}){Text("分享")};TextButton(onClick={selectedFile=null;selectedPreview=null}){Text("关闭")}}})}};preview?.let{p->Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("待恢复的备份内容",style=MaterialTheme.typography.titleMedium);Text("公司 ${p.companies} · 任务 ${p.tasks}");Text("台账 ${p.assets} · 扫码 ${p.scans} · 照片 ${p.photos}");Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(Date(p.createdAt)))}};Spacer(Modifier.height(10.dp));Button(onClick={confirmRestore=true},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),modifier=Modifier.fillMaxWidth()){Text("用此备份替换当前全部数据")}}
    }
    deleteFile?.let{file->AlertDialog(onDismissRequest={deleteFile=null},title={Text("删除这份备份？")},text={Text("将永久删除 ${file.name}。如果尚未另存或分享，删除后将无法用于恢复。")},confirmButton={Button(onClick={if(file.delete()){message="备份已删除";refresh++}else message="备份删除失败";deleteFile=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("确认删除")}},dismissButton={TextButton(onClick={deleteFile=null}){Text("取消")}})}
    if(confirmRestore)AlertDialog(onDismissRequest={if(!restoring)confirmRestore=false},title={Text("确认恢复完整备份？")},text={Text(if(restoring)"正在备份恢复前的当前数据，请勿关闭 App…" else "恢复前会先自动生成一份当前数据的保护性备份。只有备份成功后才会替换数据并重启；若备份失败，恢复将自动中止。")},confirmButton={Button(onClick={val target=preview?:return@Button;restoring=true;scope.launch{runCatching{withContext(Dispatchers.IO){val protection=InventoryBackup.create(context,database);InventoryBackup.scheduleRestore(context,target);protection}}.onSuccess{confirmRestore=false;restoring=false;(context as? ComponentActivity)?.recreate()}.onFailure{restoring=false;confirmRestore=false;message="恢复已中止：无法生成恢复前保护性备份（${it.message}）"}}},enabled=!restoring){Text(if(restoring)"正在保护当前数据…" else "备份当前数据并恢复")}},dismissButton={TextButton(onClick={confirmRestore=false},enabled=!restoring){Text("取消")}})
}

@Composable
private fun BackupDetailLists(preview:BackupPreview){
    Spacer(Modifier.height(10.dp));Text("公司明细",style=MaterialTheme.typography.titleSmall)
    preview.companyDetails.forEach{Text("• ${it.name}（${it.code}）")}
    Spacer(Modifier.height(8.dp));Text("盘点任务",style=MaterialTheme.typography.titleSmall)
    if(preview.taskDetails.isEmpty())Text("暂无盘点任务") else preview.taskDetails.forEach{Text("• ${it.name}\n  ${it.companyName} · ${it.status}")}
}

@Composable
private fun EmptyState(icon:ImageVector,title:String,description:String,actionLabel:String?=null,onAction:(()->Unit)?=null){
    Column(Modifier.fillMaxWidth().padding(vertical=28.dp,horizontal=12.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Surface(shape=RoundedCornerShape(40.dp),color=MaterialTheme.colorScheme.surfaceVariant){Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(18.dp).size(38.dp))}
        Spacer(Modifier.height(14.dp));Text(title,style=MaterialTheme.typography.titleMedium);Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp))
        if(actionLabel!=null&&onAction!=null){Spacer(Modifier.height(14.dp));Button(onClick=onAction){Text(actionLabel)}}
    }
}

@Composable
private fun DiscardChangesDialog(visible:Boolean,onKeep:()->Unit,onDiscard:()->Unit){
    if(visible) AlertDialog(onDismissRequest=onKeep,title={Text("放弃未保存的内容？")},text={Text("当前填写的内容尚未保存，返回后需要重新填写。")},confirmButton={Button(onClick=onDiscard,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("放弃并返回")}},dismissButton={TextButton(onClick=onKeep){Text("继续编辑")}})
}

private enum class FeedbackKind{INFO,LOADING,SUCCESS,WARNING,ERROR}

@Composable
private fun FeedbackBanner(message:String,kind:FeedbackKind){
    val color=when(kind){FeedbackKind.INFO->MaterialTheme.colorScheme.surfaceVariant;FeedbackKind.LOADING->MaterialTheme.colorScheme.primaryContainer;FeedbackKind.SUCCESS->Color(0xFFC8E6C9);FeedbackKind.WARNING->Color(0xFFFFE0B2);FeedbackKind.ERROR->Color(0xFFFFCDD2)}
    val icon=when(kind){FeedbackKind.INFO->Icons.Rounded.Info;FeedbackKind.LOADING->Icons.Rounded.Sync;FeedbackKind.SUCCESS->Icons.Rounded.CheckCircle;FeedbackKind.WARNING->Icons.Rounded.Warning;FeedbackKind.ERROR->Icons.Rounded.Error}
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=color){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){if(kind==FeedbackKind.LOADING)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp) else Icon(icon,null,modifier=Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)}}
}

private fun feedbackKind(message:String,busy:Boolean=false)=when{busy->FeedbackKind.LOADING;message.contains("失败")||message.contains("不能")||message.contains("异常")->FeedbackKind.ERROR;message.contains("成功")||message.contains("完成")||message.contains("通过")||message.contains("已生成")||message.contains("已合并")->FeedbackKind.SUCCESS;else->FeedbackKind.INFO}

@Composable
private fun NearbyTransferDialog(file:File,type:String,onClose:()->Unit){
    val context=LocalContext.current;val activity=context as? ComponentActivity;var generation by remember{mutableIntStateOf(0)};var server by remember(file,generation){mutableStateOf<LocalPackageServer?>(null)};var message by remember(file,generation){mutableStateOf("正在启动面对面扫码发送…")};var completed by remember{mutableStateOf(false)};var observedIp by remember{mutableStateOf(localIpv4Address(context)?.hostAddress)}
    DisposableEffect(file,generation){val window=activity?.window;val old=window?.attributes?.screenBrightness?:-1f;window?.attributes=window?.attributes?.apply{screenBrightness=1f};onDispose{server?.close();window?.attributes=window?.attributes?.apply{screenBrightness=old}}}
    LaunchedEffect(file,generation){completed=false;runCatching{LocalPackageServer(context,file,type)}.onSuccess{server=it;message="等待对方手机扫码…"}.onFailure{message="无法开始面对面扫码发送：${it.message}"}}
    val latestServer by rememberUpdatedState(server);val latestCompleted by rememberUpdatedState(completed);val latestObservedIp by rememberUpdatedState(observedIp)
    LaunchedEffect(Unit){while(true){delay(500);val current=localIpv4Address(context)?.hostAddress;if(current!=latestObservedIp){observedIp=current;latestServer?.close();server=null;completed=false;if(current==null)message="Wi-Fi 已断开，请连接 Wi-Fi 或开启手机热点" else {message="网络已变化，正在生成新的连接码…";generation++}}else if(current!=null&&latestServer==null&&!latestCompleted){generation++}}}
    LaunchedEffect(server){val watched=server;while(watched?.isActive==true){delay(300)};if(watched?.wasDownloaded==true){completed=true;message="接收完成，连接已自动失效"}}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onClose){Icon(Icons.Rounded.ArrowBack,"返回")};Text(if(type=="task")"面对面扫码发送任务" else "面对面扫码发送结果",style=MaterialTheme.typography.titleLarge)};Spacer(Modifier.weight(.3f));if(completed){Icon(Icons.Rounded.CheckCircle,"完成",tint=Color(0xFF2E7D32),modifier=Modifier.size(88.dp));Text("接收完成",style=MaterialTheme.typography.headlineMedium);Text(message);Spacer(Modifier.height(18.dp));Button(onClick={server?.close();server=null;generation++}){Text("重新生成连接码")}}else{server?.link?.let{link->Text("请让接收手机扫描",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(12.dp));BoxWithConstraints(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){val size=minOf(maxWidth,maxHeight);val bitmap=remember(link.payload()){QrGenerator.bitmapForPayload(link.payload(),1100)};androidx.compose.foundation.Image(bitmap.asImageBitmap(),"连接二维码",Modifier.size(size).background(Color.White).padding(6.dp))};Text("核对码  ${link.code}",style=MaterialTheme.typography.headlineMedium);Text(message,color=MaterialTheme.colorScheme.primary)}?:Column(Modifier.weight(1f),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){if(observedIp==null){Icon(Icons.Rounded.WifiOff,"Wi-Fi 已断开",modifier=Modifier.size(72.dp),tint=MaterialTheme.colorScheme.error);Spacer(Modifier.height(16.dp));Text("Wi-Fi 已断开",style=MaterialTheme.typography.headlineSmall);Text("连接 Wi-Fi 后将自动生成新的二维码",style=MaterialTheme.typography.bodyMedium)}else{CircularProgressIndicator();Spacer(Modifier.height(16.dp));Text(message)}}};Spacer(Modifier.weight(.15f));Text("两台手机需连接同一 Wi-Fi，或连接其中一台手机开启的热点。",style=MaterialTheme.typography.bodySmall);Text("文件只能下载一次；返回后连接立即失效。",style=MaterialTheme.typography.bodySmall)}}
    }
}

@Composable
private fun ReceiveTaskQrScreen(database:InventoryDatabase,onBack:()->Unit,onDone:()->Unit){
    var link by remember{mutableStateOf<LocalTransferLink?>(null)};var message by remember{mutableStateOf("请扫描发送手机显示的任务二维码")};var lastRaw by remember{mutableStateOf("")};var lastAt by remember{mutableLongStateOf(0L)}
    Page("扫码接收盘点任务",onBack){
        Text("两台手机需连接同一 Wi-Fi，或连接其中一台手机开启的热点。",style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(10.dp));FeedbackBanner(message,FeedbackKind.INFO);Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().weight(1f)){CameraScanner(onReady={},onError={message="识别异常：$it"}){raw->if(link==null){val now=System.currentTimeMillis();if(raw!=lastRaw||now-lastAt>2500){lastRaw=raw;lastAt=now;if(!raw.trim().startsWith("INVTRANSFER:"))message="这不是任务传输二维码。请扫描发送手机显示的二维码" else runCatching{LocalTransferLink.parse(raw.trim())}.onSuccess{if(it.type=="task"){link=it;message="检测到盘点任务，请核对并导入"}else message="这是盘点结果二维码。请先导入对应任务，再在盘点页扫码接收结果"}.onFailure{message="任务二维码无效：${it.message}"}}}}}
    }
    link?.let{TransferReceiveDialog(database,it,onDismiss={link=null;lastRaw="";message="请扫描发送手机显示的任务二维码"},onImported=onDone)}
}

@Composable
private fun TransferReceiveDialog(database:InventoryDatabase,link:LocalTransferLink,onDismiss:()->Unit,onImported:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var code by remember(link.token){mutableStateOf("")};var busy by remember(link.token){mutableStateOf(true)};var message by remember(link.token){mutableStateOf("正在下载并校验传输数据…")};var taskPkg by remember(link.token){mutableStateOf<OfflineTaskPackage?>(null)};var resultPkg by remember(link.token){mutableStateOf<ReadResultPackage?>(null)}
    LaunchedEffect(link.token){runCatching{withContext(Dispatchers.IO){val file=LocalPackageReceiver.download(context,link);if(link.type=="task")TaskPackageCodec.read(context,LocalPackageReceiver.uri(context,file)) else ResultPackageCodec.read(context,LocalPackageReceiver.uri(context,file))}}.onSuccess{if(it is OfflineTaskPackage)taskPkg=it else resultPkg=it as ReadResultPackage;message="下载和完整性校验通过"}.onFailure{message="接收失败：${it.message}"};busy=false}
    val resultPreview=resultPkg?.let{runCatching{database.resultImportPreview(it.data)}.getOrNull()}
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth().padding(18.dp),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface){Column(Modifier.padding(20.dp).heightIn(max=650.dp).verticalScroll(rememberScrollState())){
            Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.WifiTethering,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(10.dp));Text(if(link.type=="task")"接收盘点任务" else "接收盘点结果",style=MaterialTheme.typography.titleLarge)}
            Text(link.name,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));FeedbackBanner(message,if(busy)FeedbackKind.LOADING else if(taskPkg!=null||resultPkg!=null)FeedbackKind.SUCCESS else FeedbackKind.ERROR)
            taskPkg?.let{p->Spacer(Modifier.height(12.dp));Text(p.company.name,style=MaterialTheme.typography.titleMedium);Text("任务：${p.task.name}");Text("区域：${p.areas.joinToString("、"){it.name}}");Text("台账：${p.assets.size} 项")}
            resultPreview?.let{p->Spacer(Modifier.height(12.dp));Text(p.company.name,style=MaterialTheme.typography.titleMedium);Text("任务：${p.task.name}");Text("记录：${p.total} 条 · 新增 ${p.newCount} · 已存在 ${p.existingCount}");Text("人员：${p.operators.joinToString("、").ifBlank{"未填写"}}")}
            if(!busy&&(taskPkg!=null||resultPkg!=null)){Spacer(Modifier.height(14.dp));OutlinedTextField(code,{code=it.filter(Char::isDigit).take(4)},label={Text("输入发送手机显示的四位核对码")},supportingText={Text("发送端核对码应与此处输入完全一致")},singleLine=true,modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(10.dp));Button(onClick={runCatching{taskPkg?.let{database.importTaskPackage(it)}?:resultPkg?.let{database.importResultPackage(it.data,it.extractedPhotos)}?:error("没有可导入的数据")}.onSuccess{onImported();onDismiss()}.onFailure{message="导入失败：${it.message}"}},enabled=code==link.code&&(taskPkg!=null||resultPreview?.newCount?.let{it>0}==true),modifier=Modifier.fillMaxWidth()){Text("核对并导入")}}
            Spacer(Modifier.height(6.dp));TextButton(onClick=onDismiss,enabled=!busy,modifier=Modifier.align(Alignment.End)){Text("取消")}
        }}
    }
}

@Composable
private fun ExportTaskPackageScreen(database: InventoryDatabase, task: InventoryTask, onBack:()->Unit) {
    val context=LocalContext.current
    val areas=remember{database.taskAreas(task.id)}
    val selected=remember{mutableStateMapOf<String,Boolean>().apply{areas.forEach{put(it.id,true)}}}
    var message by remember{mutableStateOf("选择要分配给这台工作手机的区域")};var file by remember{mutableStateOf<File?>(null)};var nearby by remember{mutableStateOf(false)}
    Page("发送盘点任务",onBack){
        Text(task.name,style=MaterialTheme.typography.titleMedium);Text(message)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={areas.forEach{selected[it.id]=true}}){Text("全选")};TextButton(onClick={areas.forEach{selected[it.id]=false}}){Text("清空")}}
        LazyColumn(Modifier.fillMaxWidth().weight(1f)){items(areas,key={it.id}){area->Row(Modifier.fillMaxWidth().clickable{selected[area.id]=selected[area.id]!=true},verticalAlignment=Alignment.CenterVertically){Checkbox(selected[area.id]==true,{selected[area.id]=it});Column{Text(area.name);Text(area.code,style=MaterialTheme.typography.bodySmall)}}}}
        Spacer(Modifier.height(14.dp))
        if(file==null)Button(onClick={runCatching{TaskPackageCodec.create(context,database,task,areas.filter{selected[it.id]==true})}.onSuccess{file=it;nearby=true;message="任务二维码已生成"}.onFailure{message="生成失败：${it.message}"}},enabled=selected.values.any{it},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.QrCode2,null);Spacer(Modifier.width(8.dp));Text("确认区域并生成发送二维码")}
        file?.let{f->if(!nearby){Spacer(Modifier.height(12.dp));Button(onClick={nearby=true},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.QrCode2,null);Spacer(Modifier.width(8.dp));Text("重新显示发送二维码")}};if(nearby)NearbyTransferDialog(f,"task"){nearby=false}}
        Text("任务包包含 ${database.ledgerAssetCount(task.companyId)} 项本公司台账及所选区域，不包含主手机已有盘点记录。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=10.dp))
    }
}

@Composable
private fun ImportTaskPackageScreen(database:InventoryDatabase,onBack:()->Unit,onDone:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var preview by remember{mutableStateOf<OfflineTaskPackage?>(null)};var message by remember{mutableStateOf("请选择从飞书、QQ或文件管理器收到的 .invtask 盘点任务包")};var busy by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->if(uri!=null){busy=true;scope.launch{runCatching{withContext(Dispatchers.IO){TaskPackageCodec.read(context,uri)}}.onSuccess{preview=it;message="任务包校验通过"}.onFailure{message="读取失败：${it.message}"};busy=false}}}
    Page("导入离线任务包",onBack){
        FeedbackBanner(message,feedbackKind(message,busy));Spacer(Modifier.height(12.dp));Button(onClick={picker.launch(arrayOf("application/octet-stream","application/zip","*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在校验…" else "选择 .invtask 任务包")}
        preview?.let{pkg->Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(pkg.task.name,style=MaterialTheme.typography.titleMedium);Text("公司：${pkg.company.name}");Text("分配区域：${pkg.areas.joinToString("、"){it.name}}");Text("携带台账：${pkg.assets.size} 项");Text("任务包版本：${pkg.version}")}}
            Spacer(Modifier.height(12.dp));Button(onClick={runCatching{database.importTaskPackage(pkg)}.onSuccess{message="导入成功";preview=null;onDone()}.onFailure{message="导入失败：${it.message}"}},modifier=Modifier.fillMaxWidth()){Text("确认导入并开始盘点")}
        }
    }
}

@Composable
private fun FileTransferScreen(tasks:List<InventoryTask>,onBack:()->Unit,onSendTask:(InventoryTask)->Unit,onSendResult:(InventoryTask)->Unit,onImportTask:()->Unit,onImportResult:()->Unit){
    var selected by remember(tasks){mutableStateOf(tasks.firstOrNull{it.status=="进行中"}?:tasks.firstOrNull())};var choosing by remember{mutableStateOf(false)}
    Page("文件传输",onBack){
        FeedbackBanner("通过飞书、QQ、邮件或文件管理器发送和接收盘点文件；面对面时也可以直接扫码传输。",FeedbackKind.INFO)
        Spacer(Modifier.height(16.dp));Text("发送文件",style=MaterialTheme.typography.titleMedium)
        if(selected==null)Text("当前公司没有可发送的盘点任务",color=MaterialTheme.colorScheme.onSurfaceVariant) else {
            PurposeEntry("当前任务：${selected?.name}","${selected?.status} · 点击切换任务",Icons.Rounded.SwapHoriz){choosing=true}
            PurposeEntry("发送盘点任务文件","选择区域并通过其他应用发送 .invtask",Icons.Rounded.UploadFile){selected?.let(onSendTask)}
            PurposeEntry("发送盘点结果文件","通过其他应用发送 .invresult",Icons.Rounded.Send){selected?.let(onSendResult)}
        }
        Spacer(Modifier.height(18.dp));Text("接收文件",style=MaterialTheme.typography.titleMedium)
        PurposeEntry("导入盘点任务文件","选择 .invtask 文件，校验后导入公司、区域和台账",Icons.Rounded.Assignment,onImportTask)
        PurposeEntry("导入盘点结果文件","选择 .invresult 文件，校验后合并扫码和异常照片",Icons.Rounded.MoveToInbox,onImportResult)
    }
    if(choosing)AlertDialog(onDismissRequest={choosing=false},title={Text("选择盘点任务")},text={LazyColumn(Modifier.heightIn(max=520.dp)){items(tasks,key={it.id}){task->ListItem(headlineContent={Text(task.name)},supportingContent={Text(task.status)},leadingContent={if(task.id==selected?.id)Icon(Icons.Rounded.Check,null)},modifier=Modifier.clickable{selected=task;choosing=false});HorizontalDivider()}}},confirmButton={TextButton(onClick={choosing=false}){Text("取消")}})
}

@Composable
private fun ExportTaskFileScreen(database:InventoryDatabase,task:InventoryTask,onBack:()->Unit){
    val context=LocalContext.current;val areas=remember(task.id){database.taskAreas(task.id)};val selected=remember{mutableStateMapOf<String,Boolean>().apply{areas.forEach{put(it.id,true)}}};var message by remember{mutableStateOf("选择要包含在任务文件中的区域")}
    Page("发送任务文件",onBack){Text(task.name,style=MaterialTheme.typography.titleMedium);Text(message);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={areas.forEach{selected[it.id]=true}}){Text("全选")};TextButton(onClick={areas.forEach{selected[it.id]=false}}){Text("清空")}};LazyColumn(Modifier.fillMaxWidth().weight(1f)){items(areas,key={it.id}){area->Row(Modifier.fillMaxWidth().clickable{selected[area.id]=selected[area.id]!=true},verticalAlignment=Alignment.CenterVertically){Checkbox(selected[area.id]==true,{selected[area.id]=it});Column{Text(area.name);Text(area.code,style=MaterialTheme.typography.bodySmall)}}}};Button(onClick={runCatching{TaskPackageCodec.create(context,database,task,areas.filter{selected[it.id]==true})}.onSuccess{file->shareInventoryFile(context,file,"盘点任务文件：${task.name}");message="任务文件已生成"}.onFailure{message="生成失败：${it.message}"}},enabled=selected.any{it.value},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Share,null);Spacer(Modifier.width(8.dp));Text("生成并发送 .invtask")}}
}

@Composable
private fun ExportResultFileScreen(database:InventoryDatabase,task:InventoryTask,onBack:()->Unit){
    val context=LocalContext.current;var message by remember{mutableStateOf("结果文件包含扫码、撤销、异常、人员记录和现场照片")}
    Page("发送结果文件",onBack){Text(task.name,style=MaterialTheme.typography.titleMedium);Text(message);Spacer(Modifier.height(16.dp));Button(onClick={runCatching{ResultPackageCodec.create(context,database,task)}.onSuccess{file->shareInventoryFile(context,file,"盘点结果文件：${task.name}");message="结果文件已生成"}.onFailure{message="生成失败：${it.message}"}},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Share,null);Spacer(Modifier.width(8.dp));Text("生成并发送 .invresult")}}
}

private fun shareInventoryFile(context:Context,file:File,subject:String){
    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/octet-stream";putExtra(Intent.EXTRA_STREAM,uri);putExtra(Intent.EXTRA_SUBJECT,subject);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"通过飞书、QQ 等应用发送"))
}

@Composable
private fun ExportResultPackageScreen(database:InventoryDatabase,task:InventoryTask,onBack:()->Unit){
    val context=LocalContext.current;var message by remember{mutableStateOf("核对内容后生成二维码发送")};var file by remember{mutableStateOf<File?>(null)};var nearby by remember{mutableStateOf(false)};val scans=remember{database.transferScans(task.id)}
    Page("发送盘点结果",onBack){Text(task.name,style=MaterialTheme.typography.titleMedium);Text("记录：${scans.size} 条");Text("人员：${scans.map{it.operator}.filter{it.isNotBlank()}.distinct().joinToString("、").ifBlank{"未填写"}}");Text("区域：${scans.map{it.areaCode}.distinct().joinToString("、").ifBlank{"暂无记录"}}");Text("异常照片：${database.scanPhotosByUuid(task.id).values.sumOf{it.size}} 张");Text(message);Spacer(Modifier.height(16.dp));if(file==null)Button(onClick={runCatching{ResultPackageCodec.create(context,database,task)}.onSuccess{file=it;nearby=true;message="结果二维码已生成"}.onFailure{message="生成失败：${it.message}"}},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.QrCode2,null);Spacer(Modifier.width(8.dp));Text("生成二维码并发送")};file?.let{f->if(!nearby)Button(onClick={nearby=true},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.QrCode2,null);Spacer(Modifier.width(8.dp));Text("重新显示发送二维码")};if(nearby)NearbyTransferDialog(f,"result"){nearby=false}};Text("结果包包含扫码、撤销、异常、人员记录和现场照片。重复导入时不会重复计数。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=12.dp))}
}

@Composable
private fun ExportExcelScreen(database:InventoryDatabase,task:InventoryTask,onBack:()->Unit){
    val context=LocalContext.current;var message by remember{mutableStateOf("生成包含汇总、差异和审计明细的 Excel 工作簿")}
    Page("导出 Excel 盘点总表",onBack){Text(task.name,style=MaterialTheme.typography.titleMedium);Text(message);Spacer(Modifier.height(16.dp));Button(onClick={runCatching{InventoryExcelExporter.create(context,database,task)}.onSuccess{file->val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";putExtra(Intent.EXTRA_STREAM,uri);putExtra(Intent.EXTRA_SUBJECT,"${task.name} 盘点总表");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"分享 Excel 盘点总表"));message="Excel 已生成"}.onFailure{message="生成失败：${it.message}"}},modifier=Modifier.fillMaxWidth()){Text("生成并分享 .xlsx")};Text("包含9个工作表：盘点汇总、全部有效记录、各区域明细、台账内未盘点、新增待入账、异常资产、跨区域重复、撤销审计、人员及交接。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=12.dp))}
}

@Composable
private fun ImportResultPackageScreen(database:InventoryDatabase,onBack:()->Unit,onDone:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var read by remember{mutableStateOf<ReadResultPackage?>(null)};var preview by remember{mutableStateOf<ResultImportPreview?>(null)};var message by remember{mutableStateOf("请选择收到的 .invresult 盘点结果包")};var busy by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->if(uri!=null){busy=true;read=null;preview=null;scope.launch{runCatching{withContext(Dispatchers.IO){ResultPackageCodec.read(context,uri).also{database.resultImportPreview(it.data)}}}.onSuccess{r->read=r;preview=database.resultImportPreview(r.data);message="校验通过，请核对目标公司和任务"}.onFailure{message="不能导入：${it.message}"};busy=false}}}
    Page("合并盘点结果",onBack){FeedbackBanner(message,feedbackKind(message,busy));Spacer(Modifier.height(12.dp));Button(onClick={picker.launch(arrayOf("application/octet-stream","application/zip","*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在校验…" else "选择 .invresult 结果包")};read?.let{r->preview?.let{v->val p=r.data;Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(v.company.name,style=MaterialTheme.typography.titleMedium);Text("公司编号：${v.company.code}");Text("目标任务：${v.task.name}");HorizontalDivider(Modifier.padding(vertical=6.dp));Text("来源设备：${p.deviceName}");Text("盘点人员：${v.operators.joinToString("、").ifBlank{"未填写"}}");Text("涉及区域：${v.areaNames.joinToString("、").ifBlank{"无记录"}}");Text("记录：${v.total} 条（新增 ${v.newCount}，已存在 ${v.existingCount}）");Text("异常照片：${r.extractedPhotos.values.sumOf{it.size}} 张")}};Spacer(Modifier.height(12.dp));Button(onClick={runCatching{database.importResultPackage(p,r.extractedPhotos)}.onSuccess{(added,skipped)->message="已合并到 ${v.company.name} / ${v.task.name}：新增 $added 条，已存在 $skipped 条";read=null;preview=null;onDone()}.onFailure{message="合并失败：${it.message}"}},enabled=v.newCount>0,modifier=Modifier.fillMaxWidth()){Text(if(v.newCount>0)"确认合并并查看任务" else "所有记录均已合并，无需重复操作")}}}
    }
}

@Composable
private fun MissingAssetsScreen(database: InventoryDatabase, task: InventoryTask, onBack: () -> Unit) {
    val assets = remember(task.id) { database.missingLedgerAssets(task.id) }
    var query by rememberSaveable(task.id) { mutableStateOf("") }
    val visible = remember(query, assets) { assets.filter { query.isBlank() || it.code.contains(query, true) || it.name.contains(query, true) || it.department.contains(query, true) } }
    Page("未盘点资产", onBack) {
        Text("还有 ${assets.size} 项台账资产未扫描", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(query, { query = it }, label = { Text("搜索编号、名称或部门") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(visible, key = { it.code }) { asset ->
                ListItem(headlineContent = { Text(asset.code) }, supportingContent = { Text(listOf(asset.name, asset.department).filter(String::isNotBlank).joinToString(" · ").ifBlank { "暂无补充资料" }) },leadingContent={Icon(assetSemanticIcon(asset),assetSemanticLabel(asset),tint=MaterialTheme.colorScheme.primary)})
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TaskSummaryScreen(database: InventoryDatabase, task: InventoryTask, onBack: () -> Unit) {
    val progress = remember { database.areaProgress(task.id) }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    Page("区域盘点进度", onBack) {
        Text(task.name, style = MaterialTheme.typography.titleMedium)
        Text("按区域查看有效数量、问题数量、盘点人员和最后扫描时间。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp)); Text("各区域进度", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(progress, key = { it.code }) { area ->
                ListItem(headlineContent = { Text(area.name) }, leadingContent={Icon(Icons.Rounded.LocationOn,"区域",tint=MaterialTheme.colorScheme.primary)},supportingContent = {
                    Text("有效 ${area.valid} · 新增 ${area.newAssets} · 异常 ${area.anomalies}\n人员：${area.operators.ifBlank { "尚未开始" }}${area.lastScanAt?.let { " · 最近 ${dateFormat.format(Date(it))}" }.orEmpty()}")
                })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DuplicateResolutionScreen(database: InventoryDatabase, task: InventoryTask, currentOperator: String, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val codes = remember(refresh) { database.crossRegionDuplicateCodes(task.id) }
    var target by remember { mutableStateOf<ScanEventRecord?>(null) }
    var moveTarget by remember { mutableStateOf<Pair<String,List<ScanEventRecord>>?>(null) }
    Page("跨区域重复处理", onBack) {
        Text("不同区域扫描到相同编号时，两处都先计为有效。请先判断是设备发生移动、标签编号重复，还是误扫，再选择对应处理方式。")
        Spacer(Modifier.height(10.dp))
        if (codes.isEmpty()) EmptyState(Icons.Rounded.TaskAlt,"跨区域重复已全部处理","当前任务中没有需要人工确认的重复资产")
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(codes) { code ->
                val rows = database.scanRecords(task.id, code).filter { it.assetCode == code && it.revokedAt == null && !it.duplicateInArea }
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(code, style = MaterialTheme.typography.titleMedium)
                        rows.forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column { Text(row.areaName); Text(row.operatorName, style = MaterialTheme.typography.bodySmall) }
                                TextButton(onClick = { target = row }) { Text("改新编号") }
                            }
                        }
                        Spacer(Modifier.height(6.dp));OutlinedButton(onClick={moveTarget=code to rows},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.MoveDown,null);Spacer(Modifier.width(6.dp));Text("确认是同一设备移动")}
                    }
                }
            }
        }
    }
    moveTarget?.let{(code,rows)->var finalArea by remember(code){mutableStateOf(rows.maxByOrNull{it.scannedAt}?.areaCode.orEmpty())};var op by remember(code){mutableStateOf(currentOperator)};var reason by remember(code){mutableStateOf("现场确认同一设备发生移动")};var error by remember(code){mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={moveTarget=null},title={Text("确认 $code 的最终位置")},text={Column{Text("请选择盘点结束时设备实际所在区域。其他区域的扫码记录会保留为“因设备移动撤销”，不再计入有效数量。");rows.distinctBy{it.areaCode}.forEach{row->Row(Modifier.fillMaxWidth().clickable{finalArea=row.areaCode},verticalAlignment=Alignment.CenterVertically){RadioButton(finalArea==row.areaCode,{finalArea=row.areaCode});Column{Text(row.areaName);Text("扫码：${SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(row.scannedAt))}",style=MaterialTheme.typography.bodySmall)}}};OutlinedTextField(op,{op=it},label={Text("处理人")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(reason,{reason=it},label={Text("判断依据或处理原因")},modifier=Modifier.fillMaxWidth());error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={Button(onClick={runCatching{database.confirmAssetMoved(task.id,code,finalArea,op,reason)}.onSuccess{moveTarget=null;refresh++}.onFailure{error=it.message?:"处理失败"}},enabled=finalArea.isNotBlank()&&op.isNotBlank()&&reason.isNotBlank()){Text("确认最终区域")}},dismissButton={TextButton(onClick={moveTarget=null}){Text("取消")}})
    }
    target?.let { row ->
        var code by remember(row.id) { mutableStateOf("") }
        var operator by remember(row.id) { mutableStateOf(currentOperator) }
        var reason by remember(row.id) { mutableStateOf("重复标签实物重新编号") }
        var error by remember(row.id) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { target = null }, title = { Text("为 ${row.areaName} 的实物改编号") }, text = {
            Column {
                Text("原编号：${row.assetCode}")
                OutlinedTextField(code, { code = it.uppercase(); error = null }, label = { Text("确认后的新编号") }, singleLine = true)
                OutlinedTextField(operator, { operator = it }, label = { Text("处理人") }, singleLine = true)
                OutlinedTextField(reason, { reason = it }, label = { Text("处理原因") })
                if (code.isNotBlank() && !XlsxImporter.isValidCode(code)) Text("编号格式不正确", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            Button(onClick = {
                runCatching { database.correctAssetCode(row.id, code, operator, reason) }
                    .onSuccess { target = null; refresh++ }.onFailure { error = it.message ?: "无法修改编号" }
            }, enabled = XlsxImporter.isValidCode(code) && operator.isNotBlank() && reason.isNotBlank()) { Text("确认修改") }
        }, dismissButton = { TextButton(onClick = { target = null }) { Text("取消") } })
    }
}

@Composable
private fun ScanRecordsScreen(database: InventoryDatabase, task: InventoryTask, currentOperator: String,
                              filter: RecordFilter = RecordFilter.ALL, onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<ScanEventRecord?>(null) }
    var revokeTarget by remember { mutableStateOf<ScanEventRecord?>(null) }
    var anomalyTarget by remember { mutableStateOf<ScanEventRecord?>(null) }
    var promoteTarget by remember{mutableStateOf<ScanEventRecord?>(null)}
    var pendingPhoto by remember { mutableStateOf<Pair<Long, File>?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        pendingPhoto?.let { (scanId, file) ->
            if (saved) database.addAnomalyPhoto(scanId, file.absolutePath) else file.delete()
            refresh++
        }
        pendingPhoto = null
    }
    val records = remember(query, refresh, filter) {
        database.scanRecords(task.id, query).filter { record ->
            when (filter) {
                RecordFilter.ALL -> true
                RecordFilter.ANOMALY -> record.revokedAt == null && record.anomalyType.isNotBlank()
                RecordFilter.NEW_ASSET -> record.revokedAt == null && !record.duplicateInArea && record.outsideLedger
            }
        }
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA) }
    Page(when(filter) { RecordFilter.ALL -> "盘点记录"; RecordFilter.ANOMALY -> "异常资产"; RecordFilter.NEW_ASSET -> "新增待入账资产" }, onBack) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索编号、区域或盘点人员") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val valid = records.count { it.revokedAt == null && !it.duplicateInArea }
        val newCount = records.count { it.revokedAt == null && !it.duplicateInArea && it.outsideLedger }
        Text("${records.size} 条记录 · 有效 $valid · 新增待入账 $newCount", Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(records, key = { it.id }) { record ->
                ListItem(
                    headlineContent = { Text(record.assetCode) },
                    supportingContent = { Text("${record.areaName} · ${record.operatorName} · ${dateFormat.format(Date(record.scannedAt))}") },
                    leadingContent={Icon(when{record.revokedAt!=null->Icons.Rounded.Undo;record.anomalyType.isNotBlank()->Icons.Rounded.Warning;record.duplicateInArea->Icons.Rounded.ContentCopy;record.outsideLedger->Icons.Rounded.AddCircle;else->Icons.Rounded.CheckCircle},"记录状态",tint=when{record.revokedAt!=null->Color.Gray;record.anomalyType.isNotBlank()->MaterialTheme.colorScheme.error;record.duplicateInArea||record.outsideLedger->Color(0xFFE65100);else->Color(0xFF2E7D32)})},
                    trailingContent = {
                        Text(when {
                            record.revokedAt != null -> "已撤销"
                            record.anomalyType.isNotBlank() -> "异常"
                            record.duplicateInArea -> "重复"
                            record.outsideLedger -> "新增待入账"
                            else -> "正常"
                        }, color = when {
                            record.revokedAt != null -> Color.Gray
                            record.anomalyType.isNotBlank() -> MaterialTheme.colorScheme.error
                            record.duplicateInArea || record.outsideLedger -> Color(0xFFE65100)
                            else -> Color(0xFF2E7D32)
                        })
                    },
                    modifier = Modifier.clickable { selected = record }
                )
                HorizontalDivider()
            }
        }
    }
    selected?.let { record ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(record.assetCode) }, text = {
            Column {
                Text("区域：${record.areaName}（${record.areaCode}）")
                Text("盘点人员：${record.operatorName}")
                Text("扫码时间：${dateFormat.format(Date(record.scannedAt))}")
                Text("数据来源：${record.sourceDevice}")
                Text(if (record.outsideLedger) "台账状态：新增待入账资产" else "台账状态：台账内资产")
                if(record.outsideLedger&&record.revokedAt==null)Button(onClick={selected=null;promoteTarget=record}){Text("确认转入正式台账")}
                if (record.duplicateInArea) Text("该记录为同区域重复扫描")
                if (record.anomalyType.isNotBlank()) {
                    Spacer(Modifier.height(8.dp)); Text("异常类型：${record.anomalyType}")
                    if (record.anomalyNote.isNotBlank()) Text("异常备注：${record.anomalyNote}")
                    Text("登记人：${record.anomalyOperator}")
                    val photos = database.anomalyPhotos(record.id)
                    Text("现场照片：${photos.size} 张")
                    photos.forEachIndexed { index, photo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(photo.path))
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }) { Text("查看照片 ${index + 1}") }
                            TextButton(onClick = { File(photo.path).delete(); database.removeAnomalyPhoto(photo.id); selected = null; refresh++ }) { Text("移除") }
                        }
                    }
                    Button(onClick = {
                        val dir = File(context.filesDir, "anomaly_photos").apply { mkdirs() }
                        val file = File(dir, "${record.assetCode}_${System.currentTimeMillis()}.jpg")
                        pendingPhoto = record.id to file
                        takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                    }) { Text("拍摄现场照片") }
                }
                if (record.revokedAt != null) {
                    Spacer(Modifier.height(8.dp)); Text("已撤销")
                    Text("操作人：${record.revokeOperator}"); Text("原因：${record.revokeReason}")
                    Text("时间：${dateFormat.format(Date(record.revokedAt))}")
                }
            }
        }, confirmButton = {
            TextButton(onClick = { selected = null }) { Text("关闭") }
        }, dismissButton = {
            if (record.revokedAt == null && !record.duplicateInArea) Row {
                TextButton(onClick = { selected = null; anomalyTarget = record }) { Text(if (record.anomalyType.isBlank()) "标记异常" else "编辑异常") }
                TextButton(onClick = { selected = null; revokeTarget = record }) { Text("撤销") }
            }
        })
    }
    anomalyTarget?.let { record ->
        val types = listOf("标签损坏", "实物信息不符", "设备损坏", "无法确认", "其他")
        var type by remember(record.id) { mutableStateOf(record.anomalyType) }
        var note by remember(record.id) { mutableStateOf(record.anomalyNote) }
        var operator by remember(record.id) { mutableStateOf(currentOperator) }
        AlertDialog(onDismissRequest = { anomalyTarget = null }, title = { Text("异常资产 · ${record.assetCode}") }, text = {
            Column {
                Text("选择异常类型")
                types.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { type = option }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == option, onClick = { type = option }); Text(option)
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("异常备注") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(operator, { operator = it }, label = { Text("登记人") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            Button(onClick = { database.saveAnomaly(record.id, type, note, operator); anomalyTarget = null; refresh++ }, enabled = type.isNotBlank() && operator.isNotBlank()) { Text("保存异常") }
        }, dismissButton = {
            Row {
                if (record.anomalyType.isNotBlank()) TextButton(onClick = { database.clearAnomaly(record.id); anomalyTarget = null; refresh++ }) { Text("取消异常") }
                TextButton(onClick = { anomalyTarget = null }) { Text("返回") }
            }
        })
    }
    promoteTarget?.let{record->var assetName by remember(record.id){mutableStateOf("")};var category by remember(record.id){mutableStateOf("")};var op by remember(record.id){mutableStateOf(currentOperator)};AlertDialog(onDismissRequest={promoteTarget=null},title={Text("新增资产正式入账")},text={Column{Text(record.assetCode);Text("来源区域：${record.areaName}");OutlinedTextField(assetName,{assetName=it},label={Text("资产名称")});OutlinedTextField(category,{category=it},label={Text("资产分类")});OutlinedTextField(op,{op=it},label={Text("操作人")})}},confirmButton={Button(onClick={database.promoteNewAsset(task.id,record,assetName,category,op);promoteTarget=null;refresh++},enabled=assetName.isNotBlank()&&op.isNotBlank()){Text("确认入账")}},dismissButton={TextButton(onClick={promoteTarget=null}){Text("取消")}})}
    revokeTarget?.let { record ->
        var operator by remember(record.id) { mutableStateOf(currentOperator) }
        var reason by remember(record.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { revokeTarget = null }, title = { Text("撤销 ${record.assetCode}") }, text = {
            Column {
                Text("撤销后不再计入有效数量，原始记录和操作信息会永久保留。")
                OutlinedTextField(operator, { operator = it }, label = { Text("操作人") }, singleLine = true)
                OutlinedTextField(reason, { reason = it }, label = { Text("撤销原因（必填）") })
            }
        }, confirmButton = {
            Button(onClick = { database.revokeScan(record.id, operator, reason); revokeTarget = null; refresh++ }, enabled = operator.isNotBlank() && reason.isNotBlank()) { Text("确认撤销") }
        }, dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("取消") } })
    }
}

@Composable
private fun ScannerScreen(database: InventoryDatabase, task: InventoryTask, initialOperator: String,
                          initialSessionId: String, voiceStatus: String, speak: (String) -> Unit,onDataImported:()->Unit, onExit: (String) -> Unit) {
    val context = LocalContext.current
    val allowedAreas = remember { database.taskAreas(task.id) }
    var operator by remember { mutableStateOf(initialOperator) }
    var sessionId by remember { mutableStateOf(initialSessionId) }
    var area by remember { mutableStateOf<Area?>(null) }
    var message by remember { mutableStateOf("请先扫描区域二维码") }
    var statusColor by remember { mutableStateOf(Color(0xFFF4F6F5)) }
    var count by remember { mutableIntStateOf(0) }
    var duplicateCount by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var paused by remember { mutableStateOf(false) }
    var lastRaw by remember { mutableStateOf<String?>(null) }
    var lastSeenAt by remember { mutableLongStateOf(0L) }
    var handover by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var hintSpoken by remember { mutableStateOf(false) }
    var torchEnabled by remember{mutableStateOf(false)}
    var torchManual by remember{mutableStateOf<Boolean?>(null)}
    var torchControl by remember{mutableStateOf<((Boolean)->Unit)?>(null)}
    var darkFrames by remember{mutableIntStateOf(0)}
    var brightFrames by remember{mutableIntStateOf(0)}
    var transferLink by remember{mutableStateOf<LocalTransferLink?>(null)}

    BackHandler { confirmExit = true }

    LaunchedEffect(Unit) { speak("请扫描区域二维码");delay(8000);if(area==null&&!hintSpoken){hintSpoken=true;speak("请先扫描区域二维码")} }

    Box(Modifier.fillMaxSize()) {
        CameraScanner(onReady = {
            if (message == "请先扫描区域二维码") message = "相机识别已启动，请扫描区域二维码"
        }, onError = { message = "识别异常：$it"; statusColor = Color(0xFFFFCDD2) },onTorchReady={torchControl=it},onLuma={luma->
            if(torchManual==null){if(luma<42){darkFrames++;brightFrames=0}else if(luma>90){brightFrames++;darkFrames=0}else{darkFrames=0;brightFrames=0};if(darkFrames>=4&&!torchEnabled){torchControl?.invoke(true);torchEnabled=true;message="环境较暗，已自动开启手电筒";darkFrames=0};if(brightFrames>=6&&torchEnabled){torchControl?.invoke(false);torchEnabled=false;message="光线已恢复，已自动关闭手电筒";brightFrames=0}}
        }) { raw ->
            if (paused||transferLink!=null) return@CameraScanner
            val now = System.currentTimeMillis()
            if (raw == lastRaw && now - lastSeenAt < 2500) return@CameraScanner
            lastRaw = raw; lastSeenAt = now
            if(raw.trim().startsWith("INVTRANSFER:")){
                runCatching{LocalTransferLink.parse(raw.trim())}.onSuccess{transferLink=it;message="检测到${if(it.type=="task")"盘点任务" else "盘点结果"}，请核对并导入";statusColor=Color(0xFFB2DFDB);speak("检测到盘点数据，请核对并导入")}.onFailure{message="传输二维码无效：${it.message}";statusColor=Color(0xFFFFCDD2);speak("传输二维码无效")}
            } else when (val payload = QrPayload.parse(raw)) {
                is QrPayload.Area -> {
                    val found = allowedAreas.firstOrNull { it.code == payload.code }
                    if (found == null) {
                        message = "该区域未分配给当前任务：${payload.code}"
                        statusColor = Color(0xFFFFE0B2)
                        speak("该区域不属于当前任务，请重新扫描区域二维码")
                    } else {
                        val switching=area!=null&&area?.code!=found.code
                        area = found; count = database.validCount(task.id, found.code)
                        message = "${if(switching)"已切换到" else "已进入"}${found.name}\n请连续扫描资产二维码"
                        statusColor = Color(0xFFC8E6C9)
                        speak("${if(switching)"已切换到" else "已进入"}${found.name}，请扫描资产二维码")
                    }
                }
                is QrPayload.Asset -> {
                    val selected = area
                    if (selected == null) {
                        message = "请先扫描区域二维码"
                        statusColor = Color(0xFFFFE0B2)
                        speak("请先扫描区域二维码")
                    }
                    else runCatching { database.recordScan(task.id, selected.code, payload.code, raw, operator) }
                        .onSuccess { result ->
                            val otherAreas=if(result.duplicateInArea)emptyList() else database.activeOtherAreaNames(task.id,result.assetCode,selected.code)
                            val crossRegion=otherAreas.isNotEmpty()
                            if (!result.duplicateInArea) count++ else duplicateCount++
                            message = when {
                                result.duplicateInArea -> "本区域重复：${result.assetCode}"
                                crossRegion -> "其他区域已扫描：${result.assetCode}\n${otherAreas.joinToString("、")} · 当前区域仍计入，结束后确认位置"
                                result.outsideLedger -> "已盘点：${result.assetCode}\n新增待入账资产"
                                else -> "已盘点：${result.assetCode}"
                            }
                            statusColor = if (result.duplicateInArea || result.outsideLedger || crossRegion) Color(0xFFFFE0B2) else Color(0xFFC8E6C9)
                            val spoken = spokenAssetCode(result.assetCode)
                            speak(when{result.duplicateInArea->"重复，$spoken";crossRegion->"其他区域已扫描，$spoken";result.outsideLedger->"新增资产，$spoken";else->spoken})
                            val recentItem = when {
                                result.duplicateInArea -> "${result.assetCode} · 重复"
                                crossRegion -> "${result.assetCode} · 跨区域待确认"
                                result.outsideLedger -> "${result.assetCode} · 新增待入账"
                                else -> "${result.assetCode} · 成功"
                            }
                            recent = (listOf(recentItem) + recent).take(3)
                            val vibrator=context.getSystemService(Vibrator::class.java)
                            if(result.outsideLedger&&!result.duplicateInArea&&!crossRegion)vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0,60,70,60),-1)) else vibrator?.vibrate(VibrationEffect.createOneShot(if(result.duplicateInArea||crossRegion)180 else 60,VibrationEffect.DEFAULT_AMPLITUDE))
                        }.onFailure {
                            message = "保存失败，扫描已暂停"
                            statusColor = Color(0xFFFFCDD2)
                            paused = true
                            speak("保存失败，请停止扫描")
                        }
                }
                is QrPayload.Invalid -> {
                    message = "${payload.reason}\n识别内容：${raw.trim()}"
                    statusColor = Color(0xFFFFCDD2)
                    speak("无效二维码")
                }
            }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0xE6072F2B)).statusBarsPadding().padding(16.dp)) {
            Text(task.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("人员：$operator",color=Color.White);Text("语音：$voiceStatus",color=if(voiceStatus=="正常")Color(0xFFB9F6CA) else Color(0xFFFFCC80))}
            Text("区域：${area?.name ?: "等待扫描区域"}", color = Color.White,style=MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){Column{Text("$count",color=Color.White,style=MaterialTheme.typography.headlineSmall);Text("已盘点",color=Color.White.copy(alpha=.8f),style=MaterialTheme.typography.labelSmall)};Column{Text("$duplicateCount",color=Color.White,style=MaterialTheme.typography.headlineSmall);Text("本区重复",color=Color.White.copy(alpha=.8f),style=MaterialTheme.typography.labelSmall)}}
        }
        Surface(Modifier.align(Alignment.Center).padding(horizontal=32.dp),shape=RoundedCornerShape(18.dp),color=Color(0xB8000000),border=androidx.compose.foundation.BorderStroke(2.dp,if(area==null)Color(0xFF80CBC4) else Color(0xFFA5D6A7))){Column(Modifier.padding(horizontal=24.dp,vertical=18.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(if(area==null)Icons.Rounded.QrCodeScanner else Icons.Rounded.QrCodeScanner,null,tint=Color.White,modifier=Modifier.size(34.dp));Spacer(Modifier.height(6.dp));Text(if(area==null)"扫描区域或传输二维码" else "${area?.name} · 连续扫描资产",color=Color.White,style=MaterialTheme.typography.titleMedium);Text(if(area==null)"自动识别区域、任务和结果" else "也可扫描任务或结果传输二维码",color=Color.White.copy(alpha=.82f),style=MaterialTheme.typography.bodySmall)}}
        FilledIconButton(onClick={val next=!torchEnabled;torchManual=next;torchControl?.invoke(next);torchEnabled=next;message=if(next)"手电筒已开启（手动）" else "手电筒已关闭（手动）"},modifier=Modifier.align(Alignment.CenterEnd).padding(end=18.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xCCFFFFFF))){Icon(if(torchEnabled)Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,if(torchEnabled)"关闭手电筒" else "开启手电筒",tint=Color(0xFF075E54))}
        val animatedStatusColor by animateColorAsState(statusColor,tween(220,easing=FastOutSlowInEasing),label="扫码状态颜色")
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), color = animatedStatusColor,shadowElevation=6.dp) {
            Column(Modifier.padding(18.dp)) {
                AnimatedContent(message,label="扫码反馈",transitionSpec={
                    (fadeIn(tween(160))+slideInHorizontally(tween(190)){it/12}) togetherWith fadeOut(tween(110))
                }){currentMessage->Text(currentMessage, style = MaterialTheme.typography.titleMedium)}
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    recent.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                AnimatedVisibility(paused,enter=fadeIn()+scaleIn(initialScale=.96f),exit=fadeOut()) {
                  Column {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        paused = false
                        message = "已恢复，请继续扫描"
                        statusColor = Color(0xFFC8E6C9)
                    }) { Text("确认并恢复扫描") }
                  }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { area = null; message = "请扫描新的区域二维码";statusColor=Color(0xFFB2DFDB);speak("请扫描区域二维码") }) { Text("切换区域") }
                    OutlinedButton(onClick = { handover = true }) { Text("人员交接") }
                    TextButton(onClick = { confirmExit = true }) { Text("结束扫描") }
                }
            }
        }
    }
    if (handover) {
        var next by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { handover = false }, title = { Text("人员交接") },
            text = { OutlinedTextField(next, { next = it }, label = { Text("接手人员姓名或工号") }) },
            confirmButton = { Button(onClick = {
                database.endSession(sessionId, "人员交接给${next.trim()}")
                operator = next.trim()
                sessionId = database.startSession(task.id, operator)
                handover = false
                speak("已切换盘点人员，${operator}，请继续扫描")
            }, enabled = next.isNotBlank()) { Text("确认交接") } },
            dismissButton = { TextButton(onClick = { handover = false }) { Text("取消") } })
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("结束连续扫描？") },
            text = { Text("已扫描的数据已经保存。结束后将返回当前盘点任务，之后仍可继续进入扫描。") },
            confirmButton = {
                Button(onClick = { confirmExit = false; onExit(sessionId) }) { Text("结束并返回任务") }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续扫描") } }
        )
    }
    transferLink?.let{link->TransferReceiveDialog(database,link,onDismiss={transferLink=null;lastRaw=null;message=if(area==null)"请扫描区域二维码" else "${area?.name} · 请继续扫描资产"},onImported={onDataImported();area?.let{count=database.validCount(task.id,it.code)};message=if(link.type=="task")"盘点任务已导入" else "盘点结果已合并";speak(if(link.type=="task")"盘点任务已导入" else "盘点结果已合并")})}
}

private fun spokenAssetCode(code: String): String {
    val digits = mapOf(
        '0' to "零", '1' to "一", '2' to "二", '3' to "三", '4' to "四",
        '5' to "五", '6' to "六", '7' to "七", '8' to "八", '9' to "九"
    )
    return code.uppercase().map { char -> digits[char] ?: char.toString() }.joinToString(" ")
}

@Composable
private fun CameraScanner(onReady: () -> Unit, onError: (String) -> Unit, onTorchReady:(((Boolean)->Unit)->Unit)={},onLuma:(Double)->Unit={},onValue: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by rememberSaveable{mutableStateOf(false)}
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it;requested=true }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) { Box(Modifier.fillMaxSize().background(Color(0xFF202124)).padding(24.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Rounded.NoPhotography,null,tint=Color.White,modifier=Modifier.size(64.dp));Spacer(Modifier.height(16.dp));Text("扫码需要使用摄像头",color=Color.White,style=MaterialTheme.typography.titleLarge);Text(if(requested)"相机权限未允许，请到系统设置中开启后返回" else "允许后即可扫描区域和资产二维码",color=Color.White.copy(alpha=.8f),modifier=Modifier.padding(vertical=10.dp));Button(onClick={if(requested){context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}else{launcher.launch(Manifest.permission.CAMERA)}}){Text(if(requested)"打开系统设置" else "允许使用摄像头")}} }; return }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); scanner.close() } }
    AndroidView(factory = { ctx ->
        PreviewView(ctx).also { view ->
            ProcessCameraProvider.getInstance(ctx).addListener({
                val provider = ProcessCameraProvider.getInstance(ctx).get()
                val preview = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    ).build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                    it.setAnalyzer(executor, BarcodeAnalyzer(scanner, { value -> view.post { onValue(value) } }, { error -> view.post { onError(error) } },{luma->view.post{onLuma(luma)}}))
                }
                provider.unbindAll()
                val camera = provider.bindToLifecycle(context as ComponentActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                view.post{onTorchReady{enabled->camera.cameraControl.enableTorch(enabled)}}
                view.post {
                    val point = view.meteringPointFactory.createPoint(0.5f, 0.5f)
                    camera.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                    )
                    onReady()
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
    }, modifier = Modifier.fillMaxSize())
}
