package ru.citeck.ecos.ecom

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
}
