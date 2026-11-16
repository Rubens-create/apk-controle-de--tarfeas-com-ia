package com.taskai.app.ui

/** Suggested identifiers only; availability is checked with the user's provider account. */
internal object ProviderPresets {
    fun forProvider(provider: String): Pair<String, String> = when (provider) {
        "ANTHROPIC" -> "https://api.anthropic.com/v1" to "claude-sonnet-4-6"
        "GEMINI" -> "https://generativelanguage.googleapis.com/v1beta" to "gemini-3.8-flash"
        "COMPATIBLE" -> "" to ""
        else -> "https://api.openai.com/v1" to "gpt-4o-mini"
    }
}
