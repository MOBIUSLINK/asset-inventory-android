package cn.assetinventory.app

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var database: InventoryDatabase
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private val voiceStatus = mutableStateOf("初始化中")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InventoryBackup.applyPendingRestore(this)
        database = InventoryDatabase(this)
        val engine = resolveSpeechEngine()
        Log.i("AssetInventoryTTS", "creating TextToSpeech with engine=$engine")
        tts = if (engine == null) TextToSpeech(this, this) else TextToSpeech(this, this, engine)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            MaterialTheme(lightColorScheme(primary = Color(0xFF075E54), secondary = Color(0xFF26766B))) {
                InventoryRoot(database, voiceStatus.value, ::speakLatest)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            voiceStatus.value = "语音引擎不可用"
            Log.e("AssetInventoryTTS", "TTS initialization failed: $status")
            return
        }
        val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsReady = false
            voiceStatus.value = "中文语音不可用"
            Log.e("AssetInventoryTTS", "Chinese voice is unavailable: $languageResult")
            return
        }
        tts.setSpeechRate(1.7f)
        ttsReady = true
        voiceStatus.value = "正常"
        Log.i("AssetInventoryTTS", "TTS ready, engine=${tts.defaultEngine}, languageResult=$languageResult")
        pendingSpeech?.let { text -> pendingSpeech = null; speakLatest(text) }
    }

    private fun resolveSpeechEngine(): String? {
        val services = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_ALL
        )
        val engines = services.map { it.serviceInfo.packageName }.distinct()
        val configured = Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        val selected = when {
            configured in engines -> configured
            "com.oplus.ttsaccessibilityengine" in engines -> "com.oplus.ttsaccessibilityengine"
            else -> engines.firstOrNull()
        }
        Log.i("AssetInventoryTTS", "configured=$configured available=$engines selected=$selected")
        return selected
    }

    private fun speakLatest(text: String) {
        if (!ttsReady) { pendingSpeech = text; return }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "latest-${System.nanoTime()}")
        if (result == TextToSpeech.ERROR) {
            voiceStatus.value = "播报失败"
            Log.e("AssetInventoryTTS", "speak() returned ERROR for: $text")
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        database.close()
        super.onDestroy()
    }
}
