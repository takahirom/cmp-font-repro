import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.PlatformTypefacesLoader
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.createPlatformFontFamilyResolver
import androidx.compose.ui.text.font.platformTypefacesLoader
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * skTextStylesCache (ui-skiko/.../SkikoParagraphBuilder.nonAndroid.kt:372) is a file-level global
 * keyed on ComputedStyle.Immutable alone. The FontFamily.Resolver is passed to the loader (:667)
 * but is not part of the key, so the first resolver to build a given computed style bakes its
 * alias list into the shared entry.
 *
 * ROBOTO_WIDTH and SYSTEM_WIDTH below are what each resolver should produce. Whether a render is
 * correct is therefore readable straight off the printed number.
 */
@OptIn(InternalComposeUiApi::class)
object Fixture {

    const val TEXT = "Hamburgefonstiv 0123"

    private fun resource(name: String) =
        File(Fixture::class.java.getResource("/$name")!!.toURI())

    /**
     * Two resolvers that return different aliases for FontFamily.Default. Reaching this state
     * needs @InternalComposeUiApi today; see the linked issue about pinning FontFamily.Default.
     */
    class Resolvers(val stock: FontFamily.Resolver, val pinned: FontFamily.Resolver)

    fun setUp(): Resolvers {
        registerSkikoComposeImplementation()
        // ui-text has no text backend until ui registers ui-skiko; creating any scene does that.
        ImageComposeScene(width = 1, height = 1) {}.close()

        val stock = createFontFamilyResolver()
        val delegate = requireNotNull(stock.platformTypefacesLoader())
        val roboto = mapOf(
            FontWeight.Normal to Font(file = resource("Roboto-Regular.ttf"), weight = FontWeight.Normal),
            FontWeight.Bold to Font(file = resource("Roboto-Bold.ttf"), weight = FontWeight.Bold),
        )
        val pinnedBackend = object : PlatformTypefacesLoader by delegate {
            override val cacheKey: Any = "pinned-roboto"
            override fun loadPlatformTypes(
                fontFamily: FontFamily,
                fontWeight: FontWeight,
                fontStyle: FontStyle,
            ): Any =
                if (fontFamily == FontFamily.Default || fontFamily == FontFamily.SansSerif) {
                    delegate.loadBlocking(roboto[fontWeight] ?: roboto.getValue(FontWeight.Normal))!!
                } else {
                    delegate.loadPlatformTypes(fontFamily, fontWeight, fontStyle)
                }
        }
        return Resolvers(stock, createPlatformFontFamilyResolver(pinnedBackend))
    }

    fun style(background: Color = Color.Unspecified) = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Default,
        background = background,
    )

    /** Renders TEXT in its own scene and returns the measured width at density 1. */
    fun render(resolver: FontFamily.Resolver, style: TextStyle): Int {
        var w = 0
        val scene = ImageComposeScene(width = 600, height = 120, density = Density(1f)) {
            CompositionLocalProvider(LocalFontFamilyResolver provides resolver) {
                BasicText(TEXT, style = style, modifier = Modifier.onSizeChanged { w = it.width })
            }
        }
        scene.render()
        scene.close()
        return w
    }

    fun report(label: String, width: Int): Int {
        val what = when (width) {
            ROBOTO_WIDTH -> " (Roboto)"
            SYSTEM_WIDTH -> " (host OS system font)"
            else -> ""
        }
        println("  $label rendered $width px$what")
        return width
    }

    /**
     * The assertion is deliberately not a hardcoded width: the two resolvers use different fonts,
     * so whatever the host OS system font is, the two renders must not measure the same.
     */
    fun assertResolversDisagree(stockWidth: Int, pinnedWidth: Int) {
        assertNotEquals(
            stockWidth,
            pinnedWidth,
            "the pinned resolver rendered at the stock resolver's width, " +
                "so it used the stock resolver's font",
        )
    }

    /** Observed on macOS 26.3 / arm64. Used only to label the output. */
    const val ROBOTO_WIDTH = 249
    const val SYSTEM_WIDTH = 277
}

/** Run 1: both scenes use the same TextStyle. The second render reuses the first one's entry. */
class SameStyleTest {
    @Test fun secondResolverGetsTheFirstResolversFont() {
        val r = Fixture.setUp()
        println("run 1 -- same TextStyle, stock renders first")
        val stock = Fixture.report("stock ", Fixture.render(r.stock, Fixture.style()))
        val pinned = Fixture.report("pinned", Fixture.render(r.pinned, Fixture.style()))
        Fixture.assertResolversDisagree(stock, pinned)   // fails: this is the bug
    }
}

/** Run 2: the two styles differ only in `background`, which cannot affect advances but does change the key. */
class DifferentKeyTest {
    @Test fun bothResolversAreCorrectOnceTheKeysDiffer() {
        val r = Fixture.setUp()
        println("run 2 -- TextStyles differ in `background` only, so the cache keys differ")
        val pinned = Fixture.report("pinned", Fixture.render(r.pinned, Fixture.style(Color.Red)))
        val stock = Fixture.report("stock ", Fixture.render(r.stock, Fixture.style()))
        Fixture.assertResolversDisagree(stock, pinned)   // passes
    }
}

/** Run 3: same TextStyle as run 1, but the weak key is collected between the two renders. */
class ForcedGcTest {
    @Test fun collectingTheWeakKeyBetweenRendersHidesTheBug() {
        val r = Fixture.setUp()
        println("run 3 -- same TextStyle, System.gc() between the two renders")
        val stock = Fixture.report("stock ", Fixture.render(r.stock, Fixture.style()))
        System.gc()
        Thread.sleep(200)
        val pinned = Fixture.report("pinned", Fixture.render(r.pinned, Fixture.style()))
        Fixture.assertResolversDisagree(stock, pinned)   // passes, and that is the nondeterminism
    }
}
