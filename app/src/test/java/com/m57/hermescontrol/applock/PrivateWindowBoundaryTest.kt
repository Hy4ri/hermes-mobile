package com.m57.hermescontrol.applock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fail CI if an app-owned native sub-window bypasses the app-lock privacy boundary. */
class PrivateWindowBoundaryTest {
    private val nativePrimitive =
        Regex(
            "androidx\\.compose\\.(?:material3\\.(?:AlertDialog|BasicAlertDialog|ModalBottomSheet|" +
                "DropdownMenu|DatePickerDialog|TimePickerDialog)|ui\\.window\\.(?:Dialog|Popup))\\b" +
                "(?!Properties|PositionProvider|Item)",
        )

    @Test
    fun `native windows are only emitted by the guarded adapters`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val bypasses =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "PrivateWindows.kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        val bypass =
                            nativePrimitive.containsMatchIn(line) ||
                                Regex("\\bExposedDropdownMenu\\(").containsMatchIn(line)
                        if (bypass) "${file.relativeTo(root)}:${index + 1}" else null
                    }
                }.toList()
        assertTrue("Route native dialogs/menus through applock/PrivateWindows.kt: $bypasses", bypasses.isEmpty())
    }

    @Test
    fun `scanner catches aliases and qualified calls but not dialog properties or menu items`() {
        assertTrue(nativePrimitive.containsMatchIn("import androidx.compose.ui.window.Dialog as NativeDialog"))
        assertTrue(nativePrimitive.containsMatchIn("androidx.compose.material3.ModalBottomSheet("))
        assertFalse(nativePrimitive.containsMatchIn("import androidx.compose.ui.window.DialogProperties"))
        assertFalse(nativePrimitive.containsMatchIn("import androidx.compose.material3.DropdownMenuItem"))
    }
}
