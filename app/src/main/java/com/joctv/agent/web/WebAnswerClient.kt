package com.joctv.agent.web

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties
import java.util.concurrent.TimeUnit

class WebAnswerClient(context: Context) {
    private val client: OkHttpClient
    private val baseUrl: String
    private val apiKey: String
    private val model: String
    
    companion object {
        const val TAG = "WebAnswerClient"
    }
    
    init {
        // 读取配置文件
        val properties = Properties()
        context.assets.open("config.properties").use { inputStream ->
            properties.load(inputStream)
        }
        
        baseUrl = properties.getProperty("web.api.base.url", "https://api.example.com")
        apiKey = properties.getProperty("web.api.key", "")
        model = properties.getProperty("web.api.model", "deepseek-chat")
        
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    fun getAnswer(query: String, callback: (WebAnswerResult) -> Unit) {
        Log.d(TAG, "WEB_STATE=WEB_REQ query=$query")
        
        // 构造Qwen API请求体 (OpenAI兼容模式)
        // 使用org.json.JSONArray而不是Kotlin的listOf()
        val messages = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("role", "user")
                put("content", query)
            })
        }
        
        val jsonBody = org.json.JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.2)
            put("stream", false)
            // 添加实时信息查询参数
            put("presence_penalty", 0.1)
            put("frequency_penalty", 0.1)
            // 添加日期相关的参数以确保获取最新信息
            put("date", System.currentTimeMillis())
            // 强制使用最新数据
            put("force_update", true)
        }
        
        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "WEB_STATE=WEB_ERROR", e)
                when (e) {
                    is SocketTimeoutException -> {
                        Log.d(TAG, "WEB_STATE=WEB_TIMEOUT")
                        callback(WebAnswerResult(false, "查询超时，请稍后重试", query))
                    }
                    is UnknownHostException -> {
                        Log.d(TAG, "WEB_STATE=WEB_ERROR reason=network_unavailable")
                        callback(WebAnswerResult(false, "网络连接失败，请检查网络设置", query))
                    }
                    else -> {
                        Log.d(TAG, "WEB_STATE=WEB_ERROR reason=unexpected_error")
                        callback(WebAnswerResult(false, "查询过程中发生错误，请稍后重试", query))
                    }
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    try {
                        val responseBody = response.body?.string()
                        Log.d(TAG, "WEB_STATE=WEB_OK response=$responseBody")
                        
                        if (responseBody != null) {
                            val jsonResponse = JSONObject(responseBody)
                            val choices = jsonResponse.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val answer = choices.getJSONObject(0).getJSONObject("message").getString("content")
                                callback(WebAnswerResult(true, answer, query))
                            } else {
                                Log.d(TAG, "WEB_STATE=WEB_ERROR reason=empty_choices")
                                callback(WebAnswerResult(false, "未获取到有效信息", query))
                            }
                        } else {
                            Log.d(TAG, "WEB_STATE=WEB_ERROR reason=empty_response")
                            callback(WebAnswerResult(false, "未获取到有效信息", query))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "WEB_STATE=WEB_ERROR reason=parsing_response", e)
                        callback(WebAnswerResult(false, "解析响应数据时发生错误", query))
                    }
                } else {
                    // 打印response body用于调试
                    val errorBody = response.body?.string()
                    Log.e(TAG, "WEB_STATE=WEB_ERROR status=${response.code} body=$errorBody")
                    callback(WebAnswerResult(false, "查询服务暂时不可用，请稍后重试", query))
                }
            }
        })
    }
}

data class WebAnswerResult(
    val success: Boolean,
    val answerText: String,
    val originalQuery: String
)