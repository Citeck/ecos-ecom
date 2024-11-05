package ru.citeck.ecos.ecom.routes

import org.apache.camel.Endpoint
import org.apache.camel.builder.RouteBuilder
import java.util.*

object EcomCamelMailUtils {

    @JvmStatic
    fun fromMailUri(builder: RouteBuilder, uri: String): Endpoint {

        val endPoint = if (uri.startsWith("imap")) {
            uri
        } else {
            "imap://$uri"
        }
        val additionalMailProps = Properties()
        additionalMailProps.setProperty("mail.imaps.partialfetch", "false")
        return builder.context.getEndpoint(
            endPoint,
            mapOf("additionalJavaMailProperties" to additionalMailProps)
        )
    }
}
