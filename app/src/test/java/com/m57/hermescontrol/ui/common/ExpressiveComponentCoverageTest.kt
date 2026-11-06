package com.m57.hermescontrol.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards production integration, separately from native-component interaction tests. */
class ExpressiveComponentCoverageTest {
    @Test
    fun productionDoesNotBypassTheSharedExpressiveVariants() {
        val root = mainRoot()
        val nativeImport =
            Regex(
                """(?m)^import androidx\.compose\.material3\.(Button|FilledTonalButton|OutlinedButton|TextButton|IconButton|Switch|CircularProgressIndicator|LinearProgressIndicator|SingleChoiceSegmentedButtonRow|SegmentedButton)(?: as \w+)?$""",
            )
        val nativeOwners =
            setOf(
                "ui/common/ExpressiveButtons.kt",
                "ui/common/ExpressiveIconButton.kt",
                "ui/common/ExpressiveSwitch.kt",
            )
        val offenders =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.relativeTo(root).invariantSeparatorsPath !in nativeOwners }
                .flatMap { file ->
                    val code = file.readText()
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    nativeImport.findAll(code).map { "$relative: ${it.value}" } +
                        if (code.contains("import androidx.compose.material3.*")) {
                            sequenceOf("$relative: wildcard import bypasses component ownership")
                        } else {
                            emptySequence()
                        }
                }.toList()
        assertEquals(
            "Use the shared native Expressive adapters, not a stable-overload bypass",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun existingFeaturesActuallyImportEveryMigratedFamily() {
        val featureSources =
            mainRoot()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "/ui/common/" !in it.invariantSeparatorsPath && !it.name.endsWith("Preview.kt") }
                .map { it.readText() }
                .toList()
        listOf(
            "ExpressiveButton as Button",
            "ExpressiveFilledTonalButton as FilledTonalButton",
            "ExpressiveOutlinedButton as OutlinedButton",
            "ExpressiveTextButton as TextButton",
            "ExpressiveIconButton as IconButton",
            "ExpressiveSwitch as Switch",
            "ExpressiveCircularProgressIndicator as CircularProgressIndicator",
            "ExpressiveLinearProgressIndicator as LinearProgressIndicator",
            "ExpressiveChoiceRow",
        ).forEach { name ->
            assertTrue(
                "No production feature integrates $name",
                featureSources.any {
                    it.contains("import com.m57.hermescontrol.ui.common.$name")
                },
            )
        }
    }

    private fun mainRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(cwd, "src/main/java/com/m57/hermescontrol"),
            File(cwd, "app/src/main/java/com/m57/hermescontrol"),
        ).firstOrNull { it.isDirectory } ?: error("Cannot find main Kotlin sources from $cwd")
    }
}
