package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.dto.DealDTO;
import ru.citeck.ecos.ecom.dto.MailDTO;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@PropertySource(ignoreResourceNotFound = true, value = "classpath:application.yml")
@Slf4j
@Component
public class CreateDealProcessor implements Processor {
    private static Pattern DEAL_FROM;
            //Pattern.compile("(?m)(?<=От:).*$");
    private static Pattern DEAL_SUBJECT;
            //Pattern.compile("(?m)(?<=Тема:).*$");
    private static Pattern DEAL_COMPANY;
            //Pattern.compile("(?m)(?<=Компания:).*$");
    private static Pattern DEAL_FIO;
            //Pattern.compile("(?m)(?<=ФИО:).*$");
    private static Pattern DEAL_PHONE;
            //Pattern.compile("(?m)(?<=Телефон:).*$");
    private static Pattern DEAL_EMAIL;
            //Pattern.compile("(?m)(?<=E-mail:).*$");
    private static Pattern DEAL_COMMENT;
            //Pattern.compile( "Комментарий:([\\s\\S\\n]+)Страница перехода");
    private static Pattern DEAL_SITE_FROM;
    private static Pattern GA_CLIENT_ID;
    private static Pattern YM_CLIENT_ID;

    private CreateDealProcessor(@Value("${mail.deal.pattern.from}") final String dealFrom,
                                @Value("${mail.deal.pattern.company}") final String dealCompany,
                                @Value("${mail.deal.pattern.subject}") final String dealSubject,
                                @Value("${mail.deal.pattern.fio}") final String dealFio,
                                @Value("${mail.deal.pattern.phone}") final String dealPhone,
                                @Value("${mail.deal.pattern.email}") final String dealEmail,
                                @Value("${mail.deal.pattern.comment}") final String dealComment,
                                @Value("${mail.deal.pattern.siteFrom}") final String dealSiteFrom,
                                @Value("${mail.deal.pattern.gaClientId}") final String gaClientId,
                                @Value("${mail.deal.pattern.ymClientId}") final String ymClientId) {
        DEAL_FROM =  Pattern.compile(dealFrom);
        DEAL_COMPANY =  Pattern.compile(dealCompany);
        DEAL_SUBJECT =  Pattern.compile(dealSubject);
        DEAL_FIO =  Pattern.compile(dealFio);
        DEAL_PHONE =  Pattern.compile(dealPhone);
        DEAL_EMAIL =  Pattern.compile(dealEmail);
        DEAL_COMMENT =  Pattern.compile(dealComment);
        DEAL_SITE_FROM =  Pattern.compile(dealSiteFrom);
        GA_CLIENT_ID =  Pattern.compile(gaClientId);
        YM_CLIENT_ID =  Pattern.compile(ymClientId);
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        MailDTO mail = (MailDTO) exchange.getIn().getBody();
        String content = mail.getContent();
        log.debug("mail content: " + content);

        DealDTO deal = new DealDTO();
        deal.setFromAddress(mail.getFromAddress());
        deal.setFrom(parseDeal(content, DEAL_FROM, 0));
        deal.setSubject(parseDeal(content, DEAL_SUBJECT, 0));
        deal.setCompany(parseDeal(content, DEAL_COMPANY, 0));
        deal.setFio(parseDeal(content, DEAL_FIO, 0));
        deal.setPhone(parseDeal(content, DEAL_PHONE, 0));
        deal.setEmail(parseDeal(content, DEAL_EMAIL, 0));
        deal.setComment(parseDeal(content, DEAL_COMMENT, 1));
        deal.setSiteFrom(parseDeal(content, DEAL_SITE_FROM, 0));
        deal.setDateReceived(mail.getDate());
        deal.setSource(mail.getKind());
        deal.setEmessage(mail.getContent());
        deal.setGaClientId(parseDeal(content, GA_CLIENT_ID, 0));
        deal.setYmClientId(parseDeal(content, YM_CLIENT_ID, 0));

        log.debug("deal: " + deal);
        exchange.getIn().setBody(deal.toMap());
    }

    private String parseDeal(String content, Pattern p, Integer group) {
        try {
            Matcher m = p.matcher(content);
            if (m.find()) {
                return StringUtils.stripStart(m.group(group), null);
            } else return "";
        } catch (Exception e) {
            return "";
        }
    }

}
