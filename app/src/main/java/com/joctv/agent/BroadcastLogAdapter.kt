package com.joctv.agent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BroadcastLogAdapter(private val logList: MutableList<String>) : RecyclerView.Adapter<BroadcastLogAdapter.LogViewHolder>() {

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val logTextView: TextView = itemView.findViewById(R.id.tvLogItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_broadcast_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.logTextView.text = logList[position]
    }

    override fun getItemCount(): Int = logList.size

    fun addLog(log: String) {
        // 临时移除所有过滤逻辑，直接显示所有事件
        // 添加新日志到列表开头
        logList.add(0, log)
        
        // 如果超过最大条目数，移除最旧的条目
        if (logList.size > 20) {
            logList.removeAt(logList.size - 1)
        }
        
        notifyDataSetChanged()
    }
    
    fun clearLogs() {
        logList.clear()
        notifyDataSetChanged()
    }
}