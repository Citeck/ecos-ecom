package ru.citeck.ecos.ecom.service

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

object HtmlUtils {

    private val VALID_TAGS = setOf("strong", "em", "ul", "li", "ol", "p", "i", "b", "u", "a", "br", "span", "img")

    private val VALID_ATTS_FOR_ALL = setOf(
        "style"
    )
    private val VALID_ATTS_BY_TAG = listOf(
        "a" to setOf("href"),
        "img" to setOf("src", "alt")
    ).associate {
        it.first to setOf(*it.second.toTypedArray(), *VALID_ATTS_FOR_ALL.toTypedArray())
    }

    private val ALLOWED_INLINE_STYLES = setOf(
        "font-size",
        "font-style",
        "color",
        "white-space",
        "grid-template-columns",
        "width",
        "height",
        "border",
        "vertical-align",
        "text-align",
        "padding-inline-start",
    ).map { "$it:" }

    /**
     * Convert the HTML content of the email to a format suitable
     * for display and editing in a WYSIWYG editor
     */
    fun convertHtmlToFormattedText(text: String): String {

        val document = Jsoup.parse(text)

        document.body().traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element && node.tagName() == "body") {
                    return
                }
                if (node is Element) {
                    val tag = node.tagName()
                    if (tag == "div") {
                        if (depth == 1) {
                            node.replaceWith(Element("p").appendChildren(node.childNodes()))
                        }
                    } else if (!VALID_TAGS.contains(tag)) {
                        var newNode: Node = TextNode(node.wholeText())
                        if (depth == 1) {
                            newNode = Element("p").appendChild(newNode)
                        }
                        node.replaceWith(newNode)
                    } else {
                        val validAtts = VALID_ATTS_BY_TAG[tag] ?: emptySet()
                        val iter = node.attributes().iterator()
                        while (iter.hasNext()) {
                            val att = iter.next()
                            if (!validAtts.contains(att.key)) {
                                iter.remove()
                            } else if (att.key == "style") {
                                val styleValue = att.value
                                if (styleValue.contains("url")) {
                                    iter.remove()
                                } else {
                                    val safeStyle = styleValue.split(";")
                                        .map { it.trim() }
                                        .filter { style ->
                                            ALLOWED_INLINE_STYLES.any { style.startsWith(it) }
                                        }.joinToString(";")
                                    if (safeStyle != styleValue) {
                                        att.setValue(safeStyle)
                                    }
                                }
                            }
                        }

                    }
                } else if (node is TextNode && depth == 1) {
                    node.replaceWith(Element("p").appendChild(TextNode(node.wholeText)))
                }
            }
        })
        document.outputSettings().prettyPrint(false)
        return document.body().html().replace("&nbsp;", " ")
    }
}
