package com.joctv.agent.engine

import com.joctv.agent.engine.providers.LocalKBProvider
import com.joctv.agent.engine.providers.LocalLLMProvider
import com.joctv.agent.engine.providers.RemoteLLMProvider
import com.joctv.agent.engine.providers.WebSearchProvider

class AssistantEngine(
    private val localKB: LocalKBProvider,
    private val web: WebSearchProvider,
    private val llm: LocalLLMProvider,
    private val remote: RemoteLLMProvider
) {
    fun reply(route: RouteType, userText: String): AssistantResponse {
        return when (route) {
            RouteType.LOCAL_KB -> localKB.answer(userText)
            RouteType.WEB -> web.answer(userText)
            RouteType.LLM -> llm.answer(userText)
        }
    }
}