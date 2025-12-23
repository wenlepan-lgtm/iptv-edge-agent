package com.joctv.agent.intent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

data class IntentRoute(
    val biz: String,
    val intent: String,
    val priority: Int,
    val keywordsAny: List<String>,
    val keywordsNone: List<String>,
    val reply: String,
    val slots: List<SlotExtractor>?
)

data class SlotExtractor(
    val name: String,
    val type: String,
    val extract: String
)

data class SlotValue(
    val name: String,
    val value: Any
)

class IntentRouter(private val context: Context) {
    private val routes: List<IntentRoute>
    private val slotExtractors: Map<String, JSONObject>
    private val webFallbackKeywords: List<String>
    
    companion object {
        const val TAG = "IntentRouter"
    }
    
    init {
        val jsonString = context.assets.open("intents_local.json").bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(jsonString)
        
        // 解析路由规则
        val routesArray = jsonObject.getJSONArray("routes")
        routes = parseRoutes(routesArray)
        
        // 解析槽位提取器
        slotExtractors = mutableMapOf()
        if (jsonObject.has("slot_extractors")) {
            val extractors = jsonObject.getJSONObject("slot_extractors")
            extractors.keys().forEach { key ->
                slotExtractors[key] = extractors.getJSONObject(key)
            }
        }
        
        // 解析Web回退关键词
        webFallbackKeywords = jsonObject.getJSONArray("web_fallback_keywords").let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
    }
    
    private fun parseRoutes(routesArray: JSONArray): List<IntentRoute> {
        val routeList = mutableListOf<IntentRoute>()
        
        for (i in 0 until routesArray.length()) {
            val routeObj = routesArray.getJSONObject(i)
            val route = IntentRoute(
                biz = routeObj.getString("biz"),
                intent = routeObj.getString("intent"),
                priority = routeObj.getInt("priority"),
                keywordsAny = routeObj.optJSONArray("keywords_any")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList(),
                keywordsNone = routeObj.optJSONArray("keywords_none")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList(),
                reply = routeObj.getString("reply"),
                slots = routeObj.optJSONArray("slots")?.let { array ->
                    (0 until array.length()).map { i ->
                        val slotObj = array.getJSONObject(i)
                        SlotExtractor(
                            name = slotObj.getString("name"),
                            type = slotObj.getString("type"),
                            extract = slotObj.getString("extract")
                        )
                    }
                }
            )
            routeList.add(route)
        }
        
        // 按优先级排序
        return routeList.sortedByDescending { it.priority }
    }
    
    fun route(text: String): RouteResult {
        // 首先尝试匹配本地意图
        for (route in routes) {
            // 检查是否包含任意关键词
            val hasAnyKeyword = route.keywordsAny.isEmpty() || route.keywordsAny.any { text.contains(it) }
            
            // 检查是否不包含排除关键词
            val hasNoExcludeKeyword = route.keywordsNone.isEmpty() || route.keywordsNone.none { text.contains(it) }
            
            if (hasAnyKeyword && hasNoExcludeKeyword) {
                Log.d(TAG, "ROUTER_STATE=ROUTER_LOCAL biz=${route.biz} intent=${route.intent}")
                
                // 提取槽位信息
                val slotValues = extractSlots(text, route.slots)
                
                return RouteResult(
                    type = RouteType.LOCAL_COMMAND,
                    intent = route.intent,
                    reply = route.reply,
                    slotValues = slotValues,
                    biz = route.biz
                )
            }
        }
        
        // 检查是否应该路由到Web查询
        if (webFallbackKeywords.any { text.contains(it) }) {
            Log.d(TAG, "ROUTER_STATE=ROUTER_WEB")
            return RouteResult(
                type = RouteType.WEB_QUERY,
                query = text,
                biz = "WEB"
            )
        }
        
        // 默认路由到LLM
        Log.d(TAG, "ROUTER_STATE=ROUTER_LLM")
        return RouteResult(
            type = RouteType.LLM,
            query = text,
            biz = "LLM"
        )
    }
    
    /**
     * 根据业务类型获取相关意图
     */
    fun getRoutesByBiz(biz: String): List<IntentRoute> {
        return routes.filter { it.biz == biz }
    }
    
    /**
     * 获取所有业务类型
     */
    fun getAllBizTypes(): Set<String> {
        return routes.map { it.biz }.toSet()
    }
    
    private fun extractSlots(text: String, slots: List<SlotExtractor>?): List<SlotValue> {
        if (slots.isNullOrEmpty()) return emptyList()
        
        val slotValues = mutableListOf<SlotValue>()
        
        for (slot in slots) {
            val extractor = slotExtractors[slot.extract] ?: continue
            val regexPattern = extractor.getString("regex")
            val pattern = Pattern.compile(regexPattern)
            val matcher = pattern.matcher(text)
            
            if (matcher.find()) {
                val valueStr = matcher.group(1)
                when (slot.type) {
                    "int" -> {
                        try {
                            val value = valueStr.toInt()
                            val min = extractor.optInt("min", Int.MIN_VALUE)
                            val max = extractor.optInt("max", Int.MAX_VALUE)
                            
                            if (value >= min && value <= max) {
                                slotValues.add(SlotValue(slot.name, value))
                            } else {
                                // 使用默认值
                                val default = extractor.optInt("default")
                                slotValues.add(SlotValue(slot.name, default))
                            }
                        } catch (e: NumberFormatException) {
                            // 使用默认值
                            val default = extractor.optInt("default")
                            slotValues.add(SlotValue(slot.name, default))
                        }
                    }
                    else -> {
                        slotValues.add(SlotValue(slot.name, valueStr))
                    }
                }
            } else {
                // 使用默认值
                when (slot.type) {
                    "int" -> {
                        val default = extractor.optInt("default")
                        slotValues.add(SlotValue(slot.name, default))
                    }
                }
            }
        }
        
        return slotValues
    }
}

data class RouteResult(
    val type: RouteType,
    val intent: String? = null,
    val reply: String? = null,
    val query: String? = null,
    val slotValues: List<SlotValue> = emptyList(),
    val biz: String? = null
)

enum class RouteType {
    LOCAL_COMMAND,
    WEB_QUERY,
    LLM
}