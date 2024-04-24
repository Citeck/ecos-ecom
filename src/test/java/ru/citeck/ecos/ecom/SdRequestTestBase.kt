package ru.citeck.ecos.ecom

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import ru.citeck.ecos.config.lib.service.EcosConfigServiceFactory
import ru.citeck.ecos.ecom.processor.ReadMailboxSDProcessor
import ru.citeck.ecos.ecom.routes.CreateCommentRoute
import ru.citeck.ecos.ecom.routes.CreateSDRoute
import ru.citeck.ecos.ecom.routes.ReadMailboxSDRoute
import ru.citeck.ecos.ecom.service.cameldsl.RecordsDaoEndpoint
import ru.citeck.ecos.ecom.service.documents.DocumentDao
import ru.citeck.ecos.records3.RecordsService
import ru.citeck.ecos.records3.RecordsServiceFactory
import ru.citeck.ecos.records3.record.dao.impl.mem.InMemDataRecordsDao
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery
import ru.citeck.ecos.webapp.api.content.EcosContentApi
import ru.citeck.ecos.webapp.api.entity.EntityRef
import java.util.*
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

abstract class SdRequestTestBase {

    companion object {
        private const val USERNAME = "test"
        private const val PASSWORD = "test"
        private const val INBOX_EMAIL = "test@test.com"

        const val CLIENTS_SRC_ID = "emodel/clients-type"
        const val SD_REQ_SRC_ID = "emodel/task-tracker"
    }

    lateinit var greenMail: GreenMail
    lateinit var camelCtx: DefaultCamelContext
    lateinit var recordsService: RecordsService

    @BeforeEach
    fun setup() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.reset()
        greenMail.setUser(INBOX_EMAIL, USERNAME, PASSWORD)

        recordsService = RecordsServiceFactory().recordsServiceV1
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

        val documentsDao = Mockito.mock(DocumentDao::class.java)

        val processor = ReadMailboxSDProcessor()
        processor.setRecordsService(recordsService)
        processor.setDocumentDao(documentsDao)

        val configServices = EcosConfigServiceFactory()

        val readMailRoute = ReadMailboxSDRoute()
        readMailRoute.setReadMailboxSDProcessor(processor)

        configServices.beanConsumersService.registerConsumers(readMailRoute)

        val host = greenMail.imap.bindTo + ":" + greenMail.imap.port
        configServices.inMemConfigProvider.setConfig("mail-inbox-sd", "imap://$host?username=$USERNAME&password=$PASSWORD&delete=false&unseen=true&delay=1000")

        val recsDaoEndpoint = RecordsDaoEndpoint()
        recsDaoEndpoint.documentDao = documentsDao
        recsDaoEndpoint.ecosContentApi = Mockito.mock(EcosContentApi::class.java)
        recsDaoEndpoint.recordsService = recordsService

        val registry = DefaultRegistry()
        registry.bind("recsDaoEndpoint", recsDaoEndpoint)
        camelCtx = DefaultCamelContext(registry)
        camelCtx.addRoutes(readMailRoute)
        camelCtx.addRoutes(CreateSDRoute())
        camelCtx.addRoutes(CreateCommentRoute())

        camelCtx.start()
    }

    fun getSdRequests(): List<SdRequestData> {
        return recordsService.query(
            RecordsQuery.create { withSourceId(SD_REQ_SRC_ID) },
            SdRequestData::class.java,
        ).getRecords()
    }

    fun sendEmail(subject: String, body: String, attachments: List<ByteArray>) {
        val prop = Properties()
        prop["mail.smtp.auth"] = false
        prop["mail.smtp.host"] = greenMail.smtp.bindTo
        prop["mail.smtp.port"] = greenMail.smtp.port

        val session: Session = Session.getInstance(prop, null)

        val message: Message = MimeMessage(session)
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
