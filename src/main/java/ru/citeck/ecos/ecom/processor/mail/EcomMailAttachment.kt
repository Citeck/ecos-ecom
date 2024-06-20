package ru.citeck.ecos.ecom.processor.mail

import java.io.InputStream

interface EcomMailAttachment {

    fun getName(): String

    fun <T> readData(action: (InputStream) -> T): T
}