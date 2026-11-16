package com.taskai.app.ui

import org.junit.Assert.*
import org.junit.Test

class ProviderPresetsTest {
    @Test fun anthropicBaseProducesTheDocumentedMessagesPath() {
        assertEquals("https://api.anthropic.com/v1/messages", ProviderPresets.forProvider("ANTHROPIC").first + "/messages")
        assertEquals("claude-sonnet-4-6", ProviderPresets.forProvider("ANTHROPIC").second)
    }
    @Test fun geminiPresetUsesACurrentStableModelInsteadOfRetired20Flash() {
        val preset = ProviderPresets.forProvider("GEMINI")
        assertEquals("https://generativelanguage.googleapis.com/v1beta", preset.first)
        assertEquals("gemini-3.8-flash", preset.second)
    }
    @Test fun compatibleEndpointAndModelMustBeExplicitlyProvided() {
        assertEquals("" to "", ProviderPresets.forProvider("COMPATIBLE"))
    }
}
