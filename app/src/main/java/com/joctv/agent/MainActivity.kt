package com.joctv.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.joctv.agent.asr.AsrController
import com.joctv.agent.intent.IntentRouter
import com.joctv.agent.intent.RouteResult
import com.joctv.agent.intent.RouteType
import com.joctv.agent.intent.SlotValue
import com.joctv.agent.tts.TTSManager
import com.joctv.agent.utils.MetricsCollector
import com.joctv.agent.web.WebAnswerClient
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val ASR_UNMUTE_DELAY_MS = 500L

class MainActivity : AppCompatActivity(), AsrController.AsrListener, TTSManager.TTSListener {
    private val TAG = "MainActivity"
    private val PERMISSIONS_REQUEST_RECORD_AUDIO = 1

    private lateinit var tvCpu: TextView
    private lateinit var tvCpuModel: TextView
    private lateinit var tvMem: TextView
    private lateinit var tvDisk: TextView
    private lateinit var tvNet: TextView
    private lateinit var tvNpu: TextView
    private lateinit var tvGpu: TextView
    private lateinit var tvReplyText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvVadInfo: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvWakewordHits: TextView
    private lateinit var btnPauseResume: Button
    private lateinit var btnTtsReplay: Button
    private lateinit var btnTtsStop: Button
    private lateinit var vStatusLight: View
    private lateinit var ivPauseIcon: ImageView
    private lateinit var pbVolume: ProgressBar
    private lateinit var tvAsrPartial: TextView
    private lateinit var tvAsrFinal: TextView
    
    private var toneGenerator: ToneGenerator? = null
    private lateinit var tvRouterDecision: TextView
    private lateinit var tvCoreAsrPartial: TextView
    private lateinit var tvCoreAsrFinal: TextView
    private lateinit var tvCoreRouterDecision: TextView
    private lateinit var tvOutputText: TextView
    private lateinit var outputTextScrollView: ScrollView
    private lateinit var tvLlmLog: TextView
    private lateinit var llmLogScrollView: ScrollView
    private lateinit var tvBroadcastLog: TextView
    private lateinit var broadcastLogScrollView: ScrollView
    private lateinit var videoView: VideoView
    private lateinit var btnVideoPlayPause: Button
    private lateinit var btnVideoVolumeDown: Button
    private lateinit var btnVideoVolumeUp: Button

    private var isVideoPlaying = true
    private var currentVolume = 0.5f

    private var ttsManager: TTSManager? = null
    private var asrController: AsrController? = null
    private var intentRouter: IntentRouter? = null
    private var webAnswerClient: WebAnswerClient? = null
    private var model: Model? = null
    
    private var reqIdCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initUI()
        startSystemMonitor()
        // initTTS() // 移至模型加载后延迟执行
        initIntentRouter()
        initWebAnswerClient()
        
        checkPermissionsAndLoadModel()
        
        Handler(Looper.getMainLooper()).postDelayed({
            logViewCoordinates()
        }, 1000)
    }

    private fun initUI() {
        tvCpu = findViewById(R.id.tvCpu)
        tvCpuModel = findViewById(R.id.tvCpuModel)
        tvMem = findViewById(R.id.tvMem)
        tvDisk = findViewById(R.id.tvDisk)
        tvNet = findViewById(R.id.tvNet)
        tvNpu = findViewById(R.id.tvNpu)
        tvGpu = findViewById(R.id.tvGpu)
        tvReplyText = findViewById(R.id.tvReplyText)
        tvStatus = findViewById(R.id.tvStatus)
        tvModel = findViewById(R.id.tvModel)
        tvVadInfo = findViewById(R.id.tvVadInfo)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvWakewordHits = findViewById(R.id.tvWakewordHits)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnTtsReplay = findViewById(R.id.btnTtsReplay)
        btnTtsStop = findViewById(R.id.btnTtsStop)
        vStatusLight = findViewById(R.id.vStatusLight)
        ivPauseIcon = findViewById(R.id.ivPauseIcon)
        pbVolume = findViewById(R.id.pbVolume)
        tvAsrPartial = findViewById(R.id.tvAsrPartial)
        
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        pbVolume.progress = (currentVolume * 100).toInt()
        tvAsrFinal = findViewById(R.id.tvAsrFinal)
        tvRouterDecision = findViewById(R.id.tvRouterDecision)
        tvCoreAsrPartial = findViewById(R.id.tvCoreAsrPartial)
        tvCoreAsrFinal = findViewById(R.id.tvCoreAsrFinal)
        tvCoreRouterDecision = findViewById(R.id.tvCoreRouterDecision)
        tvOutputText = findViewById(R.id.tvOutputText)
        outputTextScrollView = findViewById(R.id.outputTextScrollView)
        tvLlmLog = findViewById(R.id.tvLlmLog)
        llmLogScrollView = findViewById(R.id.llmLogScrollView)
        tvBroadcastLog = findViewById(R.id.tvBroadcastLog)
        broadcastLogScrollView = findViewById(R.id.broadcastLogScrollView)
        videoView = findViewById(R.id.videoView)
        btnVideoPlayPause = findViewById(R.id.btnVideoPlayPause)
        btnVideoVolumeUp = findViewById(R.id.btnVideoVolumeUp)
        btnVideoVolumeDown = findViewById(R.id.btnVideoVolumeDown)
        
        btnPauseResume.setOnClickListener { toggleRecording() }
        btnTtsReplay.setOnClickListener { replayTts() }
        btnTtsStop.setOnClickListener { stopTts() }
        btnVideoPlayPause.setOnClickListener { toggleVideoPlayback() }
        btnVideoVolumeDown.setOnClickListener { adjustVideoVolume(false) }
        btnVideoVolumeUp.setOnClickListener { adjustVideoVolume(true) }
        
        initVideoPlayer()
    }

    private fun startSystemMonitor() {
        val metricsCollector = MetricsCollector()
        lifecycleScope.launch {
            while (isActive) {
                val snap = withContext(Dispatchers.IO) { metricsCollector.collectSnapshot() }
                tvCpu.text = "CPU: ${snap.cpuPercent ?: 0}%"
                tvCpuModel.text = "Model: ${snap.cpuModel ?: "rk3576"}  ${snap.cpuCores ?: 8} Processor  ${snap.cpuFreqMax ?: 2208}MHz"
                tvMem.text = "MEM: ${snap.memUsedMb ?: 0}MB / ${snap.memTotalMb ?: 0}MB"
                tvDisk.text = "DISK: ${String.format("%.1f", snap.diskUsedGb ?: 0f)}GB / ${String.format("%.1f", snap.diskTotalGb ?: 0f)}GB"
                tvNet.text = "NET: IN: ${String.format("%.2f", snap.netRxKbps ?: 0.0)} KB/s  OUT: ${String.format("%.2f", snap.netTxKbps ?: 0.0)} KB/s"
                tvNpu.text = "NPU: ${snap.npuLoad ?: "0%"}"
                tvGpu.text = "GPU: ${snap.gpuFreq ?: "0MHz"}"
                delay(1000)
            }
        }
    }

    private fun checkPermissionsAndLoadModel() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_RECORD_AUDIO)
        } else {
            loadVoskModel()
        }
    }

    private fun loadVoskModel() {
        lifecycleScope.launch {
            tvStatus.text = "Status: ASR Initializing..."
            val startTime = System.currentTimeMillis() // 记录开始时间
            try {
                val modelName = "vosk-model-small-cn-0.22"
                val extDir = File("/sdcard/$modelName")
                val extOk = extDir.exists() && File(extDir, "conf/model.conf").exists()
                
                val loadedModel = withContext(Dispatchers.IO) {
                    if (extOk) Model(extDir.absolutePath)
                    else suspendCancellableCoroutine { cont ->
                        StorageService.unpack(this@MainActivity, "model", "model", { m -> cont.resume(m) }, { e -> cont.resumeWithException(e) })
                    }
                }
                
                // Calculate duration
                val duration = System.currentTimeMillis() - startTime
                
                model = loadedModel
                asrController = AsrController(loadedModel, this@MainActivity)
                asrController?.startContinuousAsr()
                
                tvStatus.text = "Status: ASR Ready"
                tvModel.text = "Model: $modelName (loaded in ${duration}ms)" // 显示加载耗时
                addJsonBroadcastLog("SYSTEM", "INIT", null, "System initialized", null)
                
                // 核心修复：模型就绪后，先检查TTS数据，再延迟初始化TTS
                checkTtsDataAndInit()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                tvStatus.text = "Status: Error - ${e.message}"
            }
        }
    }

    private fun initTTS() { 
        try {
            ttsManager = TTSManager(this, this) 
        } catch (e: Exception) {
            Log.e(TAG, "TTS initialization failed", e)
            tvStatus.text = "Status: TTS Init Failed"
        }
    }

    private fun checkTtsDataAndInit() {
        try {
            Log.d(TAG, "Checking TTS data...")
            val checkIntent = Intent()
            checkIntent.action = TextToSpeech.Engine.ACTION_CHECK_TTS_DATA
            // 显式指定引擎进行检查
            checkIntent.setPackage("com.k2fsa.sherpa.onnx.tts.engine")
            
            // 延迟 1 秒初始化，给系统服务留出响应时间
            Handler(Looper.getMainLooper()).postDelayed({
                initTTS()
            }, 1000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking TTS data", e)
            initTTS() // 降级直接初始化
        }
    }
    private fun initIntentRouter() { intentRouter = IntentRouter(this) }
    private fun initWebAnswerClient() { webAnswerClient = WebAnswerClient(this) }

    private fun toggleRecording() { 
        asrController?.let {
            if (it.isPaused()) {
                it.resume()
                btnPauseResume.text = "暂停"
                ivPauseIcon.visibility = View.GONE
                vStatusLight.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                playBeep(ToneGenerator.TONE_PROP_ACK)
            } else {
                it.pause()
                btnPauseResume.text = "开始"
                ivPauseIcon.visibility = View.VISIBLE
                vStatusLight.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                playBeep(ToneGenerator.TONE_PROP_BEEP)
            }
        }
    }

    private fun playBeep(toneType: Int) {
        toneGenerator?.startTone(toneType, 150)
    }

    private fun replayTts() {
        val text = tvOutputText.text.toString()
        if (text.isNotEmpty()) {
            Log.d(TAG, "Replaying TTS: $text")
            playBeep(ToneGenerator.TONE_PROP_PROMPT)
            vStatusLight.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
            ttsManager?.speak(text)
        } else {
            Toast.makeText(this, "没有可播报的内容", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopTts() {
        Log.d(TAG, "Stopping TTS")
        ttsManager?.stop()
        vStatusLight.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        playBeep(ToneGenerator.TONE_PROP_BEEP2)
    }

    // --- AsrController.AsrListener ---
    override fun onAsrResult(text: String, isFinal: Boolean) {
        val cleanedText = text.replace(" ", "")
        runOnUiThread {
            if (isFinal) {
                tvAsrFinal.text = cleanedText
                tvCoreAsrFinal.text = "ASR_FINAL: $cleanedText"
                tvReplyText.text = cleanedText
                tvAsrPartial.text = ""
                tvCoreAsrPartial.text = "ASR_PARTIAL: "
                processFinalResult(cleanedText)
            } else {
                tvAsrPartial.text = cleanedText
                tvCoreAsrPartial.text = "ASR_PARTIAL: $cleanedText"
            }
        }
    }

    // --- TTSManager.TTSListener ---
    override fun onTTSReady() { 
        Log.d(TAG, "TTS Ready")
        runOnUiThread { 
            tvStatus.text = "Status: ASR Ready (TTS OK)"
            // 将 TTS 状态追加到 LLM LOG
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLlmLog.append("\n[$currentTime] [TTS] Ready")
        }
    }
    override fun onTTSStart() { 
        Log.d(TAG, "TTS Start - Muting ASR")
        runOnUiThread { 
            asrController?.setMuted(true)
            vStatusLight.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
            // 将 TTS 状态追加到 LLM LOG
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLlmLog.append("\n[$currentTime] [TTS] Speaking: ${tvOutputText.text.toString().take(20)}...")
        }
    }
    override fun onTTSSpeak() { 
        Log.d(TAG, "TTS Speaking")
        runOnUiThread { btnTtsReplay.isEnabled = false }
    }
    override fun onTTSDone() { 
        Log.d(TAG, "TTS Done - Unmuting ASR in ${ASR_UNMUTE_DELAY_MS}ms")
        runOnUiThread { 
            // 延迟恢复 ASR，防止 TTS 尾音被误识别
            Handler(Looper.getMainLooper()).postDelayed({
                asrController?.setMuted(false)
                Log.d(TAG, "ASR unmuted after TTS")
            }, ASR_UNMUTE_DELAY_MS)
            
            btnTtsReplay.isEnabled = true
            vStatusLight.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            // 清除高亮
            tvOutputText.text = tvOutputText.text.toString()
            
            // 将 TTS 状态追加到 LLM LOG
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLlmLog.append("\n[$currentTime] [TTS] Done")
        }
    }
    override fun onTTSError(error: String) { 
        Log.e(TAG, "TTS Error: $error")
        runOnUiThread { 
            tvStatus.text = "Status: TTS Error"
            vStatusLight.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            btnTtsReplay.isEnabled = true
            
            // 将 TTS 状态追加到 LLM LOG
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLlmLog.append("\n[$currentTime] [TTS] Error: $error")
        }
    }
    override fun onTTSProgress(start: Int, end: Int) {
        runOnUiThread {
            val fullText = tvOutputText.text.toString()
            if (start < fullText.length && end <= fullText.length) {
                val spannable = SpannableString(fullText)
                spannable.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.holo_orange_light)),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                tvOutputText.text = spannable
            }
        }
    }

    private fun processFinalResult(text: String) {
        if (text.isEmpty()) return
        val routeResult = intentRouter?.route(text)
        runOnUiThread {
            val biz = routeResult?.biz ?: "Internet"
            val displayBiz = if (routeResult?.type == RouteType.LOCAL_COMMAND) "Local" else "Internet"
            tvCoreRouterDecision.text = "ROUTER_DECISION: $displayBiz"
            addJsonBroadcastLog(biz, routeResult?.intent, text, routeResult?.reply, routeResult?.slotValues)
            when (routeResult?.type) {
                RouteType.LOCAL_COMMAND -> handleLocalCommand(routeResult)
                RouteType.WEB_QUERY -> handleWebQuery(routeResult)
                RouteType.LLM -> handleLLMQuery(routeResult)
                else -> handleLLMQuery(RouteResult(RouteType.LLM, query = text))
            }
        }
    }

    private fun handleLocalCommand(routeResult: RouteResult) {
        val replyText = routeResult.reply ?: "好的，已为您处理。"
        tvOutputText.text = replyText
        outputTextScrollView.post { outputTextScrollView.fullScroll(View.FOCUS_DOWN) }
        if (ttsManager?.isReady() == true) ttsManager?.speak(replyText)
    }

    private fun handleWebQuery(routeResult: RouteResult) {
        webAnswerClient?.getAnswer(routeResult.query ?: "") { result ->
            runOnUiThread {
                val text = if (result.success) cleanMarkdown(result.answerText) else result.answerText
                tvOutputText.text = text
                outputTextScrollView.post { outputTextScrollView.fullScroll(View.FOCUS_DOWN) }
                if (ttsManager?.isReady() == true) ttsManager?.speak(text)
            }
        }
    }
    
    private fun handleLLMQuery(routeResult: RouteResult) {
        val replyText = "正在处理您的请求，请稍等。"
        tvOutputText.text = replyText
        outputTextScrollView.post { outputTextScrollView.fullScroll(View.FOCUS_DOWN) }
        ttsManager?.speak(replyText)
    }

    private fun cleanMarkdown(text: String): String = text.replace(Regex("[#*`_~]"), "")

    private fun addJsonBroadcastLog(biz: String, intent: String?, query: String?, reply: String?, slotValues: List<SlotValue>?) {
        fun jsonEscape(s: String?): String {
            if (s == null) return ""
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        }
        val timestamp = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date(timestamp))
        val counter = synchronized(this) { reqIdCounter++; reqIdCounter }
        val reqId = "${timeStr}-${String.format("%06d", counter)}"
        
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("    \"ver\": 1,\n")
        sb.append("    \"type\": \"command\",\n")
        sb.append("    \"biz\": \"${jsonEscape(biz)}\",\n")
        sb.append("    \"intent\": \"${jsonEscape(intent ?: "UNKNOWN")}\",\n")
        sb.append("    \"reqId\": \"${jsonEscape(reqId)}\",\n")
        sb.append("    \"source\": {\n")
        sb.append("        \"app\": \"com.joctv.agent\"\n")
        sb.append("    },\n")
        sb.append("    \"payload\": {\n")
        sb.append("        \"query\": \"${jsonEscape(query ?: "")}\",\n")
        sb.append("        \"reply\": \"${jsonEscape(reply ?: "")}\"")
        
        slotValues?.forEach { slot ->
            sb.append(",\n")
            if (slot.value is Number) {
                sb.append("        \"${jsonEscape(slot.name)}\": ${slot.value}")
            } else {
                sb.append("        \"${jsonEscape(slot.name)}\": \"${jsonEscape(slot.value.toString())}\"")
            }
        }
        
        sb.append("\n    }\n")
        sb.append("}")
        
        val jsonLog = sb.toString()
        Log.e("BROADCAST_TX", jsonLog)
        Log.d(TAG, "UI_LOG_APPEND: length=${jsonLog.length}, startsWith={: ${jsonLog.startsWith("{")}}")
        
        runOnUiThread {
            // 如果日志太长，清理一下，防止内存溢出或渲染卡顿
            if (tvBroadcastLog.text.length > 10000) {
                tvBroadcastLog.text = ""
            }
            
            if (tvBroadcastLog.text.isNotEmpty()) {
                tvBroadcastLog.append("\n\n")
            }
            
            // 记录当前滚动位置
            val currentScrollY = broadcastLogScrollView.scrollY
            
            tvBroadcastLog.append(jsonLog)
            
            // 智能滚动：确保新日志块的顶部可见
            broadcastLogScrollView.postDelayed({
                broadcastLogScrollView.fullScroll(View.FOCUS_DOWN)
            }, 50)
        }
    }

    private fun initVideoPlayer() {
        try {
            val videoFile = File("/sdcard/1.mp4")
            if (!videoFile.exists()) {
                tvOutputText.text = "视频文件不存在: /sdcard/1.mp4"
                return
            }
            videoView.setVideoPath(videoFile.absolutePath)
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(currentVolume, currentVolume)
                // 移除自动播放
                // videoView.start()
            }
            videoView.setOnErrorListener { _, what, extra ->
                runOnUiThread { tvOutputText.text = "视频播放失败 (Error: $what, $extra)" }
                true
            }
        } catch (e: Exception) { Log.e(TAG, "Video player init failed", e) }
    }

    private fun toggleVideoPlayback() {
        if (videoView.isPlaying) { videoView.pause(); btnVideoPlayPause.text = "播放" }
        else { videoView.start(); btnVideoPlayPause.text = "暂停" }
    }

    private fun adjustVideoVolume(increase: Boolean) {
        val oldVolume = currentVolume
        currentVolume = if (increase) (currentVolume + 0.1f).coerceAtMost(1.0f) else (currentVolume - 0.1f).coerceAtLeast(0.0f)
        
        if (currentVolume != oldVolume) {
            pbVolume.progress = (currentVolume * 100).toInt()
            playBeep(ToneGenerator.TONE_PROP_ACK)
            // 如果达到极值，播放特殊提示音
            if (currentVolume == 1.0f || currentVolume == 0.0f) {
                playBeep(ToneGenerator.TONE_CDMA_PIP)
            }
        } else {
            // 已经在极值，播放警告音
            playBeep(ToneGenerator.TONE_CDMA_LOW_L)
        }
    }

    private fun logViewCoordinates() {
        val views = listOf(R.id.videoContainer, R.id.llmLogScrollView, R.id.broadcastLogScrollView)
        views.forEach { id ->
            val view = findViewById<View>(id)
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            Log.d("ViewCheck", "View ID: ${resources.getResourceEntryName(id)}, bottom: ${location[1] + view.height}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        asrController?.stopContinuousAsr()
        ttsManager?.shutdown()
    }
}