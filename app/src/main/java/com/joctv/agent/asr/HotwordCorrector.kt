package com.joctv.agent.asr

object HotwordCorrector {
    private val correctionMap = mapOf(
        "joc tv" to "JOCTV",
        "joy tv" to "JOCTV",
        "九西提" to "JOCTV",
        "wifi" to "Wi-Fi",
        "外发" to "Wi-Fi",
        "歪f" to "Wi-Fi",
        "悦影院" to "悦影院" // Placeholder for same-word corrections
    )

    fun correct(text: String): String {
        var correctedText = text
        correctionMap.forEach { (wrong, correct) ->
            correctedText = correctedText.replace(wrong, correct, ignoreCase = true)
        }
        return correctedText
    }
}