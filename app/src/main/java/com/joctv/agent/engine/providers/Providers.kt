package com.joctv.agent.engine.providers

import com.joctv.agent.engine.AssistantResponse

class LocalKBProvider {
    fun answer(q: String): AssistantResponse {
        val text = "酒店早餐在 2 楼，时间 07:00-10:30；冰箱：可乐/苏打水/酸奶（示例）"
        return AssistantResponse(text, "LocalKB")
    }
}

class WebSearchProvider {
    fun answer(q: String): AssistantResponse {
        return AssistantResponse("这里将调用联网搜索API（stub）：$q", "WebSearch")
    }
}

class LocalLLMProvider {
    fun answer(q: String): AssistantResponse {
        return AssistantResponse("本地LLM接口预留（stub）：$q", "LocalLLM")
    }
}

class RemoteLLMProvider {
    fun answer(q: String): AssistantResponse {
        return AssistantResponse("联网LLM接口预留（stub）：$q", "RemoteLLM")
    }
}