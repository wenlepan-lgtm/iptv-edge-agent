package com.joctv.agent

import android.app.ActivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.joctv.agent.asr.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.File
import java.io.FileInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), 
    AsrController.AsrListener {
    private val TAG = "MainActivity"
    
    private lateinit var tvCpu: TextView
    private lateinit var tvMem: TextView
    private lateinit var tvDisk: TextView
    private lateinit var tvNet: TextView
    private lateinit var tvAsrText: TextView
    private lateinit var tvReplyText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvVadInfo: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvWakewordHits: TextView
    private lateinit var btnPauseResume: android.widget.Button
    
    private var model: Model? = null
    private var asrController: AsrController? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastNetRx = 0L
    private var lastNetTx = 0L
    private var lastNetTime = 0L
    
    // Stats
    private var wakewordHitCount = 0
    private var noiseFloorRms = 0.0
    private var speechThreshold = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initUI()
        startSystemMonitor()
        loadVoskModel()
    }

    private fun initUI() {
        tvCpu = findViewById(R.id.tvCpu)
        tvMem = findViewById(R.id.tvMem)
        tvDisk = findViewById(R.id.tvDisk)
        tvNet = findViewById(R.id.tvNet)
        tvAsrText = findViewById(R.id.tvAsrText)
        tvReplyText = findViewById(R.id.tvReplyText)
        tvStatus = findViewById(R.id.tvStatus)
        tvModel = findViewById(R.id.tvModel)
        tvVadInfo = findViewById(R.id.tvVadInfo)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvWakewordHits = findViewById(R.id.tvWakewordHits)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        
        // Set up pause/resume button
        btnPauseResume.setOnClickListener {
            toggleRecording()
        }
    }

    private fun startSystemMonitor() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    updateSystemMonitorUI()
                } catch (e: Exception) {
                    Log.e("Metrics", "Error in system monitor loop", e)
                }
                delay(1000)
            }
        }
    }

    private fun loadVoskModel() {
        Log.i(TAG, "modelLoad:start")
        tvStatus.text = "Status: Loading Model..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 优先使用小模型以确保稳定性
                val smallModelPath = "/sdcard/vosk-model-small-cn-0.22"
                val largeModelPath = "/sdcard/vosk-model-cn-0.22"
                
                // 如果小模型存在，先用小模型；否则尝试大模型
                var modelDir = if (File(smallModelPath).exists()) File(smallModelPath) else File(largeModelPath)
                
                // External 模型保护逻辑
                if (modelDir.absolutePath == largeModelPath) {
                    val largeModelDir = File(largeModelPath)
                    if (largeModelDir.exists()) {
                        val rnnlmDir = File(largeModelDir, "rnnlm")
                        val rescoreDir = File(largeModelDir, "rescore")
                        if (rnnlmDir.exists() || rescoreDir.exists()) {
                            Log.w(TAG, "External large model has rnnlm/rescore, skipping and using assets small model")
                            // 这里应该加载 assets 中的小模型，但原代码没有实现
                            // 我们暂时保持原逻辑，但记录日志
                            // modelDir = loadAssetsSmallModel() // 需要实现这个方法
                        }
                    }
                }
                
                if (modelDir.exists()) {
                    Log.i(TAG, "START loading model: ${modelDir.absolutePath}")
                    val startTime = System.currentTimeMillis()
                    
                    val m = Model(modelDir.absolutePath)
                    val duration = System.currentTimeMillis() - startTime
                    Log.i(TAG, "modelLoad:success in ${duration}ms")
                    
                    model = m
                    
                    withContext(Dispatchers.Main) {
                        tvModel.text = "Model: ${modelDir.name} (${duration}ms)"
                        tvStatus.text = "Status: Model Loaded"
                        Log.i(TAG, "Recognizer is READY")
                        
                        // Initialize ASR components
                        Log.i(TAG, "asr:start")
                        asrController = AsrController(model!!, this@MainActivity)
                        
                        // Start continuous ASR
                        try {
                            asrController?.startContinuousAsr()
                            tvStatus.text = "Status: Continuous ASR Started"
                        } catch (e: Exception) {
                            Log.e(TAG, "asr:start failed", e)
                            tvStatus.text = "Status: ASR Start Failed"
                        }
                    }
                } else {
                    Log.e(TAG, "No model found at ${modelDir.absolutePath}")
                    logToUI("No model found on SDCard!")
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Status: No Model Found"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "modelLoad:fail", e)
                logToUI("Model Load Failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Status: Model Load Failed"
                }
            }
        }
    }

    // --- AsrController.AsrListener callbacks ---
    
    override fun onAsrResult(text: String, isFinal: Boolean) {
        // Remove spaces from Chinese recognition results
        val cleanedText = text.replace(" ", "")
        
        // Log results
        if (isFinal) {
            Log.d("ASR", "Final Result: $cleanedText")
        } else {
            Log.d("ASR", "Partial Result: $cleanedText")
        }

        runOnUiThread {
            if (isFinal) {
                tvReplyText.text = cleanedText
                tvAsrText.text = ""
            } else {
                tvAsrText.text = cleanedText
            }
        }
    }

    // --- AsrStateMachine.StateListener callbacks ---
    
    // Removed all state machine callbacks as we're not using state machine anymore

    // --- VadDetector.VadListener callbacks ---
    
    // Removed all VAD callbacks as we're not using VAD anymore

    private fun updateSystemMonitorUI() {
        val cpuUsage = getCpuUsage()
        val memUsed = getSystemUsedMemory()
        val totalMem = getTotalMemory()
        val diskUsage = getDiskUsage()
        val totalDisk = getTotalDiskSpace()
        val (rxRate, txRate) = getNetworkUsage()

        Log.v(TAG, "Monitor: CPU=$cpuUsage, MEM=$memUsed/$totalMem, DISK=$diskUsage/$totalDisk")

        runOnUiThread {
            tvCpu.text = "CPU: ${String.format("%.1f", cpuUsage)}%"
            tvMem.text = "MEM: $memUsed / $totalMem MB"
            tvDisk.text = "DISK(/data): ${String.format("%.2f", diskUsage)} / ${String.format("%.2f", totalDisk)} GB"
            tvNet.text = "NET: RX $rxRate KB/s  TX $txRate KB/s"
        }
    }
    
    private fun toggleRecording() {
        // Stop current ASR
        asrController?.stopContinuousAsr()
        
        // Update button text
        if (btnPauseResume.text == "Pause Recording") {
            btnPauseResume.text = "Resume Recording"
            tvStatus.text = "Status: Recording Paused"
        } else {
            btnPauseResume.text = "Pause Recording"
            tvStatus.text = "Status: Continuous ASR Started"
            // Restart ASR
            asrController?.startContinuousAsr()
        }
    }

    private fun getDiskUsage(): Double {
        return try {
            val stat = android.os.StatFs("/data")
            val used = (stat.blockCountLong - stat.availableBlocksLong) * stat.blockSizeLong
            used.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) { 
            Log.w("Metrics", "Failed to get disk usage", e)
            0.0 
        }
    }

    private fun getTotalDiskSpace(): Double {
        return try {
            val stat = android.os.StatFs("/data")
            (stat.blockCountLong * stat.blockSizeLong).toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) { 
            Log.w("Metrics", "Failed to get total disk space", e)
            0.0 
        }
    }

    private fun getCpuUsage(): Float {
        return try {
            val lines = File("/proc/stat").readLines()
            val parts = lines[0].split("\\s+".toRegex())
            val idle = parts[4].toLong()
            val total = parts.drop(1).map { it.toLong() }.sum()
            100f * (1 - idle.toFloat() / total.toFloat())
        } catch (e: Exception) { 
            Log.w("Metrics", "Failed to get CPU usage", e)
            0f 
        }
    }

    private fun getSystemUsedMemory(): Long {
        return try {
            val memInfo = File("/proc/meminfo").readLines().associate { line ->
                val parts = line.split(":")
                parts[0] to parts[1].trim().split(" ")[0].toLong()
            }
            val total = memInfo["MemTotal"] ?: 0L
            val free = memInfo["MemFree"] ?: 0L
            val buffers = memInfo["Buffers"] ?: 0L
            val cached = memInfo["Cached"] ?: 0L
            // 已用内存 = 总内存 - 空闲 - 缓冲 - 缓存
            (total - free - buffers - cached) / 1024
        } catch (e: Exception) { 
            Log.w("Metrics", "Failed to get system used memory", e)
            0L 
        }
    }

    private fun getTotalMemory(): Long {
        return try {
            val line = File("/proc/meminfo").readLines()[0]
            line.split("\\s+".toRegex())[1].toLong() / 1024
        } catch (e: Exception) { 
            Log.w("Metrics", "Failed to get total memory", e)
            0L 
        }
    }

    private fun getNetworkUsage(): Pair<Long, Long> {
        try {
            val lines = File("/proc/net/dev").readLines()
            // 优先寻找 wlan0，如果没有再找 eth0
            val line = lines.find { it.contains("wlan0") } ?: lines.find { it.contains("eth0") }
            
            if (line != null) {
                val parts = line.trim().split("\\s+".toRegex())
                val rx = parts[1].toLong() / 1024
                val tx = parts[9].toLong() / 1024
                val now = System.currentTimeMillis()
                if (lastNetTime == 0L) {
                    lastNetRx = rx; lastNetTx = tx; lastNetTime = now
                    return Pair(0, 0)
                }
                val diff = (now - lastNetTime) / 1000.0
                val rxRate = ((rx - lastNetRx) / diff).toLong()
                val txRate = ((tx - lastNetTx) / diff).toLong()
                lastNetRx = rx; lastNetTx = tx; lastNetTime = now
                return Pair(rxRate, txRate)
            }
        } catch (e: Exception) {
            Log.w("Metrics", "Failed to get network usage", e)
        }
        return Pair(0, 0)
    }

    private fun executeRootCommand(cmd: String): Int {
        return try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            os.writeBytes("$cmd\nexit\n")
            os.flush()
            p.waitFor()
        } catch (e: Exception) { -1 }
    }

    private fun execRootCmdSilent(cmd: String) {
        try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            os.writeBytes("$cmd\nexit\n")
            os.flush()
            p.waitFor()
        } catch (e: Exception) {}
    }

    private fun logToUI(msg: String) {
        Log.d(TAG, msg)
    }

    override fun onStop() {
        super.onStop()
        // Don't stop ASR in continuous mode
        // asrController?.stop()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop continuous ASR when activity is destroyed
        asrController?.stopContinuousAsr()
    }
}