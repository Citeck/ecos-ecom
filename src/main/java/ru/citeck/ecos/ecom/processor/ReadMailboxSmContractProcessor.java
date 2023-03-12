package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.attachment.AttachmentMessage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.dto.SmContractResponseDto;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records2.rest.RemoteRecordsUtils;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import javax.activation.DataHandler;
import javax.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@PropertySource(ignoreResourceNotFound = true, value = "classpath:application.yml")
@Component
public class ReadMailboxSmContractProcessor implements Processor {

    private static final String MAIL_FROM = "From";
    private static final String MAIL_SUBJECT = "Subject";
    private static final String PERSON_SK = "person";

    private static final String COMMENT_PATTERN = "(?s).*=====Please enter your comment below this line=====(.+)=============================================.*";

    @Autowired
    private RecordsService recordsService;

    @Override
    public void process(Exchange exchange) throws Exception {
        if (exchange.getIn().getBody() == null) {
            // fail fast
            log.debug("Received exchange with empty body, skipping");
            return;
        }

        var message = exchange.getIn();
        var subject = decode(message.getHeader(MAIL_SUBJECT, String.class));

        var split = subject.split("#");
        if (split.length != 3) {
            log.info("Subject is not valid, skipping...\n" + subject);
            return;
        }

        var document = split[0];
        var taskName = split[1];
        var taskResolution = split[2];

        var user = getUser(message);
        if (user == null || user.isEmpty()) {
            log.info("User is not found, skipping...");
            return;
        }

        String body = message.getBody(String.class);
        var comment = getComment(body);

        var userAuthorities = AuthContext.runAsSystem(() ->
                recordsService.getAtt(user, "authorities.list[]").asList(String.class)
        );

        byte[] attachment = null;
        var attachmentName = "";
        var contentType = "";

        AttachmentMessage attachmentMessage = exchange.getMessage(AttachmentMessage.class);
        Map<String, DataHandler> attachments = attachmentMessage.getAttachments();
        if (attachments != null && attachments.size() > 0) {
            for (String name : attachments.keySet()) {
                DataHandler dh = attachments.get(name);
                // get the file name
                attachmentName = dh.getName();

                contentType = dh.getContentType();
                // get the content and convert it to byte[]
                attachment = exchange.getContext().getTypeConverter()
                        .convertTo(byte[].class, dh.getInputStream());
                break;
            }
        }

        var smResponseDto = new SmContractResponseDto(
                taskName,
                document,
                taskResolution,
                user,
                userAuthorities,
                comment,
                attachment,
                attachmentName,
                contentType
        );

        exchange.getIn().setHeader("taskResolution", taskResolution);
        exchange.getIn().setBody(smResponseDto);
    }

    private String getComment(String body) {
        var matcher = Pattern.compile(COMMENT_PATTERN).matcher(body);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private EntityRef getUser(Message message) {
        var from = decode(message.getHeader(MAIL_FROM, String.class));
        var fromAddress = StringUtils.substringBetween(from, "<", ">");
        return getUserByEmail(fromAddress);
    }

    private EntityRef getUserByEmail(String email) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + PERSON_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("email", email))
                .build();

        EntityRef result = RemoteRecordsUtils.runAsSystem(() -> recordsService.queryOne(query));

        return result;
    }


    private String decode(String value) {
        try {
            return MimeUtility.decodeText(value);
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
