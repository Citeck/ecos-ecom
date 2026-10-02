package ru.citeck.ecos.ecom.processor

import org.yaml.snakeyaml.Yaml
import ru.citeck.ecos.records3.RecordsService

/**
 * Creates the mail lead processors with the patterns and subjects of the production config,
 * so that the tests parse emails exactly as the service does.
 */
object MailLeadTestConfig {

    private val mailLead: Map<String, Any?> by lazy {
        val stream = MailLeadTestConfig::class.java.classLoader.getResourceAsStream("config/application.yml")
            ?: error("config/application.yml is not found")
        val config = stream.use { Yaml().load<Map<String, Any?>>(it) }
        child(child(config, "mail"), "lead")
    }

    fun createLeadProcessor(recordsService: RecordsService): CreateLeadProcessor {
        val patterns = child(mailLead, "pattern")
        val args = listOf(
            "from", "company", "subject", "fio", "position", "department", "phone",
            "email", "comment", "siteFrom", "numberOfUsers", "gaClientId", "ymClientId"
        ).map { patterns[it] as String }

        val constructor = CreateLeadProcessor::class.java.getDeclaredConstructor(
            *Array(args.size) { String::class.java }
        )
        constructor.isAccessible = true
        val processor = constructor.newInstance(*args.toTypedArray())
        processor.setRecordsService(recordsService)
        return processor
    }

    fun createReadMailboxProcessor(): ReadMailboxCRMProcessor {
        val patterns = child(mailLead, "pattern")
        val processor = ReadMailboxCRMProcessor(
            patterns["dealNumber"] as String,
            patterns["gaClientId"] as String,
            patterns["ymClientId"] as String
        )
        val subjects = child(mailLead, "subject")
        val fields = mapOf(
            "dealSubjectsConsult" to "consult",
            "dealSubjectsDemonstration" to "demonstration",
            "dealSubjectDemoAccess" to "demo-access",
            "dealSubjectCommunity" to "community",
            "dealSubjectPrice" to "price",
            "dealSubjectCloud" to "cloud",
            "communitySubscription" to "community-subscription",
            "partnershipRequest" to "partnership-request",
            "specialOffer" to "special-offer"
        )
        fields.forEach { (fieldName, key) ->
            val field = ReadMailboxCRMProcessor::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            val value = subjects[key].toString()
            // Spring splits a comma-separated value into an array and trims the items
            field.set(
                processor,
                if (field.type.isArray) value.split(",").map { it.trim() }.toTypedArray() else value
            )
        }
        return processor
    }

    @Suppress("UNCHECKED_CAST")
    private fun child(map: Map<String, Any?>, key: String): Map<String, Any?> {
        return map[key] as? Map<String, Any?> ?: error("'$key' is not found in config/application.yml")
    }
}
