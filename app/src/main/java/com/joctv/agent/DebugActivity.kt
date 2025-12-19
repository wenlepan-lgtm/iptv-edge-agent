package com.joctv.agent

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DebugActivity : AppCompatActivity() {
    
    // 监控态UI组件
    private lateinit var tvAudioInfo: TextView
    private lateinit var tvRecInfo: TextView
    private lateinit var tvReadInfo: TextView
    private lateinit var tvVoskInfo: TextView
    private lateinit var tvSysInfo: TextView
    private lateinit var tvCpuInfo: TextView
    private lateinit var tvGpuInfo: TextView
    private lateinit var tvNupInfo: TextView
    private lateinit var tvMemInfo: TextView
    private lateinit var tvDiskInfo: TextView
    private lateinit var tvNetInfo: TextView
    private lateinit var tvErrorInfo: TextView
    
    // 操作按钮
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnClear: Button
    private lateinit var btnSaveLog: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        
        bindViews()
    }
    
    private fun bindViews() {
        // 监控态UI组件
        tvAudioInfo = findViewById(R.id.tvAudioInfo)
        tvRecInfo = findViewById(R.id.tvRecInfo)
        tvReadInfo = findViewById(R.id.tvReadInfo)
        tvVoskInfo = findViewById(R.id.tvVoskInfo)
        tvSysInfo = findViewById(R.id.tvSysInfo)
        tvCpuInfo = findViewById(R.id.tvCpuInfo)
        tvGpuInfo = findViewById(R.id.tvGpuInfo)
        tvNupInfo = findViewById(R.id.tvNupInfo)
        tvMemInfo = findViewById(R.id.tvMemInfo)
        tvDiskInfo = findViewById(R.id.tvDiskInfo)
        tvNetInfo = findViewById(R.id.tvNetInfo)
        tvErrorInfo = findViewById(R.id.tvErrorInfo)
        
        // 操作按钮
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnClear = findViewById(R.id.btnClear)
        btnSaveLog = findViewById(R.id.btnSaveLog)
        val btnBack = findViewById<Button>(R.id.btnBack)
        
        // 设置按钮点击事件
        btnStart.setOnClickListener {
            // TODO: 实现开始功能
        }
        
        btnStop.setOnClickListener {
            // TODO: 实现停止功能
        }
        
        btnClear.setOnClickListener {
            // TODO: 实现清空日志功能
        }
        
        btnSaveLog.setOnClickListener {
            // TODO: 实现保存日志功能
        }
        
        btnBack.setOnClickListener {
            // 返回产品态界面
            val intent = android.content.Intent(this, MainActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }
}