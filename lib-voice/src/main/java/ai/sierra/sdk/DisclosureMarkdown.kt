// Copyright Sierra

package ai.sierra.sdk

import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser

private val ALLOWED_DISCLOSURE_LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

private val disclosureParser = Parser.builder().enabledBlockTypes(emptySet()).build()

/**
 * Renders the voice disclosure's inline Markdown. Only http, https, mailto, and tel links with a
 * target are tappable and sent to [onLinkClick]; any other link renders as its plain label.
 */
internal fun disclosureText(markdown: String, onLinkClick: (Uri) -> Unit): SpannableStringBuilder {
    val builder = SpannableStringBuilder()
    disclosureParser.parse(markdown).accept(object : AbstractVisitor() {
        override fun visit(text: Text) {
            builder.append(text.literal)
        }

        override fun visit(htmlInline: HtmlInline) {
            builder.append(htmlInline.literal)
        }

        override fun visit(code: Code) = span(TypefaceSpan("monospace")) { builder.append(code.literal) }

        override fun visit(softLineBreak: SoftLineBreak) {
            builder.append('\n')
        }

        override fun visit(hardLineBreak: HardLineBreak) {
            builder.append('\n')
        }

        override fun visit(paragraph: Paragraph) {
            if (paragraph.previous != null) builder.append("\n\n")
            visitChildren(paragraph)
        }

        override fun visit(emphasis: Emphasis) = span(StyleSpan(Typeface.ITALIC)) { visitChildren(emphasis) }

        override fun visit(strongEmphasis: StrongEmphasis) = span(StyleSpan(Typeface.BOLD)) { visitChildren(strongEmphasis) }

        override fun visit(link: Link) {
            // Intent scheme matching is case-sensitive, so send the lowercase scheme.
            val uri = Uri.parse(link.destination).normalizeScheme()
            val target = uri.schemeSpecificPart.orEmpty().trimStart('/')
            if (uri.scheme !in ALLOWED_DISCLOSURE_LINK_SCHEMES || target.isEmpty()) return visitChildren(link)
            span(object : ClickableSpan() {
                override fun onClick(widget: View) = onLinkClick(uri)
            }) { visitChildren(link) }
        }

        private fun span(what: Any, content: () -> Unit) {
            val start = builder.length
            content()
            builder.setSpan(what, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    })
    return builder
}
