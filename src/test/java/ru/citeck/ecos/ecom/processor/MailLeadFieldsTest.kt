package ru.citeck.ecos.ecom.processor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MailLeadFieldsTest {

    @Test
    fun companyFromFormWinsOverOtherSources() {
        val name = MailLeadFields.leadName("ООО Ромашка", "Иван Петров", "Robot <noreply@site.ru>", "noreply@site.ru")
        assertThat(name).isEqualTo("ООО Ромашка")
    }

    @Test
    fun fullNameFromFormIsUsedWithoutCompany() {
        val name = MailLeadFields.leadName("", "Иван Петров", "Robot <noreply@site.ru>", "noreply@site.ru")
        assertThat(name).isEqualTo("Иван Петров")
    }

    @Test
    fun senderNameIsTakenFromQuotedHeader() {
        val name = MailLeadFields.leadName("", "", "\"CFO Russia\" <info@cfo-russia.ru>", "info@cfo-russia.ru")
        assertThat(name).isEqualTo("CFO Russia")
    }

    @Test
    fun senderNameIsTakenFromUnquotedHeader() {
        val name = MailLeadFields.leadName(null, null, "Leah Smith <leah@sendnovo.com>", "leah@sendnovo.com")
        assertThat(name).isEqualTo("Leah Smith")
    }

    @Test
    fun addressIsUsedWhenHeaderHasNoName() {
        assertThat(MailLeadFields.leadName("", "", "<info@x.ru>", "info@x.ru")).isEqualTo("info@x.ru")
        assertThat(MailLeadFields.leadName("", "", "info@x.ru", "info@x.ru")).isEqualTo("info@x.ru")
        assertThat(MailLeadFields.leadName("", "", "\"\" <info@x.ru>", "info@x.ru")).isEqualTo("info@x.ru")
        assertThat(MailLeadFields.leadName("", "", "  <info@x.ru>", "info@x.ru")).isEqualTo("info@x.ru")
    }

    @Test
    fun blankSourcesGiveEmptyName() {
        assertThat(MailLeadFields.leadName(null, null, null, null)).isEmpty()
        assertThat(MailLeadFields.leadName(" ", "", "  ", "")).isEmpty()
    }

    @Test
    fun nameOfMaxLengthIsKept() {
        val name = "a".repeat(MailLeadFields.MAX_NAME_LENGTH)
        assertThat(MailLeadFields.leadName(name, null, null, null)).isEqualTo(name)
    }

    @Test
    fun longNameIsCutWithEllipsis() {
        val name = MailLeadFields.leadName("b".repeat(MailLeadFields.MAX_NAME_LENGTH + 1), null, null, null)
        assertThat(name).hasSize(MailLeadFields.MAX_NAME_LENGTH)
        assertThat(name).isEqualTo("b".repeat(MailLeadFields.MAX_NAME_LENGTH - 1) + "…")
    }

    @Test
    fun nameIsTrimmedBeforeLengthCheck() {
        val name = "c".repeat(MailLeadFields.MAX_NAME_LENGTH)
        assertThat(MailLeadFields.leadName("  $name  ", null, null, null)).isEqualTo(name)
    }

    @Test
    fun subjectLineEscapesHtml() {
        assertThat(MailLeadFields.subjectLine("  Offer <b>&\"deal\"</b> "))
            .isEqualTo("<b>Тема письма:</b> Offer &lt;b&gt;&amp;&quot;deal&quot;&lt;/b&gt;<br>")
    }

    @Test
    fun subjectLineIsEmptyWithoutSubject() {
        assertThat(MailLeadFields.subjectLine(null)).isEmpty()
        assertThat(MailLeadFields.subjectLine("  ")).isEmpty()
    }
}
