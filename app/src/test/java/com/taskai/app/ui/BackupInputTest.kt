package com.taskai.app.ui

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BackupInputTest {
    @Test fun acceptsAnExactBoundWithoutNewAndroidApis() {
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, BackupInput.read(ByteArrayInputStream(bytes), 3))
    }
    @Test fun rejectsLargerFilesBeforeLoadingThemEntirely() {
        try {
            BackupInput.read(ByteArrayInputStream(ByteArray(10_000)), 3)
            fail("An oversized backup must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun acceptsAnEmptyStreamForStructuralValidatorToRejectLater() {
        assertEquals(0, BackupInput.read(ByteArrayInputStream(byteArrayOf()), 3).size)
    }
}
