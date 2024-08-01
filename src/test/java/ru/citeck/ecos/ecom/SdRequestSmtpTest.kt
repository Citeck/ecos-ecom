package ru.citeck.ecos.ecom

import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import ru.citeck.ecos.commons.utils.resource.ResourceUtils
import java.io.FileInputStream

class SdRequestSmtpTest : SdRequestTestBase() {

    @Test
    fun test() {
        sendEmail("test", "abcd", emptyMap())

        Thread.sleep(3000)

        val sdRequests = getSdRequests()

        assertThat(sdRequests).hasSize(1)
        assertThat(sdRequests[0].letterTopic).isEqualTo("test")
        assertThat(sdRequests[0].initiator).isEqualTo("emodel/clients-type@test-user")
        assertThat(sdRequests[0].author).isEqualTo("Petr Ivanov <petr@test.com>")
        assertThat(sdRequests[0].createdAutomatically).isEqualTo(true)
        assertThat(sdRequests[0].priority).isEqualTo("medium")
        assertThat(sdRequests[0].letterContent).contains("abcd")
    }

    @Test
    @Disabled
    fun testWithMailFile() {

        val file = ResourceUtils.getFile("classpath:Message17192186730071576917.eml")

        val message: MimeMessage = FileInputStream(file).use { MimeMessage(getMailSession(), it) }

        sendEMail(message)
        Thread.sleep(10000)
    }
}
