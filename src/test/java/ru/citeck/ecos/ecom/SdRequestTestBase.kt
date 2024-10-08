package ru.citeck.ecos.ecom

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import ru.citeck.ecos.config.lib.service.EcosConfigServiceFactory
import ru.citeck.ecos.ecom.processor.sd.SdEcomMailProcessor
import ru.citeck.ecos.ecom.processor.sd.SdRequestDesc
import ru.citeck.ecos.ecom.routes.ReadMailboxSDRoute
import ru.citeck.ecos.model.lib.ModelServiceFactory
import ru.citeck.ecos.model.lib.type.dto.TypeInfo
import ru.citeck.ecos.model.lib.type.repo.TypesRepo
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.RecordsServiceFactory
import ru.citeck.ecos.records3.record.dao.impl.mem.InMemDataRecordsDao
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.test.commons.EcosWebAppApiMock
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.*

abstract class SdRequestTestBase {

    companion object {
        private const val USERNAME = "test"
        private const val PASSWORD = "test"
        private const val INBOX_EMAIL = "test@test.com"

        const val CLIENTS_SRC_ID = "emodel/clients-type"
        const val SD_REQ_SRC_ID = "emodel/sd-request-type"
    }

    lateinit var greenMail: GreenMail
    lateinit var camelCtx: DefaultCamelContext
    lateinit var recordsService: RecordsService

    @BeforeEach
    fun setup() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.reset()
        greenMail.setUser(INBOX_EMAIL, USERNAME, PASSWORD)

        val recsServiceFactory = RecordsServiceFactory()

        val modelServices = object : ModelServiceFactory() {
            override fun createTypesRepo(): TypesRepo {
                return object : TypesRepo {
                    override fun getChildren(typeRef: EntityRef): List<EntityRef> {
                        return emptyList()
                    }
                    override fun getTypeInfo(typeRef: EntityRef): TypeInfo? {
                        if (typeRef.getLocalId() == SdRequestDesc.TYPE.getLocalId()) {
                            return TypeInfo.create()
                                .withId(SdRequestDesc.TYPE.getLocalId())
                                .withSourceId(SD_REQ_SRC_ID)
                                .build()
                        }
                        return null
                    }
                }
            }
        }
        modelServices.setRecordsServices(recsServiceFactory)

        recordsService = recsServiceFactory.recordsService
        recordsService.register(InMemDataRecordsDao(CLIENTS_SRC_ID))
        recordsService.create(
            CLIENTS_SRC_ID,
            mapOf(
                "name" to "TestClient",
                "emailDomain" to "test.com",
                "users" to listOf(
                    mapOf(
                        "id" to "test-user",
                        "email" to "petr@test.com",
                    ),
                ),
            ),
        )
        recordsService.register(InMemDataRecordsDao(SD_REQ_SRC_ID))

        val webappApi = EcosWebAppApiMock()

        val processor = SdEcomMailProcessor(recordsService, webappApi.getContentApi())

        val configServices = EcosConfigServiceFactory()

        val readMailRoute = ReadMailboxSDRoute()
        readMailRoute.setSdEcomMailProcessor(processor)

        configServices.beanConsumersService.registerConsumers(readMailRoute)

        val host = greenMail.imap.bindTo + ":" + greenMail.imap.port
        configServices.inMemConfigProvider.setConfig(
            "mail-inbox-sd",
            "imap://$host?username=$USERNAME&password=$PASSWORD&delete=false&unseen=true&delay=1000"
        )

        val registry = DefaultRegistry()
        camelCtx = DefaultCamelContext(registry)
        camelCtx.addRoutes(readMailRoute)

        camelCtx.start()
    }

    fun getSdRequests(): List<SdRequestData> {
        return recordsService.query(
            RecordsQuery.create { withSourceId(SD_REQ_SRC_ID) },
            SdRequestData::class.java,
        ).getRecords()
    }

    fun sendEMail(message: Message) {
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(INBOX_EMAIL))
        Transport.send(message)
    }

    fun sendEmail(subject: String, body: String, attachments: Map<String, ByteArray>) {

        val message: Message = MimeMessage(getMailSession())
        message.setFrom(InternetAddress("Petr Ivanov <petr@test.com>"))
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(INBOX_EMAIL))
        message.subject = subject

        val mimeBodyPart = MimeBodyPart()
        mimeBodyPart.setContent(body, "text/html; charset=utf-8")

        val multipart: Multipart = MimeMultipart()
        multipart.addBodyPart(mimeBodyPart)

        message.setContent(multipart)

        Transport.send(message)
    }

    fun getMailSession(): Session {

        val prop = Properties()
        prop["mail.smtp.auth"] = false
        prop["mail.smtp.host"] = greenMail.smtp.bindTo
        prop["mail.smtp.port"] = greenMail.smtp.port

        return Session.getInstance(prop, null)
    }

    @AfterEach
    fun afterEach() {
        camelCtx.stop()
        greenMail.stop()
    }

    class SdRequestData(
        val id: String?,
        val dateReceived: String,
        val letterTopic: String,
        val initiator: String,
        val client: EntityRef,
        val author: String,
        val createdAutomatically: Boolean,
        val priority: String,
        val letterContent: String,
    )
}
