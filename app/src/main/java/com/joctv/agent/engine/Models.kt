package com.joctv.agent.engine

data class AssistantResponse(val text: String, val source: String)

enum class RouteType { WEB, LOCAL_KB, LLM }