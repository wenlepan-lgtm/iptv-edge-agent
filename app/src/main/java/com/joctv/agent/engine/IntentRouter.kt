package com.joctv.agent.engine

class IntentRouter {
    fun route(text: String): RouteType {
        val t = text.lowercase()
        return when {
            listOf("天气", "温度", "下雨", "明天").any { t.contains(it) } -> RouteType.WEB
            listOf("早餐", "几楼", "几点", "冰箱").any { t.contains(it) } -> RouteType.LOCAL_KB
            else -> RouteType.LLM
        }
    }
}