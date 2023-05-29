package ru.citeck.ecos.ecom.processor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.dto.MailDTO;
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor;
import ru.citeck.ecos.ecom.service.documents.DocumentDao;
import ru.citeck.ecos.records2.RecordRef;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.schema.annotation.AttName;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import javax.mail.internet.MimeUtility;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

@PropertySource(ignoreResourceNotFound = true, value = "classpath:application.yml")
@Slf4j
@Component
public class ReadMailboxSDProcessor implements Processor {

    private static final String MAIL_FROM = "From";
    private static final String MAIL_SUBJECT = "Subject";
    private static final String MAIL_DATE = "Date";

    private static final String CLIENT_SK = "clients-type";
    private static final String PERSON_SK = "person";

    private static final String SD_CODE__PATTERN = "\\((SD-\\d+)\\)";

    private static final String SD_RESPONSE_MAIL_PREFIX = "Re: (SD-";

    private static final String MAIL_KIND_REPLY = "reply";

    private final static String MAIL_KIND_NEW = "new";

    @EcosConfig("app/service-desk$default-client")
    private EntityRef defaultClient = EntityRef.EMPTY;

    @Autowired
    private RecordsService recordsService;

    @Autowired
    private DocumentDao documentDao;

    @Override
    public void process(Exchange exchange) throws Exception {
        if (exchange.getIn().getBody() == null) {
            // fail fast
            log.debug("Received exchange with empty body, skipping");
            return;
        }
        Message message = exchange.getIn();

        String mailText = message.getHeader(MailBodyExtractor.MAIL_TEXT_ATT, String.class);

        MailDTO mail = new MailDTO();
        mail.setContent(mailText);

        String from = decode(message.getHeader(MAIL_FROM, String.class));
        mail.setFrom(from);

        String fromAddress = StringUtils.substringBetween(from, "<", ">");
        mail.setFromAddress(fromAddress);

        mail.setSubject(decode(message.getHeader(MAIL_SUBJECT, String.class)));
        mail.setDate(message.getHeader(MAIL_DATE, String.class));

        String fromDomain = getEmailDomain(fromAddress);
        exchange.getIn().setHeader("fromDomain", fromDomain);

        EntityRef client = getClientByEmailDomain(fromDomain);
        if (client == null || client.isEmpty()) {
            return;
        }

        EntityRef initiator = lookupUser(client, fromAddress);
        if (initiator == null || initiator.isEmpty()) {
            return;
        }

        String mailKind = getMailKind(mail.getSubject());
        mail.setKind(mailKind);

        exchange.getIn().setHeader("client", client.toString());
        exchange.getIn().setHeader("kind", mailKind);
        EntityRef sdRecord = getSDRecord(mailKind, mail.getSubject());
        if (sdRecord != null){
            mail.setDocuments(documentDao.saveDocumentsForSDRecord(exchange, sdRecord));
        }
        Map<String, String> bodyMap = mail.toMap();
        bodyMap.put("client", client.toString());
        bodyMap.put("initiator", initiator.toString());

        if (MAIL_KIND_REPLY.equals(mailKind)) {
            if (sdRecord != null) {
                bodyMap.put("record", sdRecord.toString());
                exchange.getIn().setHeader("runAsUser", initiator.getLocalId());
            }
        }

        bodyMap.put("createdAutomatically", "true");
        bodyMap.put("priority", "medium");

        exchange.getIn().setBody(bodyMap);
    }

    private EntityRef getSDRecord(String mailKind, String subject) {
        if (MAIL_KIND_REPLY.equals(mailKind)) {
            return findSdRequestByTitle(subject);
        }
        return null;
    }

    private static String decode(String value) {
        if (value == null){
            return "";
        }
        try {
            return MimeUtility.decodeText(value);
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String getEmailDomain(String fromEmail) {
        return fromEmail.substring(fromEmail.indexOf("@") + 1);
    }

    private RecordRef getClientByEmailDomain(String emailDomain) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + CLIENT_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("emailDomain", emailDomain))
                .build();

        return AuthContext.runAsSystem(() -> recordsService.queryOne(query));
    }

    private EntityRef lookupUser(EntityRef client, String email) {
        if (client.equals(defaultClient)) {
            return getUserByEmail(email);
        }

        ClientUsers clientUsers = AuthContext.runAsSystem(
                () -> recordsService.getAtts(client, ClientUsers.class)
        );
        for (UserData user : clientUsers.users) {
            if (user.getEmail().equalsIgnoreCase(email)) {
                return user.ref;
            }
        }

        return null;
    }

    public RecordRef getUserByEmail(String email) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + PERSON_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("email", email))
                .build();

        return AuthContext.runAsSystem(() -> recordsService.queryOne(query));
    }

    private String getMailKind(String title) {
        if (StringUtils.startsWith(title, SD_RESPONSE_MAIL_PREFIX)) {
            return MAIL_KIND_REPLY;
        } else {
            return MAIL_KIND_NEW;
        }
    }

    private EntityRef findSdRequestByTitle(String title) {
        var matcher = Pattern.compile(SD_CODE__PATTERN).matcher(title);
        if (matcher.find()) {
            var sdCode = matcher.group(1).trim();
            var sdNumber = Integer.parseInt(StringUtils.substringAfter(sdCode, "SD-"));

            RecordsQuery query = RecordsQuery.create()
                    .withSourceId(AppName.EMODEL + "/" + "sd-request-type")
                    .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                    .withQuery(
                            Predicates.eq("_docNum", sdNumber))
                    .build();

            return AuthContext.runAsSystem(() -> recordsService.queryOne(query));
        }

        return null;
    }



    @Data
    private static class ClientUsers {
        @AttName("users[]")
        private List<UserData> users = Collections.emptyList();
    }

    @Data
    private static class UserData {
        @AttName(".id")
        private EntityRef ref;

        @AttName("id")
        private String userName;

        private String email;
    }
}
