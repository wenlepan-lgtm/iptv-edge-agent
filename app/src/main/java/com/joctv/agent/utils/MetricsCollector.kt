package com.joctv.agent.utils

import android.os.StatFs
import android.util.Log
import java.io.File
import kotlin.math.roundToInt

data class MetricsSnapshot(
    val cpuPercent: Int?,
    val memUsedMb: Int?,
    val memTotalMb: Int?,
    val diskUsedGb: Float?,
    val diskTotalGb: Float?,
    val netIf: String?,
    val netRxKbps: Double?,
    val netTxKbps: Double?,
    val npuLoad: String?,
    val gpuFreq: String?,
    val gpuLoad: String?,
    val cpuModel: String?,
    val cpuCores: Int?,
    val cpuFreqMax: Int?
)

class MetricsCollector(private val debug: Boolean = false) {

    private var lastCpu: LongArray? = null
    private var lastNet: Pair<Long, Long>? = null
    private var lastNetIf: String? = null

    fun collectSnapshot(): MetricsSnapshot {
        val cpu = safe { readCpuPercent() }
        val mem = safe { readMemMb() }
        val disk = safe { readDiskGb() }
        val net = safe { readNetKbps() }
        val npu = safe { readNpuLoad() }
        val gpu = safe { readGpuInfo() }
        val cpuModel = safe { getSystemProperty("ro.board.platform") } ?: "rk3576"
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val cpuFreqMax = safe { readCpuFreqMax() } ?: 2208

        return MetricsSnapshot(
            cpuPercent = cpu,
            memUsedMb = mem?.first,
            memTotalMb = mem?.second,
            diskUsedGb = disk?.first,
            diskTotalGb = disk?.second,
            netIf = net?.first,
            netRxKbps = net?.second,
            netTxKbps = net?.third,
            npuLoad = npu,
            gpuFreq = gpu?.first,
            gpuLoad = gpu?.second,
            cpuModel = cpuModel,
            cpuCores = cpuCores,
            cpuFreqMax = cpuFreqMax
        )
    }

    private fun readCpuFreqMax(): Int? {
        return try {
            val freqStr = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim()
            freqStr.toInt() / 1000
        } catch (e: Exception) {
            null
        }
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            process.inputStream.bufferedReader().use { it.readLine()?.trim() }
        } catch (e: Exception) {
            null
        }
    }

    private fun readCpuPercent(): Int {
        val now = readProcStat()
        val last = lastCpu
        lastCpu = now
        if (last == null) return 0

        val lastIdle = last[3] + last[4]
        val idle = now[3] + now[4]

        val lastTotal = last.sum()
        val total = now.sum()

        val totalDiff = (total - lastTotal).toFloat()
        val idleDiff = (idle - lastIdle).toFloat()
        if (totalDiff <= 0) return 0
        val usage = (100f * (1f - idleDiff / totalDiff)).roundToInt()

        if (debug) Log.d("Metrics", "cpu usage=$usage")
        return usage.coerceIn(0, 100)
    }

    private fun readProcStat(): LongArray {
        val line = File("/proc/stat").readLines().first { it.startsWith("cpu ") }
        val parts = line.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        val arr = LongArray(parts.size)
        for (i in parts.indices) arr[i] = parts[i]
        return arr
    }

    private fun readMemMb(): Pair<Int, Int> {
        val lines = File("/proc/meminfo").readLines()
        val totalKb = lines.first { it.startsWith("MemTotal:") }.split(Regex("\\s+"))[1].toLong()
        val availKb = lines.first { it.startsWith("MemAvailable:") }.split(Regex("\\s+"))[1].toLong()
        val usedKb = totalKb - availKb
        val totalMb = (totalKb / 1024).toInt()
        val usedMb = (usedKb / 1024).toInt()
        return usedMb to totalMb
    }

    private fun readDiskGb(): Pair<Float, Float> {
        val stat = StatFs("/data")
        val total = stat.totalBytes.toDouble()
        val free = stat.availableBytes.toDouble()
        val used = total - free
        val totalGb = (total / (1024.0 * 1024 * 1024)).toFloat()
        val usedGb = (used / (1024.0 * 1024 * 1024)).toFloat()
        return usedGb to totalGb
    }

    data class Triple<A, B, C>(val first: A, val second: B, val third: C)

    private fun readNetKbps(): Triple<String, Double, Double> {
        val iface = "wlan0"
        val (rx, tx) = readNetDev(iface)

        val last = lastNet
        val lastIface = lastNetIf
        lastNet = rx to tx
        lastNetIf = iface

        if (last == null || lastIface != iface) {
            return Triple(iface, 0.0, 0.0)
        }

        val rxDiff = (rx - last.first).coerceAtLeast(0)
        val txDiff = (tx - last.second).coerceAtLeast(0)

        val rxKb = rxDiff / 1024.0
        val txKb = txDiff / 1024.0

        return Triple(iface, rxKb, txKb)
    }

    private fun pickIface(): String {
        val candidates = listOf("wlan0", "eth0")
        for (c in candidates) if (File("/sys/class/net/$c").exists()) return c
        // fallback: pick first non-lo
        val all = File("/sys/class/net").list()?.toList() ?: emptyList()
        return all.firstOrNull { it != "lo" } ?: "lo"
    }

    private fun readNetDev(iface: String): Pair<Long, Long> {
        val lines = File("/proc/net/dev").readLines()
        val row = lines.firstOrNull { it.trim().startsWith("$iface:") } ?: return 0L to 0L
        val parts = row.replace(":", " ").trim().split(Regex("\\s+"))
        // parts[0]=iface, parts[1]=rx_bytes, parts[9]=tx_bytes
        val rx = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val tx = parts.getOrNull(9)?.toLongOrNull() ?: 0L
        return rx to tx
    }

    private fun readNpuLoad(): String {
        val f = File("/sys/kernel/debug/rknpu/load")
        if (!f.exists()) return "N/A"
        val s = f.readText().trim()
        return s.removePrefix("NPU load:").trim()
    }

    private fun readGpuInfo(): Pair<String?, String?> {
        val base = File("/sys/class/devfreq")
        if (!base.exists()) return null to null
        val dirs = base.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val gpuDir = dirs.firstOrNull { it.name.contains("gpu", true) || it.name.contains("mali", true) }
            ?: return null to null

        val freq = safe { File(gpuDir, "cur_freq").readText().trim() }
        val load = safe { File(gpuDir, "load").readText().trim() } // 有些系统没有
        return freq to (load?.let { "load=$it" })
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Throwable) { null }
}