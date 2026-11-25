package dev.dawitsh.noroshi.core.codec

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** A small read-only XML tree, enough for the router's `<RGW>` answers. */
class XmlNode(val name: String, val text: String, val children: List<XmlNode>) {
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    /** The first descendant called [name], depth first, like jQuery's `$(xml).find(name)`. */
    fun find(name: String): XmlNode? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    fun findAll(name: String): List<XmlNode> = buildList {
        for (c in children) {
            if (c.name == name) add(c)
            addAll(c.findAll(name))
        }
    }

    /** Text of the child at a slash path ("wan/cellular/rssi"), or "" when it is not there. */
    fun at(path: String): String {
        var node: XmlNode = this
        for (part in path.split('/')) node = node.child(part) ?: return ""
        return node.text
    }

    /** Text of the first descendant called [name], or "". */
    fun textOf(name: String): String = find(name)?.text ?: ""

    companion object {
        /**
         * Parses [xml]. The router declares `US-ASCII` but answers what it likes, so the text is
         * read as characters and the declared encoding is ignored. No DTDs, no entities.
         */
        fun parse(xml: String): XmlNode {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                for ((feature, value) in listOf(
                    "http://apache.org/xml/features/disallow-doctype-decl" to true,
                    "http://xml.org/sax/features/external-general-entities" to false,
                    "http://xml.org/sax/features/external-parameter-entities" to false,
                )) {
                    try {
                        setFeature(feature, value)
                    } catch (_: Exception) {
                        // Android's parser knows fewer features; it does not fetch external entities anyway.
                    }
                }
            }
            val body = xml.trim().replace(Regex("""^<\?xml[^>]*\?>"""), "").trim()
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
            return convert(doc.documentElement)
        }

        private fun convert(element: Element): XmlNode {
            val kids = mutableListOf<XmlNode>()
            val text = StringBuilder()
            val nodes = element.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                when (n.nodeType) {
                    Node.ELEMENT_NODE -> kids += convert(n as Element)
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(n.nodeValue)
                }
            }
            return XmlNode(element.tagName, text.toString().trim(), kids)
        }
    }
}

private fun escapeXml(text: String): String = buildString {
    for (c in text) when (c) {
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '&' -> append("&amp;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}

/**
 * Builds the body of a `method=set` call the way the panel's `XML_Operations.createXML` does:
 * each entry is a path under `RGW` ("RGW/message/flag/message_flag") and its value; elements
 * already made are reused, so entries sharing a parent end up side by side.
 */
fun buildRgwXml(fields: List<Pair<String, String>>): String {
    class Building(val name: String) {
        val kids = mutableListOf<Building>()
        var value: String? = null
    }

    val root = Building("RGW")
    for ((path, value) in fields) {
        val parts = path.split('/')
        require(parts.firstOrNull() == "RGW" && parts.size >= 2) { "path must start with RGW/: $path" }
        var node = root
        for (part in parts.drop(1).dropLast(1)) {
            node = node.kids.firstOrNull { it.name == part } ?: Building(part).also { node.kids += it }
        }
        node.kids += Building(parts.last()).also { it.value = value }
    }

    fun write(b: Building, out: StringBuilder) {
        out.append('<').append(b.name).append('>')
        b.value?.let { out.append(escapeXml(it)) }
        b.kids.forEach { write(it, out) }
        out.append("</").append(b.name).append('>')
    }

    return StringBuilder("<?xml version=\"1.0\" encoding=\"US-ASCII\"?> ").also { write(root, it) }.toString()
}
