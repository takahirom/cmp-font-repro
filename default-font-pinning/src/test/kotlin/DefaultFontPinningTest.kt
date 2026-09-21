import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.FontMgr
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Public API only. No @InternalComposeUiApi is used anywhere in this file: the point of the
 * issue is that the supported route is not sufficient.
 *
 * FontFamily.Default resolves through FontCache.ensureRegistered, which returns
 * FontFamily.SansSerif.aliases (ui-skiko/.../PlatformFont.nonAndroid.kt:255). So registering a
 * typeface under the platform's first sans-serif alias does redirect FontFamily.Default -- but
 * FontCache.registered is a MutableSet<String> keyed on the alias alone (:215, guard at :244),
 * so only the first face registered under that alias survives.
 */
class DefaultFontPinningTest {

    /**
     * The first sans-serif alias for the host OS. GenericFontFamiliesMapping (same file, :288)
     * is private, so a copy of it is the only way to learn the name to register under -- which
     * is part of what this issue is about.
     */
    private val sansSerifAlias: String = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("mac") -> ".AppleSystemUIFont"
            it.contains("win") -> "Segoe UI"
            else -> "Noto Sans"
        }
    }

    private fun resource(name: String) =
        File(DefaultFontPinningTest::class.java.getResource("/$name")!!.toURI())

    private fun loadedFamily(file: File) =
        FontFamily(Typeface(FontMgr.default.makeFromFile(file.absolutePath)!!, alias = sansSerifAlias))

    /** Width of one line of text, laid out at density 1 so the number is in font units. */
    private fun width(
        resolver: FontFamily.Resolver,
        family: FontFamily?,
        weight: FontWeight,
    ): Int {
        var w = 0
        val scene = ImageComposeScene(width = 600, height = 120, density = Density(1f)) {
            CompositionLocalProvider(LocalFontFamilyResolver provides resolver) {
                Text(
                    TEXT,
                    fontSize = 24.sp,
                    fontFamily = family,
                    fontWeight = weight,
                    modifier = Modifier.onSizeChanged { w = it.width },
                )
            }
        }
        scene.render()
        scene.close()
        return w
    }

    @Test
    fun onlyTheFirstFaceRegisteredUnderTheAliasSurvives() {
        // In 1.13.0-alpha01 ui-text has no text backend until ui registers ui-skiko.
        // Creating any scene does that; an app does it implicitly at start-up.
        ImageComposeScene(width = 1, height = 1) {}.close()
        val resolver = createFontFamilyResolver()

        // Reference widths: Roboto addressed explicitly, which always works.
        val explicitRoboto = FontFamily(
            Font(file = resource(REGULAR), weight = FontWeight.Normal),
            Font(file = resource(BOLD), weight = FontWeight.Bold),
        )
        val robotoRegular = width(resolver, explicitRoboto, FontWeight.Normal)  // explicit family, does not touch Default
        val robotoBold = width(resolver, explicitRoboto, FontWeight.Bold)

        // Host OS system font, measured on a SEPARATE resolver so that this resolver's
        // TypefaceRequestCache is not already holding FontFamily.Default -> system font
        // by the time the pin is installed.
        val baseline = createFontFamilyResolver()
        val systemRegular = width(baseline, FontFamily.Default, FontWeight.Normal)
        val systemBold = width(baseline, FontFamily.Default, FontWeight.Bold)

        // The pin: register both Roboto faces under the alias FontFamily.Default resolves to.
        // Resolving a LoadedFontFamily is what reaches FontCache.ensureRegistered.
        resolver.resolve(loadedFamily(resource(REGULAR)), FontWeight.Normal)
        resolver.resolve(loadedFamily(resource(BOLD)), FontWeight.Bold)

        val pinnedRegular = width(resolver, FontFamily.Default, FontWeight.Normal)
        val pinnedBold = width(resolver, FontFamily.Default, FontWeight.Bold)

        println("alias registered under        : $sansSerifAlias")
        println("Roboto, addressed explicitly  : regular=$robotoRegular bold=$robotoBold")
        println("FontFamily.Default, before pin: regular=$systemRegular bold=$systemBold")
        println("FontFamily.Default, after pin : regular=$pinnedRegular bold=$pinnedBold")
        println()

        // The pin works for the first face registered under the alias.
        assertEquals(robotoRegular, pinnedRegular, "FontFamily.Default at Normal was not pinned")

        // ...and silently does not for the second. This assertion is the bug: pinnedBold comes
        // out at the Regular face's advance, because the Bold registration was dropped by the
        // `registered.contains(key)` guard and Skia emboldened Regular instead.
        assertEquals(robotoBold, pinnedBold, "FontFamily.Default at Bold was not pinned")
    }

    private companion object {
        const val TEXT = "Hamburgefonstiv 0123"
        const val REGULAR = "Roboto-Regular.ttf"
        const val BOLD = "Roboto-Bold.ttf"
    }
}
