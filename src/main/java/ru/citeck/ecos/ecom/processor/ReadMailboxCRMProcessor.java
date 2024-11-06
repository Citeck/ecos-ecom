package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.dto.MailDTO;
import ru.citeck.ecos.ecom.processor.mail.EcomMail;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private String[] dealSubjectCommunity;
    @Value("${mail.deal.subject.price}")
    private String dealSubjectPrice;
    @Value("${mail.deal.subject.cloud}")
    private String dealSubjectCloud;
    @Value("${mail.deal.subject.community-subscription}")
    private String communitySubscription;
    @Value("${mail.deal.subject.partnership-request}")
    private String partnershipRequest;

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

    private final Pattern dealNumber;

    public ReadMailboxCRMProcessor(@Value("${mail.deal.pattern.dealNumber}") final String dealNumberPattern) {
        dealNumber = Pattern.compile(dealNumberPattern);
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
        mail.setBody(ecomMail.getContent());
        mail.setContent(html2text(ecomMail.getContent()));
        mail.setFrom(ecomMail.getFrom());
        mail.setFromAddress(ecomMail.getFromAddress());
        mail.setSubject(ecomMail.getSubject());
        mail.setDate(Date.from(ecomMail.getDate()));
        mail.setAttachments(ecomMail.getAttachments());

        Matcher matcher = dealNumber.matcher(ecomMail.getSubject());
        if (matcher.find()) {
            exchange.setProperty("subject", "mail-activity");
            mail.setDealNumber(matcher.group(0));
        } else {
            exchange.setProperty("subject", "deal");
        }

        if (mail.getDealNumber() == null) {
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
                else {
                    mail.setKind(OTHER_KIND);
                    exchange.setProperty("subject", "other");
                }
            }
        }
        exchange.getIn().setBody(mail);
    }

    public static String html2text(String html) {
        return Jsoup.parse(html).wholeText();
    }
}
