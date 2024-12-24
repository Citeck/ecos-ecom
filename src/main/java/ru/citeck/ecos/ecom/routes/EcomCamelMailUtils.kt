package ru.citeck.ecos.ecom.routes

import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.internet.MimeMessage
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.RouteDefinition
import java.util.*


object EcomCamelMailUtils {

    @JvmStatic
    fun fromMailUri(builder: RouteBuilder, uri: String): RouteDefinition {

        val endPoint = if (uri.startsWith("imap")) {
            uri
        } else {
            "imap://$uri"
        }
        val additionalMailProps = Properties()
        listOf("imap", "imaps").forEach { protocol ->
            additionalMailProps.setProperty("mail.$protocol.partialfetch", "false")
        }

        return builder.from(
            builder.context.getEndpoint(
                endPoint,
                mapOf(
                    "additionalJavaMailProperties" to additionalMailProps,
                    // Disable content parsing to avoid jakarta.mail.MessagingException: Unable to load BODYSTRUCTURE
                    // see https://jira.citeck.ru/browse/ECOSCOM-5644
                    // see https://javaee.github.io/javamail/FAQ#imapserverbug
                    "mapMailMessage" to false
                )
            )
        ).autoStartup(!Objects.equals(uri, "disabled"))
            .process {
                val message = it.getIn().getBody(Message::class.java)
                it.getIn().body = prepareExchangeBody(message)
            }
    }

    private fun prepareExchangeBody(message: Message?): Any? {
        message ?: return null
        return try {
            message.content
        } catch (messEx: MessagingException) {
            // Making sure that it's a BODYSTRUCTURE error
            val exMsg = messEx.message ?: ""
            if (message is MimeMessage && exMsg.contains("unable to load bodystructure", true)) {
                // Creating local copy of given MimeMessage to download and parse the Mime message locally
                MimeMessage(message).content
            } else {
                throw messEx
            }
        }
    }
}
