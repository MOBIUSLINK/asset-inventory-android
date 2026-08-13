package cn.assetinventory.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

data class LocalTransferLink(val url:String,val token:String,val code:String,val type:String,val name:String,val size:Long,val sha256:String){
    fun payload():String="INVTRANSFER:"+Base64.encodeToString(JSONObject().apply{put("url",url);put("token",token);put("code",code);put("type",type);put("name",name);put("size",size);put("sha256",sha256)}.toString().toByteArray(),Base64.URL_SAFE or Base64.NO_WRAP)
    companion object{fun parse(raw:String):LocalTransferLink{require(raw.startsWith("INVTRANSFER:")){"不是盘点包互传二维码"};val j=JSONObject(String(Base64.decode(raw.substringAfter(':'),Base64.URL_SAFE or Base64.NO_WRAP)));return LocalTransferLink(j.getString("url"),j.getString("token"),j.getString("code"),j.getString("type"),j.getString("name"),j.getLong("size"),j.getString("sha256"))}}
}

class LocalPackageServer(context:Context,private val file:File,private val type:String):AutoCloseable{
    private val active=AtomicBoolean(true);private val token=ByteArray(18).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}
    private val downloaded=AtomicBoolean(false)
    private val socket=ServerSocket(0)
    val link:LocalTransferLink
    val isActive:Boolean get()=active.get()
    val wasDownloaded:Boolean get()=downloaded.get()
    init{val address=localIpv4Address(context)?:error("未连接到同一 Wi-Fi 或手机热点")
        val code=(1000+SecureRandom().nextInt(9000)).toString();link=LocalTransferLink("http://${address.hostAddress}:${socket.localPort}/$token",token,code,type,file.name,file.length(),hash(file))
        Thread{try{while(active.get())runCatching{socket.accept().use{client->val line=client.getInputStream().bufferedReader().readLine().orEmpty();val ok=line.startsWith("GET /$token ");val out=client.getOutputStream();if(ok){out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n".toByteArray());file.inputStream().use{it.copyTo(out)};out.flush();downloaded.set(true);active.set(false)}else out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())}}}finally{close()}}.start()}
    override fun close(){active.set(false);runCatching{socket.close()}}
    companion object{fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}}
}

fun localIpv4Address(context:Context):Inet4Address? {
    val cm=context.getSystemService(ConnectivityManager::class.java)
    val wifiManager=context.applicationContext.getSystemService(WifiManager::class.java)
    cm.allNetworks.forEach{network->
        val caps=cm.getNetworkCapabilities(network)?:return@forEach
        val ethernet=caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val wifi=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val networkWifiInfo=caps.transportInfo as? WifiInfo
        val legacyWifiInfo=runCatching{wifiManager.connectionInfo}.getOrNull()
        val wifiReallyConnected=wifi&&wifiManager.isWifiEnabled&&(
            networkWifiInfo?.supplicantState==SupplicantState.COMPLETED||legacyWifiInfo?.supplicantState==SupplicantState.COMPLETED)
        if(ethernet||wifiReallyConnected){cm.getLinkProperties(network)?.linkAddresses?.map{it.address}?.filterIsInstance<Inet4Address>()?.firstOrNull{!it.isLoopbackAddress&&it.isSiteLocalAddress}?.let{return it}}
    }
    val hotspotInterface=Regex("^(ap|softap)[0-9A-Za-z_.-]*$",RegexOption.IGNORE_CASE)
    return NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&hotspotInterface.matches(it.name)}.flatMap{it.inetAddresses.toList()}.filterIsInstance<Inet4Address>().firstOrNull{!it.isLoopbackAddress&&it.isSiteLocalAddress}
}

object LocalPackageReceiver{
    fun download(context:Context,link:LocalTransferLink):File{require(link.size in 1..200_000_000){"文件大小异常"};val dir=File(context.cacheDir,"nearby_transfer").apply{mkdirs()};val out=File(dir,link.name.replace(Regex("[^A-Za-z0-9一-龥_.-]"),"_"));val connection=URL(link.url).openConnection() as HttpURLConnection;connection.connectTimeout=10_000;connection.readTimeout=60_000;connection.inputStream.use{input->out.outputStream().use{output->input.copyTo(output)}};require(out.length()==link.size&&LocalPackageServer.hash(out)==link.sha256){"文件校验失败，请重新发送"};return out}
    fun uri(context:Context,file:File)=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",file)
}
