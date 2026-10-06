package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.dto.FindRecordDTO;
import ru.citeck.ecos.ecom.dto.MailDTO;
import ru.citeck.ecos.ecom.processor.mail.EcomMail;
import ru.citeck.ecos.webapp.api.constants.AppName;

import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ReadMailboxCRMProcessor implements Processor {

    private static final String LEAD_SOURCE_ID = AppName.EMODEL + "/lead";

    @Value("${mail.lead.subject.consult}")
    private String[] dealSubjectsConsult;
    @Value("${mail.lead.subject.demonstration}")
    private String[] dealSubjectsDemonstration;
    @Value("${mail.lead.subject.demo-access}")
    private String dealSubjectDemoAccess;
    @Value("${mail.lead.subject.community}")
    private String[] dealSubjectCommunity;
    @Value("${mail.lead.subject.price}")
    private String dealSubjectPrice;
    @Value("${mail.lead.subject.cloud}")
    private String dealSubjectCloud;
    @Value("${mail.lead.subject.community-subscription}")
    private String communitySubscription;
    @Value("${mail.lead.subject.partnership-request}")
    private String partnershipRequest;
    @Value("${mail.lead.subject.special-offer}")
    private String specialOffer;

    public static final String CONSULT_KIND = "consult";
    public static final String DEMONSTRATION_KIND = "demonstration";
    public static final String COMMUNITY_KIND = "community";
    public static final String PRICE_KIND = "price";
    public static final String DEMO_ACCESS_KIND = "demo-access";
    public static final String CLOUD_KIND = "cloud";
    public static final String COMMUNITY_SUBSCRIPTION_KIND = "community-subscription";
    public static final String OTHER_KIND = "other";
    public static final String EMAIL_KIND = "email";
    public static final String PARTNERSHIP_REQUEST_KIND = "partnership-request";
    public static final String SPECIAL_OFFER_KIND = "special-offer";

    // "Re:", "Fwd:", "Ответ:", "Пересл.:", localized ones ("AW:", "WG:", "SV:", "TR:"), numbered as in "Re[2]:",
    // and after tags added by mail servers, as in "[EXT] Re:"
    private static final Pattern REPLY_OR_FORWARD = Pattern.compile(
        "^\\s*(\\[[^\\]]*]\\s*)*(re|fw|fwd|aw|wg|sv|tr|ответ|отв|пересл)\\.?\\s*(\\[\\d+]|\\(\\d+\\))?\\s*:",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private final Pattern dealNumber;
    private final List<Pattern> trackingIds;

    public ReadMailboxCRMProcessor(@Value("${mail.lead.pattern.dealNumber}") final String dealNumberPattern,
                                   @Value("${mail.lead.pattern.gaClientId}") final String gaClientIdPattern,
                                   @Value("${mail.lead.pattern.ymClientId}") final String ymClientIdPattern) {
        dealNumber = Pattern.compile(dealNumberPattern);
        trackingIds = List.of(Pattern.compile(gaClientIdPattern), Pattern.compile(ymClientIdPattern));
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        EcomMail ecomMail = exchange.getIn().getBody(EcomMail.class);
        if (StringUtils.isBlank(ecomMail.getContent())) {
            // fail fast
            exchange.getIn().setBody("");
            log.debug("Received exchange with empty body, skipping");
            return;
        }

        MailDTO mail = new MailDTO();
        mail.setContent(html2text(ecomMail.getContent()));
        mail.setFrom(ecomMail.getFrom());
        mail.setFromAddress(ecomMail.getFromAddress());
        mail.setSubject(ecomMail.getSubject());
        mail.setDate(Date.from(ecomMail.getDate()));
        mail.setAttachments(ecomMail.getAttachments());

        Matcher matcher = dealNumber.matcher(ecomMail.getSubject());
        if (matcher.find()) {
            exchange.setProperty("subject", "mail-activity");
            String leadNumber = matcher.group(0);
            mail.setLeadNumber(leadNumber);
            FindRecordDTO findRecordDTO = new FindRecordDTO(
                LEAD_SOURCE_ID,
                leadNumber,
                "number"
            );
            exchange.setVariable(AddEmailActivityProcessor.FIND_RECORD_VARIABLE, findRecordDTO);
        } else {
            exchange.setProperty("subject", "lead");
        }

        if (mail.getLeadNumber() == null && isReplyOrForward(mail.getSubject())) {
            // A reply or a forward quotes other emails, so it is not parsed as a site form even with a form subject
            mail.setKind(OTHER_KIND);
            exchange.setProperty("subject", "other");
        } else if (mail.getLeadNumber() == null) {
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
            if (mail.getKind() == null) {
                for (String dealSubject : dealSubjectCommunity) {
                    if (mail.getSubject().contains(dealSubject)) {
                        mail.setKind(COMMUNITY_KIND);
                    }
                }
            }
            if (mail.getKind() == null) {
                if (mail.getSubject().contains(dealSubjectPrice))
                    mail.setKind(PRICE_KIND);
                else if (mail.getSubject().contains(dealSubjectDemoAccess))
                    mail.setKind(DEMO_ACCESS_KIND);
                else if (mail.getSubject().contains(dealSubjectCloud))
                    mail.setKind(CLOUD_KIND);
                else if (mail.getSubject().contains(communitySubscription))
                    mail.setKind(COMMUNITY_SUBSCRIPTION_KIND);
                else if (mail.getSubject().contains(partnershipRequest))
                    mail.setKind(PARTNERSHIP_REQUEST_KIND);
                else if (mail.getSubject().contains(specialOffer)) {
                    mail.setKind(SPECIAL_OFFER_KIND);
                } else {
                    mail.setKind(OTHER_KIND);
                    // Only the site adds the tracking ids, so a form whose subject is not in the config yet
                    // is still parsed as a form
                    exchange.setProperty("subject", hasTrackingId(mail.getContent()) ? "lead" : "other");
                }
            }
        }
        exchange.getIn().setBody(mail);
    }

    private boolean hasTrackingId(String content) {
        return trackingIds.stream().anyMatch(pattern -> {
            Matcher matcher = pattern.matcher(content);
            return matcher.find() && StringUtils.isNotBlank(matcher.group());
        });
    }

    static boolean isReplyOrForward(String subject) {
        return subject != null && REPLY_OR_FORWARD.matcher(subject).find();
    }

    public static String html2text(String html) {
        return Jsoup.parse(html).wholeText();
    }
}
