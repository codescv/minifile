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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class LauncherIconResourceTest {

    data class SvgBounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    ) {
        val width: Double get() = maxX - minX
        val height: Double get() = maxY - minY
        val centerX: Double get() = (minX + maxX) / 2.0
        val centerY: Double get() = (minY + maxY) / 2.0
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val AAPT_NS = "http://schemas.android.com/aapt"
        private val LAUNCHER_COLOR_NAMES = listOf(
            "ic_launcher_background",
            "ic_launcher_bg_top",
            "ic_launcher_bg_mid",
            "ic_launcher_bg_bottom",
            "ic_launcher_bg_glow_start",
            "ic_launcher_bg_glow_mid",
            "ic_launcher_foreground",
        )
        private const val EXPECTED_BACKGROUND_PATH = "M0,0h108v108h-108z"
        private const val LEGACY_V108_FOLDER_PATH =
            "M27,36c0,-3.3 2.7,-6 6,-6h14l6,6h22c3.3,0 6,2.7 6,6v30c0,3.3 -2.7,6 -6,6H33c-3.3,0 -6,-2.7 -6,-6V36z"
        private const val EXPECTED_FOLDER_PATH =
            "M36,42c0,-2.2 1.8,-4 4,-4h9.33l4,4h14.67c2.2,0 4,1.8 4,4v20c0,2.2 -1.8,4 -4,4H40c-2.2,0 -4,-1.8 -4,-4V42z"
        private const val FULL_VIEWPORT_SIZE = 108.0
        private const val ADAPTIVE_VISIBLE_MIN = 18.0
        private const val ADAPTIVE_VISIBLE_MAX = 90.0
        private const val ADAPTIVE_VISIBLE_SIZE = ADAPTIVE_VISIBLE_MAX - ADAPTIVE_VISIBLE_MIN

        private fun computeSvgPathBounds(pathData: String): SvgBounds {
            val tokens = Regex("""[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
                .findAll(pathData)
                .map { it.value }
                .toList()
            require(tokens.isNotEmpty()) { "Empty SVG pathData" }

            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY

            fun includePoint(x: Double, y: Double) {
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }

            fun cubicBounds1D(p0: Double, p1: Double, p2: Double, p3: Double): Pair<Double, Double> {
                var lo = min(p0, p3)
                var hi = max(p0, p3)
                fun eval(t: Double): Double {
                    val u = 1.0 - t
                    return u * u * u * p0 + 3.0 * u * u * t * p1 + 3.0 * u * t * t * p2 + t * t * t * p3
                }
                val a = -p0 + 3.0 * p1 - 3.0 * p2 + p3
                val b = 2.0 * (p0 - 2.0 * p1 + p2)
                val c = p1 - p0
                if (abs(a) < 1e-12) {
                    if (abs(b) > 1e-12) {
                        val t = -c / b
                        if (t > 0.0 && t < 1.0) {
                            val v = eval(t)
                            lo = min(lo, v)
                            hi = max(hi, v)
                        }
                    }
                } else {
                    val disc = b * b - 4.0 * a * c
                    if (disc >= 0.0) {
                        val root = sqrt(disc)
                        for (t in listOf((-b - root) / (2.0 * a), (-b + root) / (2.0 * a))) {
                            if (t > 0.0 && t < 1.0) {
                                val v = eval(t)
                                lo = min(lo, v)
                                hi = max(hi, v)
                            }
                        }
                    }
                }
                return lo to hi
            }

            var idx = 0
            var cmd = ' '
            var curX = 0.0
            var curY = 0.0
            var startX = 0.0
            var startY = 0.0

            fun nextNum(): Double = tokens[idx++].toDouble()
            fun hasNum(): Boolean = idx < tokens.size && !tokens[idx][0].isLetter()

            while (idx < tokens.size) {
                if (tokens[idx][0].isLetter()) {
                    cmd = tokens[idx++][0]
                }
                when (cmd) {
                    'M', 'm' -> {
                        val rel = cmd == 'm'
                        val x = nextNum() + if (rel) curX else 0.0
                        val y = nextNum() + if (rel) curY else 0.0
                        curX = x
                        curY = y
                        startX = x
                        startY = y
                        includePoint(curX, curY)
                        cmd = if (rel) 'l' else 'L'
                    }
                    'L', 'l' -> {
                        do {
                            val rel = cmd == 'l'
                            curX = nextNum() + if (rel) curX else 0.0
                            curY = nextNum() + if (rel) curY else 0.0
                            includePoint(curX, curY)
                        } while (hasNum())
                    }
                    'H', 'h' -> {
                        do {
                            curX = nextNum() + if (cmd == 'h') curX else 0.0
                            includePoint(curX, curY)
                        } while (hasNum())
                    }
                    'V', 'v' -> {
                        do {
                            curY = nextNum() + if (cmd == 'v') curY else 0.0
                            includePoint(curX, curY)
                        } while (hasNum())
                    }
                    'C', 'c' -> {
                        do {
                            val rel = cmd == 'c'
                            val x1 = nextNum() + if (rel) curX else 0.0
                            val y1 = nextNum() + if (rel) curY else 0.0
                            val x2 = nextNum() + if (rel) curX else 0.0
                            val y2 = nextNum() + if (rel) curY else 0.0
                            val x3 = nextNum() + if (rel) curX else 0.0
                            val y3 = nextNum() + if (rel) curY else 0.0
                            val (xLo, xHi) = cubicBounds1D(curX, x1, x2, x3)
                            val (yLo, yHi) = cubicBounds1D(curY, y1, y2, y3)
                            minX = min(minX, xLo)
                            maxX = max(maxX, xHi)
                            minY = min(minY, yLo)
                            maxY = max(maxY, yHi)
                            curX = x3
                            curY = y3
                        } while (hasNum())
                    }
                    'Z', 'z' -> {
                        curX = startX
                        curY = startY
                        includePoint(curX, curY)
                    }
                    else -> error("Unsupported SVG command '$cmd' in pathData")
                }
            }

            return SvgBounds(minX, minY, maxX, maxY)
        }

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
    fun backgroundDrawsTwoLayerFrostedCharcoalGradientPlate() {
        val resRoot = File(locateAppMainRoot(), "res")
        val doc = parseXml(File(resRoot, "drawable/ic_launcher_background.xml"))
        val root = doc.documentElement
        assertEquals("vector", root.tagName)
        assertEquals("108dp", root.androidAttr("width"))
        assertEquals("108dp", root.androidAttr("height"))
        assertEquals("108", root.androidAttr("viewportWidth"))
        assertEquals("108", root.androidAttr("viewportHeight"))

        val paths = doc.childElements("path")
        assertEquals("Expected 2 <path> layers (linear plate + radial bloom)", 2, paths.size)

        fun gradientOf(path: Element): Element {
            assertEquals(EXPECTED_BACKGROUND_PATH, path.androidAttr("pathData"))
            assertTrue(
                "Gradient layer must not use a flat android:fillColor",
                path.androidAttr("fillColor").isEmpty(),
            )
            val attrs = path.getElementsByTagNameNS(AAPT_NS, "attr")
            assertEquals(1, attrs.length)
            val attr = attrs.item(0) as Element
            assertEquals("android:fillColor", attr.getAttribute("name"))
            val gradients = attr.getElementsByTagName("gradient")
            assertEquals(1, gradients.length)
            return gradients.item(0) as Element
        }

        fun stopsOf(gradient: Element): List<Pair<String, String>> {
            val items = gradient.getElementsByTagName("item")
            return (0 until items.length).map {
                val item = items.item(it) as Element
                item.androidAttr("color") to item.androidAttr("offset")
            }
        }

        // Layer 1: vertical linear charcoal plate (y=16 -> y=92)
        val linear = gradientOf(paths[0])
        assertEquals("linear", linear.androidAttr("type"))
        assertEquals("54", linear.androidAttr("startX"))
        assertEquals("16", linear.androidAttr("startY"))
        assertEquals("54", linear.androidAttr("endX"))
        assertEquals("92", linear.androidAttr("endY"))
        assertEquals(
            listOf(
                "@color/ic_launcher_bg_top" to "0.0",
                "@color/ic_launcher_bg_mid" to "0.48",
                "@color/ic_launcher_bg_bottom" to "1.0",
            ),
            stopsOf(linear),
        )

        // Layer 2: radial frosted-glass diffusion bloom at (48, 32), r=46
        val radial = gradientOf(paths[1])
        assertEquals("radial", radial.androidAttr("type"))
        assertEquals("48", radial.androidAttr("centerX"))
        assertEquals("32", radial.androidAttr("centerY"))
        assertEquals("46", radial.androidAttr("gradientRadius"))
        assertEquals(
            listOf(
                "@color/ic_launcher_bg_glow_start" to "0.0",
                "@color/ic_launcher_bg_glow_mid" to "0.55",
                "#00FFFFFF" to "1.0",
            ),
            stopsOf(radial),
        )
    }

    @Test
    fun folderPathScaleMatchesLegacyLightIconProportionsInsideAdaptiveViewport() {
        val resRoot = File(locateAppMainRoot(), "res")
        val legacyBounds = computeSvgPathBounds(LEGACY_V108_FOLDER_PATH)
        assertEquals(27.0, legacyBounds.minX, 1e-6)
        assertEquals(81.0, legacyBounds.maxX, 1e-6)
        assertEquals(30.0, legacyBounds.minY, 1e-6)
        assertEquals(78.0, legacyBounds.maxY, 1e-6)
        assertEquals(54.0, legacyBounds.width, 1e-6)
        assertEquals(48.0, legacyBounds.height, 1e-6)
        assertEquals(54.0, legacyBounds.centerX, 1e-6)
        assertEquals(54.0, legacyBounds.centerY, 1e-6)

        val legacyWidthRatio = legacyBounds.width / FULL_VIEWPORT_SIZE
        val legacyHeightRatio = legacyBounds.height / FULL_VIEWPORT_SIZE
        assertEquals(0.50, legacyWidthRatio, 1e-6)
        assertEquals(4.0 / 9.0, legacyHeightRatio, 1e-6)

        for (relativePath in listOf(
            "drawable/ic_launcher_foreground.xml",
            "drawable/ic_launcher_monochrome.xml",
        )) {
            val doc = parseXml(File(resRoot, relativePath))
            val pathData = doc.childElements("path").single().androidAttr("pathData")
            val bounds = computeSvgPathBounds(pathData)

            // Exact 2/3-scaled bounds around (54, 54) mapped onto [18, 90] (72x72 visible safe zone)
            assertEquals("minX in $relativePath", 36.0, bounds.minX, 1e-6)
            assertEquals("maxX in $relativePath", 72.0, bounds.maxX, 1e-6)
            assertEquals("minY in $relativePath", 38.0, bounds.minY, 1e-6)
            assertEquals("maxY in $relativePath", 70.0, bounds.maxY, 1e-6)
            assertEquals("width in $relativePath", 36.0, bounds.width, 1e-6)
            assertEquals("height in $relativePath", 32.0, bounds.height, 1e-6)
            assertEquals("centerX in $relativePath", 54.0, bounds.centerX, 1e-6)
            assertEquals("centerY in $relativePath", 54.0, bounds.centerY, 1e-6)

            // Strictly inside adaptive-icon visible viewport [18, 90] and 66dp keyline safe zone [21, 87]
            assertTrue(bounds.minX >= ADAPTIVE_VISIBLE_MIN && bounds.maxX <= ADAPTIVE_VISIBLE_MAX)
            assertTrue(bounds.minY >= ADAPTIVE_VISIBLE_MIN && bounds.maxY <= ADAPTIVE_VISIBLE_MAX)
            assertTrue(bounds.minX >= 21.0 && bounds.maxX <= 87.0)
            assertTrue(bounds.minY >= 21.0 && bounds.maxY <= 87.0)

            // Proportions inside 72x72 visible adaptive viewport match v1.0.8's 108x108 light icon 1-to-1
            val adaptiveWidthRatio = bounds.width / ADAPTIVE_VISIBLE_SIZE
            val adaptiveHeightRatio = bounds.height / ADAPTIVE_VISIBLE_SIZE
            assertEquals("width ratio in $relativePath", 0.50, adaptiveWidthRatio, 1e-6)
            assertEquals("width ratio vs v1.0.8 in $relativePath", legacyWidthRatio, adaptiveWidthRatio, 1e-6)
            assertEquals("height ratio vs v1.0.8 in $relativePath", legacyHeightRatio, adaptiveHeightRatio, 1e-6)
            assertEquals("linear X scale vs v1.0.8", 2.0 / 3.0, bounds.width / legacyBounds.width, 1e-6)
            assertEquals("linear Y scale vs v1.0.8", 2.0 / 3.0, bounds.height / legacyBounds.height, 1e-6)
        }
    }

    @Test
    fun lightAndDarkColorPalettesAreDistinctAndHighContrast() {
        val resRoot = File(locateAppMainRoot(), "res")
        val lightColors = parseColorMap(File(resRoot, "values/colors.xml"))
        val darkColors = parseColorMap(File(resRoot, "values-night/colors.xml"))

        assertEquals(LAUNCHER_COLOR_NAMES.toSet(), lightColors.keys.filter { it.startsWith("ic_launcher") }.toSet())
        assertEquals(LAUNCHER_COLOR_NAMES.toSet(), darkColors.keys.filter { it.startsWith("ic_launcher") }.toSet())

        // Light mode: flat #3366CC plate (gradient stops identical) with fully transparent bloom
        assertEquals("#3366CC", lightColors["ic_launcher_background"])
        assertEquals("#3366CC", lightColors["ic_launcher_bg_top"])
        assertEquals("#3366CC", lightColors["ic_launcher_bg_mid"])
        assertEquals("#3366CC", lightColors["ic_launcher_bg_bottom"])
        assertEquals("#00FFFFFF", lightColors["ic_launcher_bg_glow_start"])
        assertEquals("#00FFFFFF", lightColors["ic_launcher_bg_glow_mid"])
        assertEquals("#FFFFFF", lightColors["ic_launcher_foreground"])

        // Dark mode: Frosted Charcoal Dark Plate (Play v1.0.8 / Podcasts v0.1.19) + soft white bloom
        assertEquals("#282A30", darkColors["ic_launcher_background"])
        assertEquals("#363940", darkColors["ic_launcher_bg_top"])
        assertEquals("#282A30", darkColors["ic_launcher_bg_mid"])
        assertEquals("#1E2025", darkColors["ic_launcher_bg_bottom"])
        assertEquals("#1AFFFFFF", darkColors["ic_launcher_bg_glow_start"])
        assertEquals("#09FFFFFF", darkColors["ic_launcher_bg_glow_mid"])
        assertEquals("#60A5FA", darkColors["ic_launcher_foreground"])
        assertEquals(darkColors["ic_launcher_bg_mid"], darkColors["ic_launcher_background"])

        // Plate brightens toward the top (top > mid > bottom)
        val topLum = relativeLuminance(darkColors.getValue("ic_launcher_bg_top"))
        val midLum = relativeLuminance(darkColors.getValue("ic_launcher_bg_mid"))
        val bottomLum = relativeLuminance(darkColors.getValue("ic_launcher_bg_bottom"))
        assertTrue("Expected top ($topLum) > mid ($midLum) > bottom ($bottomLum)", topLum > midLum && midLum > bottomLum)

        assertNotEquals(lightColors["ic_launcher_background"], darkColors["ic_launcher_background"])
        assertNotEquals(lightColors["ic_launcher_foreground"], darkColors["ic_launcher_foreground"])

        val lightBgLum = relativeLuminance(lightColors.getValue("ic_launcher_background"))
        val darkBgLum = relativeLuminance(darkColors.getValue("ic_launcher_background"))
        assertTrue(
            "Expected dark theme icon background ($darkBgLum) to be darker than light ($lightBgLum)",
            darkBgLum < lightBgLum,
        )

        for (plateColor in listOf("ic_launcher_background", "ic_launcher_bg_top", "ic_launcher_bg_mid", "ic_launcher_bg_bottom")) {
            val lightContrast = contrastRatio(
                lightColors.getValue(plateColor),
                lightColors.getValue("ic_launcher_foreground"),
            )
            val darkContrast = contrastRatio(
                darkColors.getValue(plateColor),
                darkColors.getValue("ic_launcher_foreground"),
            )
            assertTrue("Expected WCAG AA contrast >= 4.5 in Light mode for $plateColor, got $lightContrast", lightContrast >= 4.5)
            assertTrue("Expected WCAG AA contrast >= 4.5 in Dark mode for $plateColor, got $darkContrast", darkContrast >= 4.5)
        }
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
