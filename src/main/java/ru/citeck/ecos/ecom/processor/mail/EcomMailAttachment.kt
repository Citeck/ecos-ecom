package ru.citeck.ecos.ecom.processor.mail

import java.io.InputStream

interface EcomMailAttachment {

    fun getName(): String

    /**
     * Read attachment data
     * If data is empty, then ifEmpty callback will be invoked
     */
    fun <T> readData(action: (InputStream) -> T, ifEmpty: () -> T): T
}
