package ru.citeck.ecos.ecom.processor.mail

import java.time.Instant

class EcomMail(
    val from: String,
    val fromAddress: String,
    val fromDomain: String,
    val subject: String,
    val content: String,
    val date: Instant,
    val attachments: List<EcomMailAttachment>
) {

    override fun toString(): String {
        return "EcomMail(" +
            "from='$from', " +
            "fromAddress='$fromAddress', " +
            "fromDomain='$fromDomain', " +
            "subject='$subject', " +
            "content='$content', " +
            "date=$date, " +
            "attachments=${attachments.map { it.getName() }})"
    }
}
