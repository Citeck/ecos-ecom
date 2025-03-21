package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.dto.DealDTO;
import ru.citeck.ecos.ecom.dto.MailDTO;
import ru.citeck.ecos.ecom.service.deal.dto.ContactData;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ru.citeck.ecos.ecom.processor.ReadMailboxCRMProcessor.EMAIL_KIND;

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
    private static Pattern DEAL_POSITION;
            //Pattern.compile("(?m)(?<=Должность:).*$");
    private static Pattern DEAL_DEPARTMENT;
            //Pattern.compile("(?m)(?<=Департамент:).*$");
    private static Pattern DEAL_PHONE;
            //Pattern.compile("(?m)(?<=Телефон:).*$");
    private static Pattern DEAL_EMAIL;
            //Pattern.compile("(?m)(?<=E-mail:).*$");
    private static Pattern DEAL_COMMENT;
            //Pattern.compile( "Комментарий:([\\s\\S\\n]+)Страница перехода");
    private static Pattern DEAL_SITE_FROM;
            //Pattern.compile("(?m)(?<=Количество пользователей:).*$");
    private static Pattern DEAL_NUMBER_OF_USERS;
    private static Pattern GA_CLIENT_ID;
    private static Pattern YM_CLIENT_ID;

    private static final String CONTACT_FIO_KEY = "contactFio";
    private static final String CONTACT_POSITION_KEY = "contactPosition";
    private static final String CONTACT_DEPARTMENT_KEY = "contactDepartment";
    private static final String CONTACT_PHONE_KEY = "contactPhone";
    private static final String CONTACT_EMAIL_KEY = "contactEmail";
    private static final String CONTACT_MAIN_KEY = "contactMain";

    private static final String REQUEST_CATEGORY_SK = "deal-request-category";
    private static final String REQUEST_COUNTERPARTY_SK = "ecos-counterparty";

    public static final String REQUEST_SOURCE_SK = "deal-request-source";
    private static final String MAIL_REQUEST_SOURCE_TYPE = "mail";

    private RecordsService recordsService;

    private CreateDealProcessor(@Value("${mail.deal.pattern.from}") final String dealFrom,
                                @Value("${mail.deal.pattern.company}") final String dealCompany,
                                @Value("${mail.deal.pattern.subject}") final String dealSubject,
                                @Value("${mail.deal.pattern.fio}") final String dealFio,
                                @Value("${mail.deal.pattern.position}") final String dealPosition,
                                @Value("${mail.deal.pattern.department}") final String dealDepartment,
                                @Value("${mail.deal.pattern.phone}") final String dealPhone,
                                @Value("${mail.deal.pattern.email}") final String dealEmail,
                                @Value("${mail.deal.pattern.comment}") final String dealComment,
                                @Value("${mail.deal.pattern.siteFrom}") final String dealSiteFrom,
                                @Value("${mail.deal.pattern.numberOfUsers}") final String dealNumberOfUsers,
                                @Value("${mail.deal.pattern.gaClientId}") final String gaClientId,
                                @Value("${mail.deal.pattern.ymClientId}") final String ymClientId) {
        DEAL_FROM =  Pattern.compile(dealFrom);
        DEAL_COMPANY =  Pattern.compile(dealCompany);
        DEAL_SUBJECT =  Pattern.compile(dealSubject);
        DEAL_FIO =  Pattern.compile(dealFio);
        DEAL_POSITION = Pattern.compile(dealPosition);
        DEAL_DEPARTMENT = Pattern.compile(dealDepartment);
        DEAL_PHONE =  Pattern.compile(dealPhone);
        DEAL_EMAIL =  Pattern.compile(dealEmail);
        DEAL_COMMENT =  Pattern.compile(dealComment);
        DEAL_SITE_FROM =  Pattern.compile(dealSiteFrom);
        DEAL_NUMBER_OF_USERS = Pattern.compile(dealNumberOfUsers);
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
        deal.setComment(parseDeal(content, DEAL_COMMENT, 1));
        deal.setSiteFrom(parseDeal(content, DEAL_SITE_FROM, 0));
        deal.setNumberOfUsers(parseDeal(content, DEAL_NUMBER_OF_USERS, 0));
        deal.setDateReceived(mail.getDate());
        deal.setEmessage(mail.getContent());
        deal.setGaClientId(parseDeal(content, GA_CLIENT_ID, 0));
        deal.setYmClientId(parseDeal(content, YM_CLIENT_ID, 0));

        String company = parseDeal(content, DEAL_COMPANY, 0);
        EntityRef counterparty = null;
        List<ContactData> contacts = new ArrayList<>();
        if (StringUtils.isNotBlank(company)) {
            counterparty = getCounterpartyByName(company);
            if (counterparty != null) {
                deal.setCounterparty(counterparty.getAsString());
                deal.setCompany(getNameFromCounterparty(counterparty));
                contacts.addAll(getContactFromCounterparty(counterparty));
            } else {
                deal.setCompany(company);
            }
        }

        deal.setName(company);

        ContactData contact = new ContactData();
        String contactFio = parseDeal(content, DEAL_FIO, 0);
        if (StringUtils.isNotBlank(contactFio)) {
            contact.setContactFio(contactFio);
        } else {
            contact.setContactFio(deal.getFrom());
        }

        String contactEmail = parseDeal(content, DEAL_EMAIL, 0);
        if (StringUtils.isNotBlank(contactEmail)) {
            contact.setContactEmail(contactEmail);
        } else {
            contact.setContactEmail(deal.getFromAddress());
        }

        contact.setContactPosition(parseDeal(content, DEAL_POSITION, 0));
        contact.setContactDepartment(parseDeal(content, DEAL_DEPARTMENT, 0));
        contact.setContactPhone(parseDeal(content, DEAL_PHONE, 0));
        boolean isContactAdded = checkAndAddContact(contacts, contact);
        if (counterparty != null && isContactAdded) {
            updateCounterpartyContacts(counterparty, contacts);
        }
        deal.setContacts(contacts);

        String kind = mail.getKind();
        if (StringUtils.isBlank(deal.getYmClientId()) && StringUtils.isBlank(deal.getGaClientId())) {
            kind = EMAIL_KIND;
            EntityRef requestSource = getMailRequestSource();
            deal.setRequestSource(requestSource.getAsString());
        }
        EntityRef requestCategory = getRequestCategoryById(kind);
        if (requestCategory != null) {
            deal.setRequestCategory(requestCategory.getAsString());
        }

        if (exchange.getProperty("subject").equals("deal")) {
            deal.setCreatedAutomatically(true);
        } else {
            deal.setCreatedAutomatically(false);
        }

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

    private boolean checkAndAddContact(List<ContactData> contacts, ContactData contact) {
        if (contacts.isEmpty()) {
            contact.setContactMain(true);
            contacts.add(contact);
            return true;
        }

        boolean contactExist = contacts.stream()
                .anyMatch(c -> c.getContactFio().equals(contact.getContactFio()) &&
                        c.getContactPhone().equals(contact.getContactPhone()) &&
                        c.getContactEmail().equals(contact.getContactEmail()));
        if (!contactExist) {
            boolean hasMainContact = contacts.stream().anyMatch(ContactData::getContactMain);
            if (hasMainContact) {
                contact.setContactMain(false);
            } else {
                contact.setContactMain(true);
            }
            contacts.add(contact);
            return true;
        }
        return false;
    }

    private void updateCounterpartyContacts(EntityRef counterparty, List<ContactData> contacts) {
        RecordAtts recordAtts = new RecordAtts();
        recordAtts.setId(counterparty);
        recordAtts.setAtt("contacts", contacts);
        AuthContext.runAsSystem(() -> recordsService.mutate(recordAtts));
    }

    private EntityRef getRequestCategoryById(String id) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + REQUEST_CATEGORY_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("id", id))
                .build();

        return AuthContext.runAsSystem(() -> recordsService.queryOne(query));
    }

    private EntityRef getMailRequestSource() {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + REQUEST_SOURCE_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("id", MAIL_REQUEST_SOURCE_TYPE))
                .build();
        return AuthContext.runAsSystem(() -> recordsService.queryOne(query));
    }

    private EntityRef getCounterpartyByName(String name) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + REQUEST_COUNTERPARTY_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("fullOrganizationName", name))
                .build();

        return AuthContext.runAsSystem(() -> recordsService.queryOne(query));
    }

    private List<ContactData> getContactFromCounterparty(EntityRef counterparty) {
        List<ContactData> contacts = AuthContext.runAsSystem(() ->
            recordsService.getAtt(counterparty, "contacts[]?json").asList(ContactData.class)
        );
        if (contacts.size() == 1 && contacts.getFirst().isEmpty()) {
            return Collections.emptyList();
        }
        return contacts;
    }

    private String getNameFromCounterparty(EntityRef counterparty) {
        return AuthContext.runAsSystem(() -> recordsService.getAtt(counterparty, "fullOrganizationName").asText());
    }

    @Autowired
    public void setRecordsService(RecordsService recordsService) {
        this.recordsService = recordsService;
    }
}
