package cn.assetinventory.app

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import com.gengcon.www.jcprintersdk.JCPrintApi
import com.gengcon.www.jcprintersdk.callback.Callback
import com.gengcon.www.jcprintersdk.callback.PrintCallback
import java.util.concurrent.Executors

data class NiimbotDevice(val name:String,val address:String,val bonded:Boolean=true)

private fun bluetoothEnabled(context:Context)=context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled==true

object NiimbotB3sPrinter {
    private const val WIDTH_MM=40f
    private const val HEIGHT_MM=15f
    private const val MULTIPLE=8f
    private val executor=Executors.newSingleThreadExecutor()
    private var api:JCPrintApi?=null
    private var connectedAddress:String?=null

    private fun printer(context:Context):JCPrintApi{
        api?.let{return it}
        val created=JCPrintApi.getInstance(object:Callback{
            override fun onConnectSuccess(address:String,type:Int){connectedAddress=address}
            override fun onDisConnect(){connectedAddress=null}
            override fun onElectricityChange(value:Int){}
            override fun onCoverStatus(value:Int){}
            override fun onPaperStatus(value:Int){}
            override fun onRfidReadStatus(value:Int){}
            override fun onRibbonStatus(value:Int){}
            override fun onRibbonRfidReadStatus(value:Int){}
            override fun onFirmErrors(){}
        })
        check(created.initSdk(context.applicationContext as Application)){"NIIMBOT 打印 SDK 初始化失败"}
        api=created;return created
    }

    fun pairedDevices(context:Context):List<NiimbotDevice>{
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter?:return emptyList()
        if(android.os.Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return emptyList()
        return adapter.bondedDevices.filter{it.type==BluetoothDevice.DEVICE_TYPE_CLASSIC||it.type==BluetoothDevice.DEVICE_TYPE_DUAL}.map{NiimbotDevice(it.name?:"蓝牙设备",it.address)}.sortedBy{if(it.name.contains("B3S",true))0 else 1}
    }

    fun startDiscovery(context:Context):Boolean{
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter?:return false
        if(android.os.Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)return false
        if(adapter.isDiscovering)adapter.cancelDiscovery()
        return adapter.startDiscovery()
    }

    fun stopDiscovery(context:Context){
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter?:return
        if(android.os.Build.VERSION.SDK_INT<31||ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED)adapter.cancelDiscovery()
    }

    fun requestPair(context:Context,device:BluetoothDevice):Boolean{
        if(android.os.Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return false
        return runCatching{device.createBond()}.getOrDefault(false)
    }

    fun connect(context:Context,address:String,done:(Result<Unit>)->Unit)=executor.execute{
        val result=runCatching{val p=printer(context);if(p.isConnection()!=0||connectedAddress!=address){p.close();check(p.connectBluetoothPrinter(address)==0){"连接失败，请确认打印机已开机并已在系统蓝牙中配对"}}}
        Handler(Looper.getMainLooper()).post{done(result)}
    }

    fun print(context:Context,labels:List<QrLabel>,done:(String)->Unit){
        val p=runCatching{printer(context)}.getOrElse{done(it.message?:"SDK 初始化失败");return}
        if(p.isConnection()!=0){done("请先连接 B3S_P");return}
        val bitmaps=labels.map(::labelBitmap);var submittedPages=0
        p.setTotalPrintQuantity(bitmaps.size)
        p.startPrintJob(3,1,1,object:PrintCallback{
            override fun onBufferFree(pageIndex:Int,bufferSize:Int){if(submittedPages<bitmaps.size){val bitmap=bitmaps[submittedPages++];p.commitImageData(0,bitmap,WIDTH_MM,HEIGHT_MM,1,0,0,0,0,"")}}
            override fun onProgress(pageIndex:Int,quantityIndex:Int,data:HashMap<String,Any>){done("正在打印 ${pageIndex.coerceIn(1,bitmaps.size)}/${bitmaps.size}");if(pageIndex==bitmaps.size&&quantityIndex==1){p.endPrintJob();done("打印完成：${bitmaps.size} 张")}}
            override fun onError(code:Int){done(errorText(code))}
            override fun onError(code:Int,state:Int){done(errorText(code))}
            override fun onPause(success:Boolean){}
            override fun onPausing(){}
            override fun onResume(success:Boolean){}
            override fun onCancelJob(success:Boolean){done(if(success)"已取消打印" else "取消失败")}
        })
    }

    fun preview(label:QrLabel)=labelBitmap(label)

    private fun labelBitmap(label:QrLabel):Bitmap{
        val width=(WIDTH_MM*MULTIPLE).toInt();val height=(HEIGHT_MM*MULTIPLE).toInt()
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val horizontalInset=(0.6f*MULTIPLE).toInt();val qrSize=height+4
        val qr=QrGenerator.bitmapForPayload(label.payload,qrSize,0);canvas.drawBitmap(qr,horizontalInset.toFloat(),-1f,null)
        val visualGap=horizontalInset;val textLeft=horizontalInset+qrSize+visualGap;val textRight=width-horizontalInset;val textWidth=(textRight-textLeft).toFloat()
        val displayText=if(label.type=="区域")label.name.ifBlank{label.code}else label.code
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);isFakeBoldText=true;textSize=42f}
        while(paint.measureText(displayText)>textWidth&&paint.textSize>20f)paint.textSize-=1f
        val metrics=paint.fontMetrics;val baseline=height/2f-(metrics.ascent+metrics.descent)/2f;canvas.drawText(displayText,(textLeft+textRight)/2f,baseline,paint)
        return bitmap
    }

    private fun errorText(code:Int)=when(code){1->"打印机上盖未关闭";2->"标签纸用完";3->"打印机电量不足";23->"打印机连接已断开";24->"标签尺寸参数错误";27->"B3S_P 出纸异常";28->"请检查标签纸类型";else->"打印失败（错误码 $code）"}
}

@Composable
fun NiimbotPrintDialog(labels:List<QrLabel>,onDismiss:()->Unit){
    val context=LocalContext.current;var devices by remember{mutableStateOf(emptyList<NiimbotDevice>())};var selected by remember{mutableStateOf<NiimbotDevice?>(null)};var message by remember{mutableStateOf("正在检查蓝牙和打印机…")};var connected by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var discovering by remember{mutableStateOf(false)};var pairingAddress by remember{mutableStateOf<String?>(null)};var bluetoothOn by remember{mutableStateOf(bluetoothEnabled(context))};var permissionsGranted by remember{mutableStateOf(android.os.Build.VERSION.SDK_INT<31||listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN).all{ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED})}
    fun merge(found:NiimbotDevice){devices=(devices.filterNot{it.address==found.address}+found).sortedWith(compareByDescending<NiimbotDevice>{it.name.contains("B3S",true)}.thenByDescending{it.bonded}.thenBy{it.name});if(selected?.address==found.address)selected=found}
    fun refresh(){bluetoothOn=bluetoothEnabled(context);if(!bluetoothOn){devices=emptyList();selected=null;connected=false;message="蓝牙尚未开启，暂时无法连接标签打印机";return};if(!permissionsGranted){message="需要附近设备权限才能查找和连接打印机";return};devices=NiimbotB3sPrinter.pairedDevices(context);selected=devices.firstOrNull{it.name.contains("B3S",true)}?:devices.firstOrNull();message=if(devices.isEmpty())"尚未发现打印机，请查找附近打印机" else "请选择 B3S_P；未配对设备会先打开系统配对确认"}
    fun connect(device:NiimbotDevice){busy=true;message="正在连接 ${device.name}…";NiimbotB3sPrinter.connect(context,device.address){result->busy=false;connected=result.isSuccess;message=if(result.isSuccess)"B3S_P 已连接，可以打印" else "连接失败：${result.exceptionOrNull()?.message?:"请检查打印机电源、距离和配对状态"}"}}
    fun discover(){if(!bluetoothOn){message="请先开启蓝牙";return};if(!permissionsGranted){message="请先允许附近设备权限";return};devices=NiimbotB3sPrinter.pairedDevices(context);discovering=NiimbotB3sPrinter.startDiscovery(context);message=if(discovering)"正在查找附近打印机，请保持 B3S_P 开机…" else "无法开始查找，请检查蓝牙权限后重试"}
    val enableBluetooth=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){refresh()}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->permissionsGranted=result.values.all{it};message=if(permissionsGranted)"权限已允许，正在查找打印机" else "附近设备权限未允许，无法连接打印机";refresh()}
    LaunchedEffect(Unit){refresh()}
    LaunchedEffect(pairingAddress){val pending=pairingAddress;if(pending!=null){delay(30000);if(pairingAddress==pending){pairingAddress=null;busy=false;message="配对等待超时，请确认系统配对弹窗或重新查找打印机"}}}
    DisposableEffect(Unit){
        val receiver=object:BroadcastReceiver(){override fun onReceive(receiverContext:Context,intent:Intent){when(intent.action){
            BluetoothDevice.ACTION_FOUND->{val device=if(android.os.Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(device!=null&&(android.os.Build.VERSION.SDK_INT<31||ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)){merge(NiimbotDevice(runCatching{device.name}.getOrNull()?:"附近蓝牙设备",device.address,device.bondState==BluetoothDevice.BOND_BONDED))}}
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED->{discovering=false;message=if(devices.isEmpty())"没有找到打印机，请确认 B3S_P 已开机并靠近手机" else "查找完成，请选择 B3S_P"}
            BluetoothDevice.ACTION_BOND_STATE_CHANGED->{val device=if(android.os.Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(device!=null&&(android.os.Build.VERSION.SDK_INT<31||ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)){when(device.bondState){BluetoothDevice.BOND_BONDED->{pairingAddress=null;val paired=NiimbotDevice(runCatching{device.name}.getOrNull()?:"B3S_P",device.address,true);merge(paired);selected=paired;message="配对成功，正在连接 ${paired.name}…";connect(paired)};BluetoothDevice.BOND_NONE->{if(pairingAddress==device.address){pairingAddress=null;busy=false;message="配对未完成，请确认系统配对提示后重试"}}}}}
        }}}
        val filter=IntentFilter().apply{addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)}
        // Bluetooth discovery and bond-state events are system broadcasts, so the
        // receiver must accept broadcasts originating outside this application.
        ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_EXPORTED)
        onDispose{NiimbotB3sPrinter.stopDiscovery(context);runCatching{context.unregisterReceiver(receiver)}}
    }
    val preview=remember(labels){NiimbotB3sPrinter.preview(labels.first())}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("NIIMBOT B3S_P · T40×15")},text={Column{
        Text("打印预览（首张）",style=MaterialTheme.typography.labelLarge);Image(preview.asImageBitmap(),"首张标签预览",Modifier.fillMaxWidth().aspectRatio(40f/15f).background(androidx.compose.ui.graphics.Color.White).padding(4.dp));Text("共 ${labels.size} 张 · ${labels.first().code} 至 ${labels.last().code}",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp));Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp) else Icon(if(connected)Icons.Rounded.CheckCircle else Icons.Rounded.Info,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}}
        if(!bluetoothOn)Button(onClick={enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))},modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text("开启蓝牙")}
        else if(!permissionsGranted)Button(onClick={permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN))},modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text("允许附近设备权限")}
        LazyColumn(Modifier.heightIn(max=180.dp)){items(devices,key={it.address}){d->Row(Modifier.fillMaxWidth().clickable(enabled=!busy){selected=d}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected?.address==d.address,{selected=d},enabled=!busy);Column(Modifier.weight(1f)){Text(d.name);Text(if(d.bonded)"已配对 · ${d.address}" else "未配对 · 点击后连接",style=MaterialTheme.typography.bodySmall)}}}}
        TextButton(onClick={discover()},enabled=!busy&&bluetoothOn&&permissionsGranted&&!discovering){Text(if(discovering)"正在查找…" else "查找附近打印机")}
        Button(onClick={
            when {
                !bluetoothOn -> message="请先开启蓝牙"
                !permissionsGranted -> message="请先允许附近设备权限"
                selected==null -> message="请先选择打印机；如果列表为空，请重新查找或前往系统蓝牙配对"
                selected?.bonded==false -> {val target=selected!!;val adapter=context.getSystemService(BluetoothManager::class.java).adapter;val native=runCatching{adapter.getRemoteDevice(target.address)}.getOrNull();if(native==null){message="无法读取该设备，请重新查找"}else{NiimbotB3sPrinter.stopDiscovery(context);discovering=false;pairingAddress=target.address;busy=true;message="请在系统弹窗中确认与 ${target.name} 配对";if(!NiimbotB3sPrinter.requestPair(context,native)){busy=false;pairingAddress=null;message="无法发起配对，请重新查找或在系统蓝牙中配对"}}}
                else -> connect(selected!!)
            }
        },enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy&&!connected)"正在连接…" else "连接打印机")}
        Spacer(Modifier.height(8.dp));Button(onClick={busy=true;NiimbotB3sPrinter.print(context,labels){message=it;busy=it.startsWith("正在打印")}},enabled=connected&&!busy,modifier=Modifier.fillMaxWidth()){Text("开始打印 ${labels.size} 张")}
    }},confirmButton={TextButton(onClick=onDismiss,enabled=!busy){Text("关闭")}})
}
