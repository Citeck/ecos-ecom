package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.dto.MailDTO;
import ru.citeck.ecos.records2.RecordRef;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records2.rest.RemoteRecordsUtils;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import springfox.documentation.service.ObjectVendorExtension;

import javax.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.util.Map;

@PropertySource(ignoreResourceNotFound = true, value = "classpath:application.yml")
@Slf4j
@Component
public class ReadMailboxSDProcessor implements Processor {

    private static final String EMODEL_APP = "emodel";
    private static final String CLIENT_SK = "clients-type";
    private static final String PERSON_SK = "person";

    @Autowired
    private RecordsService recordsService;

    public RecordRef getClientByEmailDomain(String emailDomain) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(EMODEL_APP + "/" + CLIENT_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("emailDomain", emailDomain))
                .build();

        RecordRef result = RemoteRecordsUtils.runAsSystem(() -> recordsService.queryOne(query));

        return result;
    }

    public RecordRef getUserByEmail(String email) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(EMODEL_APP + "/" + PERSON_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("email", email))
                .build();

        RecordRef result = RemoteRecordsUtils.runAsSystem(() -> recordsService.queryOne(query));

        return result;
    }

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
        String fromAddress = StringUtils.substringBetween(from, "<", ">");
        mail.setFromAddress(fromAddress);
        mail.setSubject(decode(message.getHeader(MAIL_SUBJECT, String.class)));
        mail.setDate(message.getHeader(MAIL_DATE, String.class));

        String fromDomain = getEmailDomain(fromAddress);
        exchange.getIn().setHeader("fromDomain", fromDomain);
        RecordRef client = getClientByEmailDomain(fromDomain);

        if (client != null) {
            exchange.getIn().setHeader("client", client.toString());
            Map<String, String> bodyMap = mail.toMap();
            bodyMap.put("client", client.toString());

            RecordRef initiator = getUserByEmail(fromAddress);
            if (initiator != null) {
                bodyMap.put("initiator", initiator.toString());
            }

            bodyMap.put("createdAutomatically", "true");
            bodyMap.put("priority", "medium");

            exchange.getIn().setBody(bodyMap);
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

    public String getEmailDomain(String fromEmail)
    {
        return  fromEmail.substring(fromEmail.indexOf("@") + 1);
    }

    private final String MAIL_FROM = "From";
    private final String MAIL_SUBJECT = "Subject";
    private final String MAIL_DATE = "Date";
}
