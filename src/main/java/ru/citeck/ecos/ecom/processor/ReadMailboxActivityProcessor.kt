package ru.citeck.ecos.ecom.processor

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.camel.Exchange
import org.apache.camel.Processor
import org.apache.commons.lang3.StringUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import ru.citeck.ecos.ecom.dto.FindRecordDTO
import ru.citeck.ecos.ecom.processor.mail.EcomMail
import ru.citeck.ecos.model.lib.type.constants.TypeConstants
import ru.citeck.ecos.model.lib.utils.ModelUtils
import ru.citeck.ecos.records2.predicate.PredicateService
import ru.citeck.ecos.records2.predicate.model.Predicates
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.lib.model.type.dto.TypeDef

@Component
class ReadMailboxActivityProcessor(
    private val recordsService: RecordsService,
    @Value("\${mail.activity.pattern}") private val regexPattern: String
) : Processor {

    companion object {
        const val SKIP_MESSAGE_PROPERTY = "skipMessage"

        private val log = KotlinLogging.logger {}
    }

    private val regex: Regex by lazy { regexPattern.toRegex() }
    private val hasActivityAspect = ModelUtils.getAspectRef("has-ecos-activities")

    override fun process(exchange: Exchange) {
        val mail = exchange.getIn().getBody(EcomMail::class.java)
        val subject = mail.subject
        if (StringUtils.isBlank(subject)) {
            exchange.getIn().body = ""
            setSkipMessage(exchange, "Received exchange with empty subject, skipping")
            return
        }

        val matchResult = regex.find(subject)
        val alias = matchResult?.groups?.get("key")?.value
        val searchValue = matchResult?.groups?.get("value")?.value
        if (alias == null || searchValue == null) {
            setSkipMessage(exchange, "(alias: value) was not found in the subject. Skip message")
            return
        }

        val typeDef = recordsService.queryOne(
            RecordsQuery.create()
                .withSourceId("${TypeConstants.TYPE_APP}/${TypeConstants.TYPE_SOURCE}")
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                    Predicates.and(
                        Predicates.contains("aspects[]?str|join()", "\"alias\":\"$alias\""),
                    )
                )
                .build(),
            TypeDef::class.java
        )
        if (typeDef == null) {
            setSkipMessage(exchange, "Type with alias $alias not found.")
            return
        }

        val configData = typeDef.aspects.find { hasActivityAspect == it.ref }?.config
        if (configData == null || configData.isEmpty()) {
            error("Aspect has-ecos-activities not specified in type=${typeDef.id}")
        }

        val findRecordDTO = FindRecordDTO(
            typeDef.sourceId,
            searchValue,
            configData["searchAtt"].asText()
        )
        exchange.setVariable(AddEmailActivityProcessor.FIND_RECORD_VARIABLE, findRecordDTO)
    }

    private fun setSkipMessage(exchange: Exchange, message: String) {
        exchange.setProperty(SKIP_MESSAGE_PROPERTY, true)
        log.error { message }
    }
}
