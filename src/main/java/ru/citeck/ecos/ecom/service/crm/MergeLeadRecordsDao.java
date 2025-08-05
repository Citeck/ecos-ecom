package ru.citeck.ecos.ecom.service.crm;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.ecom.service.crm.dto.AttInfo;
import ru.citeck.ecos.ecom.service.crm.dto.ContactData;
import ru.citeck.ecos.ecom.service.crm.dto.MergeInfo;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Slf4j
public class MergeLeadRecordsDao extends AbstractMergeCrmRecordsDao {

    private static final String COUNTERPARTY_TEXT_ATT = "counterpartyText";
    private static final String REQUEST_TYPE_ATT = "requestType";
    private static final String DISQUALIFICATION_REASON_ATT = "disqualificationReason";
    private static final String CREATED_DEAL_ATT = "createdDeal";
    private static final String SITE_FROM_ATT = "siteFrom";
    private static final String GA_CLIENT_ID_ATT = "ga_client_id";
    private static final String YM_CLIENT_ID_ATT = "ym_client_id";
    private static final String EMESSAGE_ATT = "emessage";
    private static final String NUMBER_OF_USERS_ATT = "numberOfUsers";

    private static final Pattern COMMENT_MERGED_MARK = Pattern.compile("Комментарий от [0-9]{2}.[0-9]{2}.[0-9]{4} из лида [0-9]+");

    public MergeLeadRecordsDao(RecordsService recordsService) {
        super(recordsService);
    }

    @NotNull
    @Override
    public String getId() {
        return "merge-lead";
    }

    @Override
    protected void mergeSpecificAttributes(MergeInfo mergeInfo, ObjectData mergedAtts) {
        mergeAtt(mergeInfo, COUNTERPARTY_TEXT_ATT, mergedAtts);
        mergeAtt(mergeInfo, REQUEST_TYPE_ATT + "?str", mergedAtts);
        mergeAtt(mergeInfo, DISQUALIFICATION_REASON_ATT + "?str", mergedAtts);
        mergeAtt(mergeInfo, CREATED_DEAL_ATT + "?id", mergedAtts);
        mergeAtt(mergeInfo, SITE_FROM_ATT, mergedAtts);
        mergeAtt(mergeInfo, GA_CLIENT_ID_ATT, mergedAtts);
        mergeAtt(mergeInfo, YM_CLIENT_ID_ATT, mergedAtts);
        mergeAtt(mergeInfo, EMESSAGE_ATT, mergedAtts);
        mergeAtt(mergeInfo, NUMBER_OF_USERS_ATT, mergedAtts);
        mergeContacts(mergeInfo, mergedAtts);
    }

    @Override
    protected Pattern getCommentMergedMarkPattern() {
        return COMMENT_MERGED_MARK;
    }

    @Override
    protected String getEntityTypeDisplayName() {
        return "Лид";
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

    @Override
    protected void handleContactsInComment(StringBuilder sb, AttInfo attInfo, MergeInfo mergeInfo) {
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

    private List<ContactData> getContacts(EntityRef recordRef) {
        List<ContactData> contacts = recordsService.getAtt(recordRef, CONTACTS_ATT + "[]?json").asList(ContactData.class);
        if (contacts.size() == 1 && contacts.getFirst().isEmpty()) {
            return Collections.emptyList();
        }
        return contacts;
    }
}
