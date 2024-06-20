package ru.citeck.ecos.ecom.service

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

object HtmlUtils {

    private val validTags = setOf("strong", "em", "ul", "li", "ol", "p", "i", "b", "u", "a", "br", "span")
    private val validAttributes = mapOf("a" to setOf("href"))

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
                    } else if (!validTags.contains(tag)) {
                        var newNode: Node = TextNode(node.wholeText())
                        if (depth == 1) {
                            newNode = Element("p").appendChild(newNode)
                        }
                        node.replaceWith(newNode)
                    } else {
                        val validAtts = validAttributes[tag] ?: emptySet()
                        for (att in node.attributes()) {
                            if (!validAtts.contains(att.key)) {
                                node.removeAttr(att.key)
                            }
                        }
                    }
                }
            }
        })
        document.outputSettings().prettyPrint(false)
        return document.body().html().replace("&nbsp;", " ")
    }
}