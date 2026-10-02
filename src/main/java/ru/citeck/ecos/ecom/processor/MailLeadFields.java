package ru.citeck.ecos.ecom.processor;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;

/**
 * Fields of a lead that are derived from the email it was created from.
 */
public final class MailLeadFields {

    // The name is shown in the browser tab title, so it is kept short
    static final int MAX_NAME_LENGTH = 50;
    private static final String ELLIPSIS = "…";

    private MailLeadFields() {
    }

    /**
     * Name of a lead: the first non-blank candidate, cut to {@link #MAX_NAME_LENGTH} characters.
     *
     * @param candidates sources of the name in the order of priority
     */
    public static String leadName(String... candidates) {
        return shorten(StringUtils.firstNonBlank(candidates));
    }

    /**
     * Display name of the "From" header, empty when the header holds only an address.
     *
     * @param from the decoded "From" header, e.g. {@code "CFO Russia" <info@cfo-russia.ru>}
     */
    public static String senderName(String from) {
        if (StringUtils.isBlank(from)) {
            return "";
        }
        try {
            String personal = new InternetAddress(from.trim(), false).getPersonal();
            return StringUtils.trimToEmpty(personal);
        } catch (AddressException e) {
            // The header is decoded before parsing, so a name with a comma or other
            // special characters may no longer be a valid address
            if (!from.contains("<")) {
                return "";
            }
            String name = StringUtils.strip(StringUtils.substringBeforeLast(from, "<").trim(), "\"'").trim();
            // Several addresses leave another address in the name, which is not a name
            return StringUtils.containsAny(name, '<', '>', '@') ? "" : name;
        }
    }

    /**
     * First line of the lead description with the subject of the email, empty without a subject.
     */
    public static String subjectLine(String subject) {
        if (StringUtils.isBlank(subject)) {
            return "";
        }
        return "<b>Тема письма:</b> " + escapeHtml(subject.trim()) + "<br>";
    }

    /**
     * Escapes plain text to be inserted into the HTML description of a lead.
     */
    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name());
    }

    /**
     * Converts plain text to HTML of the lead description: escapes it and keeps the line breaks,
     * which the rich text editor of the description would otherwise collapse.
     */
    public static String textToHtml(String text) {
        return escapeHtml(text).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }

    private static String shorten(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_NAME_LENGTH) {
            return trimmed;
        }
        // Cut by code points, so that a character outside the BMP (e.g. an emoji) is not split
        int end = trimmed.offsetByCodePoints(0, MAX_NAME_LENGTH - 1);
        return StringUtils.stripEnd(trimmed.substring(0, end), null) + ELLIPSIS;
    }
}
