package ru.citeck.ecos.ecom.service.deal;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.commons.data.DataValue;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.commons.utils.StringUtils;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.service.deal.dto.AttInfo;
import ru.citeck.ecos.ecom.service.deal.dto.ContactData;
import ru.citeck.ecos.ecom.service.deal.dto.MergeInfo;
import ru.citeck.ecos.records2.RecordRef;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.records3.record.dao.mutate.ValueMutateDao;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.records3.record.dao.query.dto.res.RecsQueryRes;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class MergeDealRecordsDao implements ValueMutateDao<MergeInfo> {

    private static final String AMOUNT_ATT = "amount";
    private static final String DESCRIPTION_ATT = "description";
    private static final String COMPANY_ATT = "company";
    private static final String COUNTERPARTY_ATT = "counterparty";
    private static final String SITE_FROM_ATT = "siteFrom";
    private static final String REQUEST_CATEGORY_ATT = "requestCategory";
    private static final String REQUEST_SOURCE_ATT = "requestSource";
    private static final String EMESSAGE_ATT = "emessage";
    private static final String GA_CLIENT_ID_ATT = "ga_client_id";
    private static final String YM_CLIENT_ID_ATT = "ym_client_id";
    private static final String CONTACTS_ATT = "contacts";

    private static final String COMMENT_SK = "comment";

    private static final Pattern COMMENT_MERGED_MARK = Pattern.compile("Комментрий от [0-9]{2}.[0-9]{2}.[0-9]{4} из сделки [0-9]+");
    private static final DateTimeFormatter COMMENT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.systemDefault());

    private final RecordsService recordsService;

    @Autowired
    public MergeDealRecordsDao(RecordsService recordsService) {
        this.recordsService = recordsService;
    }

    @NotNull
    @Override
    public String getId() {
        return "merge-deal";
    }

    @Nullable
    @Override
    public Object mutate(@NotNull MergeInfo mergeInfo) {
        ObjectData mergedAtts = ObjectData.create();
        mergeAtt(mergeInfo, AMOUNT_ATT, mergedAtts);
        mergeAtt(mergeInfo, DESCRIPTION_ATT, mergedAtts);
        mergeAtt(mergeInfo, COMPANY_ATT, mergedAtts);
        mergeAtt(mergeInfo, SITE_FROM_ATT, mergedAtts);
        mergeAtt(mergeInfo, EMESSAGE_ATT, mergedAtts);
        mergeAtt(mergeInfo, GA_CLIENT_ID_ATT, mergedAtts);
        mergeAtt(mergeInfo, YM_CLIENT_ID_ATT, mergedAtts);
        mergeCounterparty(mergeInfo, mergedAtts);
        mergeRequestCategory(mergeInfo, mergedAtts);
        mergeRequestSource(mergeInfo, mergedAtts);
        mergeContacts(mergeInfo, mergedAtts);

        RecordAtts recordAtts = new RecordAtts();
        recordAtts.setId(mergeInfo.getMergeIn());
        recordAtts.setAtts(mergedAtts);
        recordsService.mutate(recordAtts);

        AuthContext.runAsSystemJ(() -> mergeComments(mergeInfo));
        addMergeResultComment(mergeInfo);
        recordsService.delete(mergeInfo.getMergeFrom());
        return null;
    }

    private void mergeAtt(MergeInfo mergeInfo, String att, ObjectData mergedAtts) {
        DataValue attFrom = recordsService.getAtt(mergeInfo.getMergeFrom(), att);
        DataValue attIn = recordsService.getAtt(mergeInfo.getMergeIn(), att);
        if (attIn.isEmpty() && attFrom.isNotEmpty()) {
            mergedAtts.set(att, attFrom);
        }
    }

    private void mergeCounterparty(MergeInfo mergeInfo, ObjectData mergedAtts) {
        mergeAtt(mergeInfo, COUNTERPARTY_ATT + "?id", mergedAtts);
    }

    private void mergeRequestCategory(MergeInfo mergeInfo, ObjectData mergedAtts) {
        mergeAtt(mergeInfo, REQUEST_CATEGORY_ATT + "?id", mergedAtts);
    }

    private void mergeRequestSource(MergeInfo mergeInfo, ObjectData mergedAtts) {
        mergeAtt(mergeInfo, REQUEST_SOURCE_ATT + "?id", mergedAtts);
    }

    private void mergeContacts(MergeInfo mergeInfo, ObjectData mergedAtts) {
        List<ContactData> contactsFrom = getContacts(mergeInfo.getMergeFrom());
        List<ContactData> contactsIn = getContacts(mergeInfo.getMergeIn());

        if (!contactsIn.isEmpty()) {
            contactsFrom.forEach(contactData -> contactData.setContactMain(false));
        }

        Set<ContactData> mergedContacts = new HashSet<>(contactsIn);
        mergedContacts.addAll(contactsFrom);
        mergedAtts.set(CONTACTS_ATT, mergedContacts);
    }

    private void mergeComments(MergeInfo mergeInfo) {
        RecsQueryRes<RecordRef> commentsFrom = getCommentsByRecord(mergeInfo.getMergeFrom().getAsString());
        for (RecordRef comment : commentsFrom.getRecords()) {
            RecordAtts recordAtts = new RecordAtts();
            String text = recordsService.getAtt(comment, "text").asText();
            Matcher matcher = COMMENT_MERGED_MARK.matcher(text);
            if (!matcher.find()) {
                String number = recordsService.getAtt(mergeInfo.getMergeFrom(), "number").asText();
                Instant created = recordsService.getAtt(comment, "_created").getAsInstant();
                String createdDate = created != null ? COMMENT_DATE_FORMATTER.format(created) : "";
                recordAtts.setAtt("text", addToCommentMergedMark(text, createdDate, number));
            }

            recordAtts.setId(comment);
            recordAtts.setAtt("record", mergeInfo.getMergeIn().getAsString());
            recordsService.mutate(recordAtts);
        }
    }

    private RecsQueryRes<RecordRef> getCommentsByRecord(String recordId) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + COMMENT_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("record", recordId))
                .build();
        return recordsService.query(query);
    }

    private String addToCommentMergedMark(String text, String createdDate, String number) {
        return text + "<p><br></p><p><br></p><p><span>" +
                "Комментрий от " + createdDate + " из сделки " + number +
                "</span></p>";
    }

    private void addMergeResultComment(MergeInfo mergeInfo) {
        List<AttInfo> attsInfo = recordsService.getAtt(mergeInfo.getMergeFrom(), "_type.model.attributes[]?json")
                .asList(AttInfo.class);

        StringBuilder sb = new StringBuilder();
        sb.append("<p><span>Сделка объединена успешно.</span></p>");
        sb.append("<p><br></p>");
        for (AttInfo attInfo : attsInfo) {
            if (CONTACTS_ATT.equals(attInfo.getId())) {
                appendContacts(sb, attInfo, mergeInfo);
                continue;
            }

            String attValue = recordsService.getAtt(mergeInfo.getMergeFrom(), attInfo.getId()).asText();
            if (StringUtils.isNotBlank(attValue)) {
                sb.append("<p><span>");
                appendAttNames(sb, attInfo);
                sb.append(": ").append(attValue);
                sb.append("</span></p>");
            }
        }
        sb.append("<p><br></p>");

        RecordAtts recordAtts = new RecordAtts();
        recordAtts.setId("emodel/comment@");
        recordAtts.setAtt("record", mergeInfo.getMergeIn().getAsString());
        recordAtts.setAtt("text", sb.toString());
        recordsService.mutate(recordAtts);
    }

    private void appendAttNames(StringBuilder sb, AttInfo attInfo) {
        ObjectData names = attInfo.getName();
        List<String> fieldNamesList = names.fieldNamesList();
        for (int i = 0; i < fieldNamesList.size(); i++) {
            DataValue attName = names.get(fieldNamesList.get(i));
            sb.append(attName.asText());
            if (i + 1 != fieldNamesList.size()) {
                sb.append("/");
            }
        }
    }

    private void appendContacts(StringBuilder sb, AttInfo attInfo, MergeInfo mergeInfo) {
        List<ContactData> contacts = getContacts(mergeInfo.getMergeFrom());
        if (contacts.isEmpty()) {
            return;
        }

        sb.append("<p><span>");
        appendAttNames(sb, attInfo);
        sb.append(": ").append("</span></p>");
        sb.append("<p><br></p>");

        sb.append("<table><colgroup>");
        sb.append("<col>".repeat(7));
        sb.append("</colgroup>");

        sb.append("<tbody>");
        sb.append("<tr>");
        sb.append("<th><p><span>Контакт/Contact</span></p></th>");
        sb.append("<th><p><span>ФИО/Full name</span></p></th>");
        sb.append("<th><p><span>Должность/Position</span></p></th>");
        sb.append("<th><p><span>Департамент/Department</span></p></th>");
        sb.append("<th><p><span>Телефон/Phone</span></p></th>");
        sb.append("<th><p><span>E-mail</span></p></th>");
        sb.append("<th><p><span>Комментарий/Comment</span></p></th>");
        sb.append("</tr>");

        for (int i = 0; i < contacts.size(); i++) {
            ContactData contact = contacts.get(i);
            sb.append("<tr>");
            sb.append("<th><p><span>").append(i + 1).append("</span></p></th>");
            sb.append("<th><p><span>").append(contact.getContactFio()).append("</span></p></th>");
            sb.append("<th><p><span>").append(contact.getContactPosition()).append("</span></p></th>");
            sb.append("<th><p><span>").append(contact.getContactDepartment()).append("</span></p></th>");
            sb.append("<th><p><span>").append(contact.getContactPhone()).append("</span></p></th>");
            sb.append("<th><p><span>").append(contact.getContactEmail()).append("</span></p></th>");
            sb.append("<th><p><span>").append(contact.getContactComment()).append("</span></p></th>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
    }

    private List<ContactData> getContacts(EntityRef dealRef) {
        List<ContactData> contacts = recordsService.getAtt(dealRef, CONTACTS_ATT + "[]?json").asList(ContactData.class);
        if (contacts.size() == 1 && contacts.get(0).isEmpty()) {
            return Collections.emptyList();
        }
        return contacts;
    }
}

