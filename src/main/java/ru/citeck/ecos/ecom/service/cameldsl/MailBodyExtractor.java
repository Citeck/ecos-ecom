package ru.citeck.ecos.ecom.service.cameldsl;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Handler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMultipart;
import java.util.List;

@Slf4j
public class MailBodyExtractor {

    public static final String MAIL_TEXT_ATT = "mailText";

    private static final List<String> TAGS_TO_REMOVE = List.of("script");

    @Handler
    public void extract(Exchange exchange) {
        Object mailData = exchange.getIn().getBody();
        String mailText = parseBody(mailData);
        exchange.getIn().setHeader(MAIL_TEXT_ATT, mailText);
    }

    private String parseBody(Object body) {
        String result;

        if (body instanceof String) {
            result = (String) body;
        } else if (body instanceof MimeMultipart) {
            try {
                result = getText(((MimeMultipart) body).getParent());
            } catch (Exception e) {
                log.error("Error while getting html text from mail", e);
                return null;
            }
        } else {
            log.error("Unknown body type: {}", body.getClass());
            return null;
        }

        return removeVulnerabilities(result);
    }

    private String removeVulnerabilities(String data) {
        if (data == null) {
            return null;
        }
        Document doc = Jsoup.parse(data);
        TAGS_TO_REMOVE.forEach(tagsToRemove -> doc.getElementsByTag(tagsToRemove).remove());
        doc.outputSettings().prettyPrint(false);
        return doc.body().html().replace("&nbsp;", " ");
    }

    private String getText(Part p) {

        try {
            if (p.isMimeType("text/*")) {
                return (String) p.getContent();
            }

            if (p.isMimeType("multipart/alternative")) {
                // prefer html text over plain text
                Multipart mp = (Multipart) p.getContent();
                String text = null;
                for (int i = 0; i < mp.getCount(); i++) {
                    Part bp = mp.getBodyPart(i);
                    if (bp.isMimeType("text/plain")) {
                        if (text == null) {
                            text = getText(bp);
                        }
                    } else if (bp.isMimeType("text/html")) {
                        String s = getText(bp);
                        if (s != null) {
                            return s;
                        }

                    } else {
                        return getText(bp);
                    }
                }
                return text;
            } else if (p.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) p.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    String s = getText(mp.getBodyPart(i));
                    if (s != null) {
                        return s;
                    }

                }
            }
        } catch (Exception e) {
            log.error("Error while getting text from mail", e);
            return null;
        }

        return null;
    }

}
