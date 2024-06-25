package ru.citeck.ecos.ecom.processor.mail

import mu.KotlinLogging
import org.apache.camel.Exchange
import org.apache.camel.Processor
import org.apache.camel.component.mail.MailMessage
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.time.FastDateFormat
import ru.citeck.ecos.commons.mime.MimeTypes
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor
import java.io.InputStream
import java.text.ParseException
import java.time.Instant
import java.util.*
import javax.mail.BodyPart
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.MimeUtility

class EcomMailReaderProcessor : Processor {

    companion object {

        private val DATE_FORMAT = FastDateFormat.getInstance("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)

        private const val MAIL_FROM = "From"
        private const val MAIL_SUBJECT = "Subject"
        private const val MAIL_DATE = "Date"

        private val log = KotlinLogging.logger {}
    }

    override fun process(exchange: Exchange) {

        if (exchange.getIn().body == null) {
            log.debug("Received exchange with empty body, skipping")
            return
        }

        val message = exchange.getIn()

        val from = decodeText(message.getHeader(MAIL_FROM, String::class.java))
        val fromAddress = StringUtils.substringBetween(from, "<", ">")
        val fromDomain: String = getEmailDomain(fromAddress)

        val ecomMail = EcomMail(
            from,
            fromAddress,
            fromDomain,
            subject = decodeText(message.getHeader(MAIL_SUBJECT, String::class.java)),
            content = message.getHeader(MailBodyExtractor.MAIL_TEXT_ATT, String::class.java) ?: "",
            date = parseDate(message.getHeader(MAIL_DATE, String::class.java), from),
            attachments = readAttachments(exchange.getIn() as? MailMessage)
        )

        exchange.getIn().body = ecomMail
    }

    private fun readAttachments(message: MailMessage?): List<EcomMailAttachment> {
        message ?: return emptyList()
        val body = message.body
        if (body !is Multipart) {
            return emptyList()
        }
        val attachments = ArrayList<EcomMailAttachment>()
        for (i in 0 until body.count) {
            val bodyPart = body.getBodyPart(i)
            if (bodyPart.disposition != Part.ATTACHMENT && bodyPart.disposition != Part.INLINE) {
                continue
            }
            var fileName = decodeText(bodyPart.fileName)
            if (fileName.isBlank()) {
                val baseName = UUID.randomUUID().toString()
                val type = MimeTypes.parseOrBin(bodyPart.contentType)
                fileName = baseName + "." + type.getExtension().ifBlank { ".bin" }
            }
            attachments.add(BodyPartAttachment(bodyPart, fileName))
        }
        return attachments
    }

    private fun parseDate(date: String?, mailFrom: String): Instant {
        if (date.isNullOrBlank()) {
            return Instant.EPOCH
        }
        val parsedDate = try {
            DATE_FORMAT.parse(date)
        } catch (e: ParseException) {
            log.error(e) { "Invalid date in mail from '$mailFrom'. Date: '$date'" }
            null
        }
        return parsedDate?.toInstant() ?: Instant.EPOCH
    }

    private fun decodeText(value: String?): String {
        return value?.let { MimeUtility.decodeText(value) } ?: ""
    }

    private fun getEmailDomain(fromEmail: String): String {
        return fromEmail.substring(fromEmail.indexOf("@") + 1)
    }

    private inner class BodyPartAttachment(
        private val part: BodyPart,
        private val fileName: String
    ) : EcomMailAttachment {

        override fun getName(): String {
            return fileName
        }

        override fun <T> readData(action: (InputStream) -> T): T {
            return part.inputStream.use(action)
        }
    }
}