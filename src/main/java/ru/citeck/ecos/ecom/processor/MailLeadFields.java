package ru.citeck.ecos.ecom.processor;

import org.apache.commons.lang3.StringUtils;

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
     * Name of the lead: the company or the full name from a site form, otherwise the sender.
     *
     * @param from the decoded "From" header, e.g. {@code "CFO Russia" <info@cfo-russia.ru>}
     */
    public static String leadName(String company, String formFio, String from, String fromAddress) {
        String name = StringUtils.firstNonBlank(company, formFio, senderName(from), fromAddress);
        return name == null ? "" : StringUtils.abbreviate(name.trim(), ELLIPSIS, MAX_NAME_LENGTH);
    }

    /**
     * Display name of the "From" header, empty when the header holds only an address.
     */
    static String senderName(String from) {
        if (StringUtils.isBlank(from) || !from.contains("<")) {
            return "";
        }
        String name = StringUtils.substringBefore(from, "<").trim();
        return StringUtils.strip(name, "\"'").trim();
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

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
