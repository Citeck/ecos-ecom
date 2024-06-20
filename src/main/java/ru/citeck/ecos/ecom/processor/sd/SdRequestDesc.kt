package ru.citeck.ecos.ecom.processor.sd

import ru.citeck.ecos.model.lib.utils.ModelUtils

object SdRequestDesc {

    val TYPE = ModelUtils.getTypeRef("sd-request-type")

    const val ATT_CREATED_AUTOMATICALLY = "createdAutomatically"
    const val ATT_PRIORITY = "priority"
    const val ATT_LETTER_CONTENT_WO_TAGS = "letterContentWithoutTags"
    const val ATT_CLIENT = "client"
    const val ATT_INITIATOR = "initiator"
    const val ATT_LETTER_CONTENT = "letterContent"
    const val ATT_DATE_RECEIVED = "dateReceived"
    const val ATT_AUTHOR = "author"
    const val ATT_LETTER_TOPIC = "letterTopic"
}