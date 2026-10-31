package com.minifile.explorer

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.minifile.explorer.ui.theme.DarkColors
import com.minifile.explorer.ui.theme.elevateDynamicDarkSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class LauncherIconResourceTest {

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val EXPECTED_BACKGROUND_PATH = "M0,0h108v108h-108z"
        private const val EXPECTED_FOLDER_PATH =
            "M27,36c0,-3.3 2.7,-6 6,-6h14l6,6h22c3.3,0 6,2.7 6,6v30c0,3.3 -2.7,6 -6,6H33c-3.3,0 -6,-2.7 -6,-6V36z"

        private fun locateAppMainRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile && File(dir, "app/src/main").isDirectory) {
                    return File(dir, "app/src/main")
                }
                dir = dir.parentFile
            }
            error("Could not locate app/src/main from working directory")
        }

        private fun parseXml(file: File): Document {
            assertTrue("Expected XML resource file to exist: ${file.path}", file.isFile)
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            return file.inputStream().use { factory.newDocumentBuilder().parse(it) }
        }

        private fun Element.androidAttr(localName: String): String {
            val nsVal = getAttributeNS(ANDROID_NS, localName)
            if (nsVal.isNotEmpty()) return nsVal
            return getAttribute("android:$localName")
        }

        private fun Document.childElements(tagName: String): List<Element> {
            val list = documentElement.getElementsByTagName(tagName)
            return (0 until list.length).map { list.item(it) as Element }
        }

        private fun parseColorMap(file: File): Map<String, String> {
            val doc = parseXml(file)
            assertEquals("resources", doc.documentElement.tagName)
            return doc.childElements("color").associate { el ->
                el.getAttribute("name") to el.textContent.trim().uppercase()
            }
        }

        private fun relativeLuminance(hexColor: String): Double {
            val clean = hexColor.removePrefix("#")
            require(clean.length == 6) { "Expected 6-digit hex color, got $hexColor" }
            val r = clean.substring(0, 2).toInt(16) / 255.0
            val g = clean.substring(2, 4).toInt(16) / 255.0
            val b = clean.substring(4, 6).toInt(16) / 255.0
            fun channel(c: Double): Double =
                if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

        private fun contrastRatio(hexA: String, hexB: String): Double {
            val lumA = relativeLuminance(hexA)
            val lumB = relativeLuminance(hexB)
            return (max(lumA, lumB) + 0.05) / (min(lumA, lumB) + 0.05)
        }
    }

    @Test
    fun manifestReferencesAdaptiveMipmapLauncherIcon() {
        val mainRoot = locateAppMainRoot()
        val manifestDoc = parseXml(File(mainRoot, "AndroidManifest.xml"))
        assertEquals("manifest", manifestDoc.documentElement.tagName)

        val apps = manifestDoc.childElements("application")
        assertEquals(1, apps.size)
        val appEl = apps.single()
        assertEquals("@mipmap/ic_launcher", appEl.androidAttr("icon"))
        assertEquals("@mipmap/ic_launcher", appEl.androidAttr("roundIcon"))
    }

    @Test
    fun adaptiveIconXmlDefinesBackgroundForegroundAndMonochromeLayers() {
        val resRoot = File(locateAppMainRoot(), "res")
        val doc = parseXml(File(resRoot, "mipmap-anydpi-v26/ic_launcher.xml"))
        assertEquals("adaptive-icon", doc.documentElement.tagName)

        val background = doc.childElements("background")
        val foreground = doc.childElements("foreground")
        val monochrome = doc.childElements("monochrome")
        assertEquals(1, background.size)
        assertEquals(1, foreground.size)
        assertEquals(1, monochrome.size)

        assertEquals("@drawable/ic_launcher_background", background.single().androidAttr("drawable"))
        assertEquals("@drawable/ic_launcher_foreground", foreground.single().androidAttr("drawable"))
        assertEquals("@drawable/ic_launcher_monochrome", monochrome.single().androidAttr("drawable"))
    }

    @Test
    fun vectorLayersReferenceThemeColorsAndPreserveCenteredFolderGeometry() {
        val resRoot = File(locateAppMainRoot(), "res")

        fun verifyVector(
            relativePath: String,
            expectedFillColor: String,
            expectedPathData: String,
        ) {
            val doc = parseXml(File(resRoot, relativePath))
            val root = doc.documentElement
            assertEquals("vector", root.tagName)
            assertEquals("108dp", root.androidAttr("width"))
            assertEquals("108dp", root.androidAttr("height"))
            assertEquals("108", root.androidAttr("viewportWidth"))
            assertEquals("108", root.androidAttr("viewportHeight"))

            val paths = doc.childElements("path")
            assertEquals("Expected single <path> in $relativePath", 1, paths.size)
            val path = paths.single()
            assertEquals(expectedFillColor, path.androidAttr("fillColor"))
            assertEquals(expectedPathData, path.androidAttr("pathData"))
        }

        verifyVector(
            relativePath = "drawable/ic_launcher_background.xml",
            expectedFillColor = "@color/ic_launcher_background",
            expectedPathData = EXPECTED_BACKGROUND_PATH,
        )
        verifyVector(
            relativePath = "drawable/ic_launcher_foreground.xml",
            expectedFillColor = "@color/ic_launcher_foreground",
            expectedPathData = EXPECTED_FOLDER_PATH,
        )
        verifyVector(
            relativePath = "drawable/ic_launcher_monochrome.xml",
            expectedFillColor = "#FFFFFF",
            expectedPathData = EXPECTED_FOLDER_PATH,
        )
    }

    @Test
    fun lightAndDarkColorPalettesAreDistinctAndHighContrast() {
        val resRoot = File(locateAppMainRoot(), "res")
        val lightColors = parseColorMap(File(resRoot, "values/colors.xml"))
        val darkColors = parseColorMap(File(resRoot, "values-night/colors.xml"))

        assertEquals("#3366CC", lightColors["ic_launcher_background"])
        assertEquals("#FFFFFF", lightColors["ic_launcher_foreground"])

        assertEquals("#20242D", darkColors["ic_launcher_background"])
        assertEquals("#60A5FA", darkColors["ic_launcher_foreground"])

        assertNotEquals(lightColors["ic_launcher_background"], darkColors["ic_launcher_background"])
        assertNotEquals(lightColors["ic_launcher_foreground"], darkColors["ic_launcher_foreground"])

        val lightBgLum = relativeLuminance(lightColors.getValue("ic_launcher_background"))
        val darkBgLum = relativeLuminance(darkColors.getValue("ic_launcher_background"))
        assertTrue(
            "Expected dark theme icon background ($darkBgLum) to be darker than light ($lightBgLum)",
            darkBgLum < lightBgLum,
        )

        val lightContrast = contrastRatio(
            lightColors.getValue("ic_launcher_background"),
            lightColors.getValue("ic_launcher_foreground"),
        )
        val darkContrast = contrastRatio(
            darkColors.getValue("ic_launcher_background"),
            darkColors.getValue("ic_launcher_foreground"),
        )
        assertTrue("Expected WCAG AA contrast >= 4.5 in Light mode, got $lightContrast", lightContrast >= 4.5)
        assertTrue("Expected WCAG AA contrast >= 4.5 in Dark mode, got $darkContrast", darkContrast >= 4.5)
    }

    @Test
    fun darkThemeUsesCharcoalSurfaceHierarchyAndLiftsDynamicDarkSurfaces() {
        assertEquals(Color(0xFF1E1F24), DarkColors.background)
        assertEquals(Color(0xFFE3E2E9), DarkColors.onBackground)
        assertEquals(Color(0xFF1E1F24), DarkColors.surface)
        assertEquals(Color(0xFFE3E2E9), DarkColors.onSurface)
        assertEquals(Color(0xFF16181D), DarkColors.surfaceDim)
        assertEquals(Color(0xFF383B43), DarkColors.surfaceBright)
        assertEquals(Color(0xFF181A1F), DarkColors.surfaceContainerLowest)
        assertEquals(Color(0xFF26282F), DarkColors.surfaceContainerLow)
        assertEquals(Color(0xFF2B2E36), DarkColors.surfaceContainer)
        assertEquals(Color(0xFF32353E), DarkColors.surfaceContainerHigh)
        assertEquals(Color(0xFF3B3E48), DarkColors.surfaceContainerHighest)

        assertTrue(DarkColors.surfaceDim.luminance() < DarkColors.surfaceContainerLowest.luminance())
        assertTrue(DarkColors.surfaceContainerLowest.luminance() < DarkColors.surface.luminance())
        assertTrue(DarkColors.surface.luminance() < DarkColors.surfaceContainerLow.luminance())
        assertTrue(DarkColors.surfaceContainerLow.luminance() < DarkColors.surfaceContainer.luminance())
        assertTrue(DarkColors.surfaceContainer.luminance() < DarkColors.surfaceContainerHigh.luminance())
        assertTrue(DarkColors.surfaceContainerHigh.luminance() < DarkColors.surfaceContainerHighest.luminance())

        val rawDark = darkColorScheme()
        val elevated = elevateDynamicDarkSurfaces(rawDark)
        assertEquals(rawDark.surfaceContainer, elevated.background)
        assertEquals(rawDark.surfaceContainer, elevated.surface)
        assertEquals(rawDark.surface, elevated.surfaceContainerLowest)
        assertEquals(rawDark.surfaceContainerHigh, elevated.surfaceContainerLow)
        assertEquals(rawDark.surfaceContainerHighest, elevated.surfaceContainer)
        assertEquals(rawDark.surfaceBright, elevated.surfaceContainerHigh)
        assertTrue(elevated.surface.luminance() > rawDark.surface.luminance())
    }
}
