package ru.citeck.ecos.ecom.processor.mail

import java.io.InputStream

interface EcomMailAttachment {

    /**
     * Return value of Content-Id header for inline attachments
     * When attachment is not inline this id will be empty string.
     */
    fun getContentId(): String

    fun getName(): String

    /**
     * Read attachment data
     * If data is empty, then ifEmpty callback will be invoked
     */
    fun <T> readData(action: (InputStream) -> T, ifEmpty: () -> T): T
}
