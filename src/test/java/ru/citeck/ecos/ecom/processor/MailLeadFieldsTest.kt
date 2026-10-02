package ru.citeck.ecos.ecom.processor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MailLeadFieldsTest {

    @Test
    fun firstNonBlankCandidateIsUsed() {
        assertThat(MailLeadFields.leadName(null, " ", "Иван Петров", "ivan@x.ru")).isEqualTo("Иван Петров")
        assertThat(MailLeadFields.leadName("  CFO Russia ", "info@x.ru")).isEqualTo("CFO Russia")
    }

    @Test
    fun blankCandidatesGiveEmptyName() {
        assertThat(MailLeadFields.leadName()).isEmpty()
        assertThat(MailLeadFields.leadName(null, null)).isEmpty()
        assertThat(MailLeadFields.leadName(" ", "", "  ")).isEmpty()
    }

    @Test
    fun senderNameIsTakenFromHeader() {
        assertThat(MailLeadFields.senderName("\"CFO Russia\" <info@cfo-russia.ru>")).isEqualTo("CFO Russia")
        assertThat(MailLeadFields.senderName("Leah Smith <leah@sendnovo.com>")).isEqualTo("Leah Smith")
        assertThat(MailLeadFields.senderName("Иван Петров <ivan@x.ru>")).isEqualTo("Иван Петров")
    }

    @Test
    fun senderNameIsEmptyWhenHeaderHasNoName() {
        assertThat(MailLeadFields.senderName("<info@x.ru>")).isEmpty()
        assertThat(MailLeadFields.senderName("info@x.ru")).isEmpty()
        assertThat(MailLeadFields.senderName("\"\" <info@x.ru>")).isEmpty()
        assertThat(MailLeadFields.senderName("  <info@x.ru>")).isEmpty()
        assertThat(MailLeadFields.senderName(null)).isEmpty()
        assertThat(MailLeadFields.senderName("  ")).isEmpty()
    }

    @Test
    fun senderNameFollowsRfc5322() {
        assertThat(MailLeadFields.senderName("\"Sales <EU>\" <sales@x.com>")).isEqualTo("Sales <EU>")
        assertThat(MailLeadFields.senderName("\"O\\\"Brien\" <a@b.com>")).isEqualTo("O\"Brien")
        assertThat(MailLeadFields.senderName("a@b.com (Ivan Petrov)")).isEqualTo("Ivan Petrov")
    }

    @Test
    fun senderNameKeepsSpecialCharactersOfDecodedHeader() {
        // The header is decoded before parsing, so the name may hold characters that need quoting in RFC 5322
        assertThat(MailLeadFields.senderName("Петров, Иван <ivan@x.ru>")).isEqualTo("Петров, Иван")
        assertThat(MailLeadFields.senderName("Иванов И.И. <a@b.ru>")).isEqualTo("Иванов И.И.")
        assertThat(MailLeadFields.senderName("Ivan (Sales) <ivan@x.ru>")).isEqualTo("Ivan (Sales)")
        assertThat(MailLeadFields.senderName("ООО \"Ромашка\" <a@b.ru>")).isEqualTo("ООО \"Ромашка\"")
    }

    @Test
    fun senderNameIsEmptyForSeveralAddresses() {
        assertThat(MailLeadFields.senderName("Ivan <ivan@x.ru>, Petr <petr@y.ru>")).isEmpty()
        assertThat(MailLeadFields.leadName(MailLeadFields.senderName("Ivan <ivan@x.ru>, Petr <petr@y.ru>"), "ivan@x.ru"))
            .isEqualTo("ivan@x.ru")
    }

    @Test
    fun nameOfMaxLengthIsKept() {
        val name = "a".repeat(MailLeadFields.MAX_NAME_LENGTH)
        assertThat(MailLeadFields.leadName(name)).isEqualTo(name)
    }

    @Test
    fun longNameIsCutWithEllipsis() {
        val name = MailLeadFields.leadName("b".repeat(MailLeadFields.MAX_NAME_LENGTH + 1))
        assertThat(name).hasSize(MailLeadFields.MAX_NAME_LENGTH)
        assertThat(name).isEqualTo("b".repeat(MailLeadFields.MAX_NAME_LENGTH - 1) + "…")
    }

    @Test
    fun nameIsTrimmedBeforeLengthCheck() {
        val name = "c".repeat(MailLeadFields.MAX_NAME_LENGTH)
        assertThat(MailLeadFields.leadName("  $name  ")).isEqualTo(name)
    }

    @Test
    fun cutDoesNotSplitSurrogatePair() {
        val prefix = "d".repeat(MailLeadFields.MAX_NAME_LENGTH - 2)
        val name = MailLeadFields.leadName("$prefix🚀 Team")

        assertThat(name).isEqualTo("$prefix🚀…")
        assertThat(name.codePointCount(0, name.length)).isEqualTo(MailLeadFields.MAX_NAME_LENGTH)
    }

    @Test
    fun spaceBeforeEllipsisIsDropped() {
        val name = MailLeadFields.leadName("${"e".repeat(48)} fghij")
        assertThat(name).isEqualTo("e".repeat(48) + "…")
    }

    @Test
    fun subjectLineEscapesHtml() {
        assertThat(MailLeadFields.subjectLine("  Offer <b>&\"deal\"</b> 'x' "))
            .isEqualTo("<b>Тема письма:</b> Offer &lt;b&gt;&amp;&quot;deal&quot;&lt;/b&gt; &#39;x&#39;<br>")
    }

    @Test
    fun subjectLineKeepsNonAsciiText() {
        assertThat(MailLeadFields.subjectLine("Акция — «скидка» é"))
            .isEqualTo("<b>Тема письма:</b> Акция — «скидка» é<br>")
    }

    @Test
    fun subjectLineIsEmptyWithoutSubject() {
        assertThat(MailLeadFields.subjectLine(null)).isEmpty()
        assertThat(MailLeadFields.subjectLine("  ")).isEmpty()
    }

    @Test
    fun textToHtmlEscapesAndKeepsLineBreaks() {
        assertThat(MailLeadFields.textToHtml("a < b\r\nc\rd\n\ne"))
            .isEqualTo("a &lt; b<br>c<br>d<br><br>e")
        assertThat(MailLeadFields.textToHtml(null)).isEmpty()
    }

    @Test
    fun escapeHtmlHandlesNull() {
        assertThat(MailLeadFields.escapeHtml(null)).isEmpty()
    }
}
