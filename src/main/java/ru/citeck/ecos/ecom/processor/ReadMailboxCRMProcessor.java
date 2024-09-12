package ru.citeck.ecos.ecom.processor;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeUtility;
import kotlin.jvm.functions.Function1;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.component.mail.MailMessage;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.commons.mime.MimeTypes;
import ru.citeck.ecos.ecom.dto.MailDTO;
import ru.citeck.ecos.ecom.processor.mail.EcomMailAttachment;
import ru.citeck.ecos.webapp.api.mime.MimeType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ReadMailboxCRMProcessor implements Processor {

    private static final String MAIL_FROM = "From";
    private static final String MAIL_SUBJECT = "Subject";
    private static final String MAIL_DATE = "Date";

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
    @Value("${mail.deal.subject.community-subscription}")
    private String communitySubscription;

    public static final String CONSULT_KIND = "consult";
    public static final String DEMONSTRATION_KIND = "demonstration";
    public static final String COMMUNITY_KIND = "community";
    public static final String PRICE_KIND = "price";
    public static final String DEMO_ACCESS_KIND = "demo-access";
    public static final String CLOUD_KIND = "cloud";
    public static final String COMMUNITY_SUBSCRIPTION_KIND = "community-subscription";
    public static final String OTHER_KIND = "other";
    public static final String EMAIL_KIND = "email";

    private static Pattern DEAL_NUMBER;

    public ReadMailboxCRMProcessor(@Value("${mail.deal.pattern.dealNumber}") final String dealNumberPattern) {
        DEAL_NUMBER = Pattern.compile(dealNumberPattern);
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
        mail.setBody(body);
        mail.setContent(html2text(body));
        String from = decode(message.getHeader(MAIL_FROM, String.class));
        mail.setFrom(from);
        mail.setFromAddress(StringUtils.substringBetween(from, "<", ">"));
        String subject = decode(message.getHeader(MAIL_SUBJECT, String.class));
        mail.setSubject(subject);
        mail.setDate(message.getHeader(MAIL_DATE, String.class));

        List<EcomMailAttachment> attachments = readAttachments(message.getBody(), new ArrayList<>());
        mail.setAttachments(attachments);

        Matcher matcher = DEAL_NUMBER.matcher(subject);
        if (matcher.find()) {
            exchange.setProperty("subject", "mail-activity");
            mail.setDealNumber(matcher.group(0));
        } else {
            exchange.setProperty("subject", "deal");
        }
        exchange.getIn().setBody(mail);

        for (String dealSubject : dealSubjectsConsult) {
            if (mail.getSubject().contains(dealSubject)) {
                mail.setKind(CONSULT_KIND);
            }
        }
        if (mail.getKind() == null) {
            for (String dealSubject : dealSubjectsDemonstration) {
                if (mail.getSubject().contains(dealSubject)) {
                    mail.setKind(DEMONSTRATION_KIND);
                }
            }
        }
        if (mail.getKind() == null && mail.getDealNumber() == null) {
            if (mail.getSubject().contains(dealSubjectCommunity))
                mail.setKind(COMMUNITY_KIND);
            else if (mail.getSubject().contains(dealSubjectPrice))
                mail.setKind(PRICE_KIND);
            else if (mail.getSubject().contains(dealSubjectDemoAccess))
                mail.setKind(DEMO_ACCESS_KIND);
            else if (mail.getSubject().contains(dealSubjectCloud))
                mail.setKind(CLOUD_KIND);
            else if (mail.getSubject().contains(communitySubscription))
                mail.setKind(COMMUNITY_SUBSCRIPTION_KIND);
            else {
                mail.setKind(OTHER_KIND);
                exchange.setProperty("subject", "other");
            }
        }
    }

    public static String html2text(String html) {
        return Jsoup.parse(html).wholeText();
    }

    public static String decode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return MimeUtility.decodeText(value);
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<EcomMailAttachment> readAttachments(Object content, List<EcomMailAttachment> attachments)
        throws MessagingException, IOException {
        if (content == null) {
            return attachments;
        }
        if (content instanceof MailMessage) {
            readAttachments(((MailMessage) content).getBody(), attachments);
            return attachments;
        }
        if (content instanceof Multipart multipartContent) {
            for (int i = 0; i < multipartContent.getCount(); i++) {
                BodyPart bodyPart = multipartContent.getBodyPart(i);
                if (Objects.equals(bodyPart.getDisposition(), Part.ATTACHMENT) || Objects.equals(bodyPart.getDisposition(), Part.INLINE)) {
                    var fileName = decodeText(bodyPart.getFileName());
                    if (ru.citeck.ecos.commons.utils.StringUtils.isBlank(fileName)) {
                        String baseName = UUID.randomUUID().toString();
                        MimeType type = MimeTypes.parseOrBin(bodyPart.getContentType());
                        String extension = type.getExtension();
                        if (ru.citeck.ecos.commons.utils.StringUtils.isBlank(extension)) {
                            extension = "bin";
                        }
                        fileName = baseName + "." + extension;
                    }
                    attachments.add(new BodyPartAttachment(bodyPart, fileName));
                }
                readAttachments(bodyPart.getContent(), attachments);
            }
        }
        return attachments;
    }

    private String decodeText(String value) throws UnsupportedEncodingException {
        if (value != null) {
            return MimeUtility.decodeText(value);
        }
        return "";
    }

    @Data
    @AllArgsConstructor
    private static class BodyPartAttachment implements EcomMailAttachment {

        private BodyPart part;
        private String fileName;

        @NotNull
        @Override
        public String getName() {
            return fileName;
        }

        @Override
        public <T> T readData(@NotNull Function1<? super InputStream, ? extends T> action) {
            try (InputStream stream = part.getInputStream()) {
                return action.invoke(stream);
            } catch (MessagingException | IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
