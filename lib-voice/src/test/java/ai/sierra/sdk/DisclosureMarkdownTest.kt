// Copyright Sierra

package ai.sierra.sdk

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DisclosureMarkdownTest {
    private val clickedUrls = mutableListOf<String>()

    private fun render(markdown: String) = disclosureText(markdown) { clickedUrls.add(it.toString()) }

    /** Returns the text under each link and records the URL each link opens in [clickedUrls]. */
    private fun clickLinks(text: Spanned): List<String> =
        text.getSpans(0, text.length, ClickableSpan::class.java).map { span ->
            span.onClick(View(RuntimeEnvironment.getApplication()))
            text.substring(text.getSpanStart(span), text.getSpanEnd(span))
        }

    @Test
    fun plainTextIsUnchanged() {
        val text = "By proceeding, you agree to our terms. (Calls may be recorded.) [Note]"
        val rendered = render(text)
        assertEquals(text, rendered.toString())
        assertEquals(0, rendered.getSpans(0, rendered.length, Any::class.java).size)
    }

    @Test
    fun rendersBoldItalicAndCode() {
        val rendered = render("**Bold**, *italic*, and `code`")
        assertEquals("Bold, italic, and code", rendered.toString())
        val styles = rendered.getSpans(0, rendered.length, StyleSpan::class.java)
            .map { rendered.substring(rendered.getSpanStart(it), rendered.getSpanEnd(it)) to it.style }
        assertEquals(listOf("Bold" to Typeface.BOLD, "italic" to Typeface.ITALIC), styles)
        val code = rendered.getSpans(0, rendered.length, TypefaceSpan::class.java).single()
        assertEquals("monospace", code.family)
        assertEquals("code", rendered.substring(rendered.getSpanStart(code), rendered.getSpanEnd(code)))
    }

    @Test
    fun rendersLinksWithAllowedSchemes() {
        val rendered = render(
            "See [Terms](http://example.com/terms), [email](mailto:help@example.com), and [call](tel:+15555550100)."
        )
        assertEquals("See Terms, email, and call.", rendered.toString())
        assertEquals(listOf("Terms", "email", "call"), clickLinks(rendered))
        assertEquals(listOf("http://example.com/terms", "mailto:help@example.com", "tel:+15555550100"), clickedUrls)
    }

    @Test
    fun blockedSchemeRendersPlainLabel() {
        val rendered = render("Open [this](javascript:alert(1)), [that](file:///etc/hosts), and [empty](https://)")
        assertEquals("Open this, that, and empty", rendered.toString())
        assertEquals(emptyList<String>(), clickLinks(rendered))
    }

    @Test
    fun linkKeepsBalancedParenthesesAndIgnoresTitle() {
        val rendered = render("""[Policy](https://example.com/privacy_(us) "Privacy").""")
        assertEquals("Policy.", rendered.toString())
        assertEquals(listOf("Policy"), clickLinks(rendered))
        assertEquals(listOf("https://example.com/privacy_(us)"), clickedUrls)
    }

    @Test
    fun linkSchemeIsNormalized() {
        clickLinks(render("[Policy](HTTPS://example.com/Privacy)"))
        assertEquals(listOf("https://example.com/Privacy"), clickedUrls)
    }
}
