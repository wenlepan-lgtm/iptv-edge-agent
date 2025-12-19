package com.example.androidtvspeechrecognition

import android.app.ActivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.FileInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    
    private lateinit var tvCpu: TextView
    private lateinit var tvMem: TextView
    private lateinit var tvDisk: TextView
    private lateinit var tvNet: TextView
    private lateinit var tvAsrText: TextView
    private lateinit var tvReplyText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvModel: TextView
    
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastNetRx = 0L
    private var lastNetTx = 0L
    private var lastNetTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initUI()
        startSystemMonitor()
        startVoskAndTinycap()
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
    }

    private fun startSystemMonitor() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                updateSystemMonitorUI()
                delay(1000)
            }
        }
    }

    private fun startVoskAndTinycap() {
        // 1. 加载模型
        loadVoskModel()
        // 2. 启动流式识别
        runTinycapStreamingPipeline()
    }

    private fun loadVoskModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 优先使用小模型以确保稳定性
            val smallModelPath = "/sdcard/vosk-model-small-cn-0.22"
            val largeModelPath = "/sdcard/vosk-model-cn-0.22"
            
            // 如果小模型存在，先用小模型；否则尝试大模型
            val modelDir = if (File(smallModelPath).exists()) File(smallModelPath) else File(largeModelPath)
            
            if (modelDir.exists()) {
                try {
                    Log.i(TAG, "START loading model: ${modelDir.absolutePath}")
                    val startTime = System.currentTimeMillis()
                    
                    val m = Model(modelDir.absolutePath)
                    val duration = System.currentTimeMillis() - startTime
                    Log.i(TAG, "FINISH loading model in ${duration}ms")

                    withContext(Dispatchers.Main) {
                        model = m
                        recognizer = Recognizer(model!!, 16000.0f)
                        tvModel.text = "Model: ${modelDir.name} (${duration}ms)"
                        tvStatus.text = "Status: Ready"
                        Log.i(TAG, "Recognizer is READY")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Model Load Failed", e)
                    logToUI("Model Load Failed: ${e.message}")
                }
            } else {
                Log.e(TAG, "No model found at ${modelDir.absolutePath}")
                logToUI("No model found on SDCard!")
            }
        }
    }

    private fun runTinycapStreamingPipeline() {
        lifecycleScope.launch(Dispatchers.IO) {
            // --- 音频优化参数 ---
            val gain = 15.0f          // 增益：从 10.0 提升到 15.0，进一步增强拾音
            val selectedChannel = 2   // 声道：切换到第 3 个通道 (索引为2)
            // ------------------

            while (true) {
                while (recognizer == null) delay(500)

                logToUI("--- Starting Real-time FIFO Streaming ---")
                try {
                    execRootCmdSilent("stop audioserver")
                    delay(500)

                    val fifoPath = "/data/local/tmp/asr_fifo"
                    execRootCmdSilent("rm -f $fifoPath && mkfifo $fifoPath && chmod 666 $fifoPath")

                    lifecycleScope.launch(Dispatchers.IO) {
                        executeRootCommand("tinycap $fifoPath -D 2 -d 0 -c 8 -r 16000")
                    }
                    
                    delay(500)

                    val fis = FileInputStream(fifoPath)
                    val channels = 8
                    val sampleSize = 2
                    val frameSize = channels * sampleSize
                    val framesPerRead = 1024
                    val bufferSize = frameSize * framesPerRead
                    val readBuffer = ByteArray(bufferSize)
                    val monoBuffer = ShortArray(framesPerRead)

                    logToUI("FIFO opened, Channel: $selectedChannel, Gain: $gain")

                    var logCounter = 0

                    while (true) {
                        var bytesRead = 0
                        while (bytesRead < bufferSize) {
                            val r = fis.read(readBuffer, bytesRead, bufferSize - bytesRead)
                            if (r == -1) break
                            bytesRead += r
                        }
                        if (bytesRead < bufferSize) break

                        var sumSq = 0.0
                        for (i in 0 until framesPerRead) {
                            // 定位到选中通道的字节位置
                            val idx = i * frameSize + (selectedChannel * sampleSize)
                            val low = readBuffer[idx].toInt() and 0xFF
                            val high = readBuffer[idx + 1].toInt()
                            val sample = ((high shl 8) or low).toShort()
                            
                            // 应用增益
                            val amplified = sample.toFloat() * gain
                            val finalSample = amplified.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
                            
                            monoBuffer[i] = finalSample
                            sumSq += (finalSample.toDouble() * finalSample.toDouble())
                        }

                        // 每隔约 1 秒打印一次音量水平，帮助调试增益
                        if (++logCounter % 15 == 0) {
                            val rms = sqrt(sumSq / framesPerRead)
                            Log.v(TAG, "Audio Level (RMS): ${String.format("%.2f", rms)}")
                        }

                        if (recognizer!!.acceptWaveForm(monoBuffer, monoBuffer.size)) {
                            val res = recognizer!!.result
                            updateAsrUI(res, true)
                        } else {
                            val partial = recognizer!!.partialResult
                            updateAsrUI(partial, false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming Error", e)
                } finally {
                    execRootCmdSilent("killall tinycap")
                    execRootCmdSilent("start audioserver")
                    logToUI("Streaming stopped, restarting...")
                }
                delay(2000)
            }
        }
    }

    private fun updateAsrUI(json: String, isFinal: Boolean) {
        try {
            val jobj = JSONObject(json)
            var text = if (isFinal) {
                // 兼容不同模型的 key
                jobj.optString("text").ifEmpty { jobj.optString("result") }
            } else {
                jobj.optString("partial")
            }
            
            if (text.isNullOrEmpty()) return
            
            // 去除中文识别结果中的空格
            text = text.replace(" ", "")

            runOnUiThread {
                if (isFinal) {
                    Log.d(TAG, "Final Result: $text")
                    tvReplyText.text = text
                    tvAsrText.text = ""
                } else {
                    tvAsrText.text = text
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UI Update Error", e)
        }
    }

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

    private fun getDiskUsage(): Double {
        return try {
            val stat = android.os.StatFs("/data")
            val used = (stat.blockCountLong - stat.availableBlocksLong) * stat.blockSizeLong
            used.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) { 0.0 }
    }

    private fun getTotalDiskSpace(): Double {
        return try {
            val stat = android.os.StatFs("/data")
            (stat.blockCountLong * stat.blockSizeLong).toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) { 0.0 }
    }

    private fun getCpuUsage(): Float {
        return try {
            val lines = File("/proc/stat").readLines()
            val parts = lines[0].split("\\s+".toRegex())
            val idle = parts[4].toLong()
            val total = parts.drop(1).map { it.toLong() }.sum()
            100f * (1 - idle.toFloat() / total.toFloat())
        } catch (e: Exception) { 0f }
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
        } catch (e: Exception) { 0L }
    }

    private fun getTotalMemory(): Long {
        return try {
            val line = File("/proc/meminfo").readLines()[0]
            line.split("\\s+".toRegex())[1].toLong() / 1024
        } catch (e: Exception) { 0L }
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
        } catch (e: Exception) {}
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
}
