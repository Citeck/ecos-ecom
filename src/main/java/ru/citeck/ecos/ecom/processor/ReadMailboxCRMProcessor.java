package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.dto.MailDTO;

import javax.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;

@PropertySource(ignoreResourceNotFound = true, value = "classpath:application.yml")
@Slf4j
@Component
public class ReadMailboxCRMProcessor implements Processor {

    @Value("${mail.deal.subject.consult}")
    private String[] dealSubjectsConsult;
    @Value("${mail.deal.subject.demonstration}")
    private String[] dealSubjectsDemonstration;
    @Value("${mail.deal.subject.demo-access}")
    private String dealSubjectDemoAccess;
    @Value("${mail.deal.subject.community}")
    private String dealSubjectCommunity;
    @Value("${mail.deal.subject.price}")
    private String dealSubjectPrice;
    @Value("${mail.deal.subject.cloud}")
    private String dealSubjectCloud;

    @Override
    public void process(Exchange exchange) throws Exception {
        if (exchange.getIn().getBody() == null) {
            // fail fast
            log.debug("Received exchange with empty body, skipping");
            return;
        }
        Message message = exchange.getIn();
        String body = message.getBody(String.class);

        MailDTO mail = new MailDTO();
        mail.setContent(html2text(body));
        String from = decode(message.getHeader(MAIL_FROM, String.class));
        mail.setFrom(from);
        mail.setFromAddress(StringUtils.substringBetween(from, "<", ">"));
        mail.setSubject(decode(message.getHeader(MAIL_SUBJECT, String.class)));
        mail.setDate(message.getHeader(MAIL_DATE, String.class));

        exchange.setProperty("subject", "deal");
        exchange.getIn().setBody(mail);

        for (String dealSubject : dealSubjectsConsult) {
            if (mail.getSubject().contains(dealSubject)) {
                mail.setKind("consult");
            }
        }
        if (mail.getKind() == null) {
            for (String dealSubject : dealSubjectsDemonstration) {
                if (mail.getSubject().contains(dealSubject)) {
                    mail.setKind("demonstration");
                }
            }
        }
        if (mail.getKind() == null) {
            if (mail.getSubject().contains(dealSubjectCommunity))
                mail.setKind("community");
            else if (mail.getSubject().contains(dealSubjectPrice))
                mail.setKind("price");
            else if (mail.getSubject().contains(dealSubjectDemoAccess))
                mail.setKind("demo-access");
            else if (mail.getSubject().contains(dealSubjectCloud))
                mail.setKind("cloud");
            else {
                mail.setKind("other");
                exchange.setProperty("subject", "other");
                //exchange.getIn().setBody(mail.toMap());
            }
        }
    }

    public static String html2text(String html) {
        return Jsoup.parse(html).wholeText();
    }

    public static String decode(String value) {
        try {
            return MimeUtility.decodeText(value);
        }
        catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private final String MAIL_FROM = "From";
    private final String MAIL_SUBJECT = "Subject";
    private final String MAIL_DATE = "Date";
}
