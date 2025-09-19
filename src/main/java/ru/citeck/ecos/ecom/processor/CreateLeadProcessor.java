package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.dto.LeadDTO;
import ru.citeck.ecos.ecom.dto.MailDTO;
import ru.citeck.ecos.ecom.service.crm.dto.ContactData;
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
public class CreateLeadProcessor implements Processor {
    private static Pattern LEAD_FROM;
    //Pattern.compile("(?m)(?<=От:).*$");
    private static Pattern LEAD_SUBJECT;
    //Pattern.compile("(?m)(?<=Тема:).*$");
    private static Pattern LEAD_COMPANY;
    //Pattern.compile("(?m)(?<=Компания:).*$");
    private static Pattern LEAD_FIO;
    //Pattern.compile("(?m)(?<=ФИО:).*$");
    private static Pattern LEAD_POSITION;
    //Pattern.compile("(?m)(?<=Должность:).*$");
    private static Pattern LEAD_DEPARTMENT;
    //Pattern.compile("(?m)(?<=Департамент:).*$");
    private static Pattern LEAD_PHONE;
    //Pattern.compile("(?m)(?<=Телефон:).*$");
    private static Pattern LEAD_EMAIL;
    //Pattern.compile("(?m)(?<=E-mail:).*$");
    private static Pattern LEAD_COMMENT;
    //Pattern.compile( "Комментарий:([\\s\\S\\n]+)Страница перехода");
    private static Pattern LEAD_SITE_FROM;
    //Pattern.compile("(?m)(?<=Количество пользователей:).*$");
    private static Pattern LEAD_NUMBER_OF_USERS;
    private static Pattern GA_CLIENT_ID;
    private static Pattern YM_CLIENT_ID;

    private static final String REQUEST_CATEGORY_SK = "deal-request-category";
    private static final String REQUEST_COUNTERPARTY_SK = "ecos-counterparty";

    public static final String REQUEST_SOURCE_SK = "deal-request-source";
    private static final String MAIL_REQUEST_SOURCE_TYPE = "mail";

    private RecordsService recordsService;

    private CreateLeadProcessor(@Value("${mail.lead.pattern.from}") final String from,
                                @Value("${mail.lead.pattern.company}") final String company,
                                @Value("${mail.lead.pattern.subject}") final String subject,
                                @Value("${mail.lead.pattern.fio}") final String fio,
                                @Value("${mail.lead.pattern.position}") final String position,
                                @Value("${mail.lead.pattern.department}") final String department,
                                @Value("${mail.lead.pattern.phone}") final String phone,
                                @Value("${mail.lead.pattern.email}") final String email,
                                @Value("${mail.lead.pattern.comment}") final String comment,
                                @Value("${mail.lead.pattern.siteFrom}") final String siteFrom,
                                @Value("${mail.lead.pattern.numberOfUsers}") final String numberOfUsers,
                                @Value("${mail.lead.pattern.gaClientId}") final String gaClientId,
                                @Value("${mail.lead.pattern.ymClientId}") final String ymClientId) {
        LEAD_FROM = Pattern.compile(from);
        LEAD_COMPANY = Pattern.compile(company);
        LEAD_SUBJECT = Pattern.compile(subject);
        LEAD_FIO = Pattern.compile(fio);
        LEAD_POSITION = Pattern.compile(position);
        LEAD_DEPARTMENT = Pattern.compile(department);
        LEAD_PHONE = Pattern.compile(phone);
        LEAD_EMAIL = Pattern.compile(email);
        LEAD_COMMENT = Pattern.compile(comment);
        LEAD_SITE_FROM = Pattern.compile(siteFrom);
        LEAD_NUMBER_OF_USERS = Pattern.compile(numberOfUsers);
        GA_CLIENT_ID = Pattern.compile(gaClientId);
        YM_CLIENT_ID = Pattern.compile(ymClientId);
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        MailDTO mail = (MailDTO) exchange.getIn().getBody();
        String content = mail.getContent();
        log.debug("mail content: " + content);

        String description = parseLead(content, LEAD_COMMENT, 1);
        description += "<br><b>Почтовое сообщение:</b><br>" + mail.getContent();

        LeadDTO leadDto = new LeadDTO();
        leadDto.setFromAddress(mail.getFromAddress());
        leadDto.setFrom(parseLead(content, LEAD_FROM, 0));
        leadDto.setSubject(parseLead(content, LEAD_SUBJECT, 0));
        leadDto.setSiteFrom(parseLead(content, LEAD_SITE_FROM, 0));
        leadDto.setNumberOfUsers(parseLead(content, LEAD_NUMBER_OF_USERS, 0));
        leadDto.setDateReceived(mail.getDate());
        leadDto.setEmessage(mail.getContent());
        leadDto.setDescription(description);
        leadDto.setGaClientId(parseLead(content, GA_CLIENT_ID, 0));
        leadDto.setYmClientId(parseLead(content, YM_CLIENT_ID, 0));

        String company = parseLead(content, LEAD_COMPANY, 0);
        EntityRef counterparty = null;
        List<ContactData> contacts = new ArrayList<>();
        if (StringUtils.isNotBlank(company)) {
            counterparty = getCounterpartyByName(company);
            if (counterparty != null) {
                leadDto.setCounterparty(counterparty.getAsString());
                leadDto.setCounterpartyText(getNameFromCounterparty(counterparty));
                contacts.addAll(getContactFromCounterparty(counterparty));
            } else {
                leadDto.setCounterpartyText(company);
            }
        }

        leadDto.setName(company);

        ContactData contact = new ContactData();
        String contactFio = parseLead(content, LEAD_FIO, 0);
        if (StringUtils.isNotBlank(contactFio)) {
            contact.setContactFio(contactFio);
        } else {
            contact.setContactFio(leadDto.getFrom());
        }

        String contactEmail = parseLead(content, LEAD_EMAIL, 0);
        if (StringUtils.isNotBlank(contactEmail)) {
            contact.setContactEmail(contactEmail);
        } else {
            contact.setContactEmail(leadDto.getFromAddress());
        }

        contact.setContactPosition(parseLead(content, LEAD_POSITION, 0));
        contact.setContactDepartment(parseLead(content, LEAD_DEPARTMENT, 0));
        contact.setContactPhone(parseLead(content, LEAD_PHONE, 0));
        boolean isContactAdded = checkAndAddContact(contacts, contact);
        if (counterparty != null && isContactAdded) {
            updateCounterpartyContacts(counterparty, contacts);
        }
        leadDto.setContacts(contacts);

        String kind = mail.getKind();
        if (StringUtils.isBlank(leadDto.getYmClientId()) && StringUtils.isBlank(leadDto.getGaClientId())) {
            kind = EMAIL_KIND;
            EntityRef requestSource = getMailRequestSource();
            leadDto.setRequestSource(requestSource.getAsString());
        }
        EntityRef requestCategory = getRequestCategoryById(kind);
        if (requestCategory != null) {
            leadDto.setRequestCategory(requestCategory.getAsString());
        }

        leadDto.setCreatedAutomatically(exchange.getProperty("subject").equals("lead"));

        log.debug("lead: " + leadDto);
        exchange.getIn().setBody(leadDto.toMap());
    }

    private String parseLead(String content, Pattern p, Integer group) {
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
