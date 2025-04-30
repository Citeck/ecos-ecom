package ru.citeck.ecos.ecom.service.crm;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.citeck.ecos.commons.data.DataValue;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.commons.utils.StringUtils;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.service.crm.dto.AttInfo;
import ru.citeck.ecos.ecom.service.crm.dto.MergeInfo;
import ru.citeck.ecos.records2.RecordConstants;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.records3.record.dao.mutate.ValueMutateDao;
import ru.citeck.ecos.records3.record.dao.query.dto.query.QueryPage;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.records3.record.dao.query.dto.res.RecsQueryRes;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ru.citeck.ecos.ecom.service.crm.LeadSyncRequestSourceJob.SYNC_REQUEST_SOURCE_COUNT_ATT;

@Slf4j
public abstract class AbstractMergeCrmRecordsDao implements ValueMutateDao<MergeInfo> {

    protected static final String DESCRIPTION_ATT = "description";
    protected static final String REQUEST_CATEGORY_ATT = "requestCategory";
    protected static final String REQUEST_SOURCE_ATT = "requestSource";
    protected static final String CONTACTS_ATT = "contacts";
    protected static final String MANAGER_ATT = "manager";
    protected static final String COUNTERPARTY_ATT = "counterparty";

    protected static final String COMMENT_SK = "comment";
    protected static final String ACTIVITY_SK = "activity";
    protected static final String ASSIGNMENT_SK = "assignment-type";

    protected static final String ORDERS_ATT = "has-orders:orders";
    protected static final String PAYMENTS_ATT = "has-payments:payments";

    public static final String ECOS_ACTIVITY_PROCESS_ID = "ecos-activity-process";
    public static final String ASSIGNMENT_PROCESS_ID = "assignment-process";

    protected static final DateTimeFormatter COMMENT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        .withZone(ZoneId.systemDefault());

    protected final RecordsService recordsService;

    protected AbstractMergeCrmRecordsDao(RecordsService recordsService) {
        this.recordsService = recordsService;
    }

    @Nullable
    @Override
    public Object mutate(@NotNull MergeInfo mergeInfo) {
        if (mergeInfo.getMergeFrom().equals(mergeInfo.getMergeIn())) {
            throw new IllegalArgumentException("Cannot merge the same record=" + mergeInfo.getMergeFrom());
        }

        if (mergeInfo.getMergeIn().isEmpty()) {
            throw new IllegalArgumentException("Cannot merge to empty record");
        }

        ObjectData mergedAtts = ObjectData.create();
        mergeCommonAttributes(mergeInfo, mergedAtts);
        mergeSpecificAttributes(mergeInfo, mergedAtts);

        RecordAtts recordAtts = new RecordAtts();
        recordAtts.setId(mergeInfo.getMergeIn());
        recordAtts.setAtts(mergedAtts);
        recordsService.mutate(recordAtts);

        AuthContext.runAsSystemJ(() -> {
            mergeComments(mergeInfo);
            mergeActivities(mergeInfo);
            mergeAssignments(mergeInfo);
        });

        addMergeResultComment(mergeInfo);
        recordsService.delete(mergeInfo.getMergeFrom());
        return null;
    }

    protected void mergeCommonAttributes(MergeInfo mergeInfo, ObjectData mergedAtts) {
        mergeAtt(mergeInfo, DESCRIPTION_ATT, mergedAtts);
        mergeAtt(mergeInfo, MANAGER_ATT + "?id", mergedAtts);
        mergeAtt(mergeInfo, COUNTERPARTY_ATT + "?id", mergedAtts);
        mergeRequestCategory(mergeInfo, mergedAtts);
        mergeRequestSource(mergeInfo, mergedAtts);
    }

    protected abstract void mergeSpecificAttributes(MergeInfo mergeInfo, ObjectData mergedAtts);

    protected abstract Pattern getCommentMergedMarkPattern();

    protected abstract String getEntityTypeDisplayName();

    protected void mergeAtt(MergeInfo mergeInfo, String att, ObjectData mergedAtts) {
        DataValue attFrom = recordsService.getAtt(mergeInfo.getMergeFrom(), att);
        DataValue attIn = recordsService.getAtt(mergeInfo.getMergeIn(), att);
        if (attIn.isEmpty() && attFrom.isNotEmpty()) {
            mergedAtts.set(att, attFrom);
        }
    }

    protected List<String> addAssoc(MergeInfo mergeInfo, String att, ObjectData mergedAtts) {
        List<String> attFrom = recordsService.getAtt(mergeInfo.getMergeFrom(), att + "[]?id").asStrList();

        if (!attFrom.isEmpty()) {
            mergedAtts.set("att_add_" + att, attFrom);
        }

        return attFrom;
    }

    protected void mergeRequestCategory(MergeInfo mergeInfo, ObjectData mergedAtts) {
        mergeAtt(mergeInfo, REQUEST_CATEGORY_ATT + "?id", mergedAtts);
    }

    protected void mergeRequestSource(MergeInfo mergeInfo, ObjectData mergedAtts) {
        mergeAtt(mergeInfo, REQUEST_SOURCE_ATT + "?id", mergedAtts);
    }

    protected void mergeComments(MergeInfo mergeInfo) {
        RecsQueryRes<EntityRef> commentsFrom = getCommentsByRecord(mergeInfo.getMergeFrom().getAsString());
        for (EntityRef comment : commentsFrom.getRecords()) {
            RecordAtts recordAtts = new RecordAtts();
            String text = recordsService.getAtt(comment, "text").asText();
            Matcher matcher = getCommentMergedMarkPattern().matcher(text);
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

    protected RecsQueryRes<EntityRef> getCommentsByRecord(String recordId) {
        RecordsQuery query = RecordsQuery.create()
            .withSourceId(AppName.EMODEL + "/" + COMMENT_SK)
            .withLanguage(PredicateService.LANGUAGE_PREDICATE)
            .withQuery(Predicates.eq("record", recordId))
            .build();
        return recordsService.query(query);
    }

    protected String addToCommentMergedMark(String text, String createdDate, String number) {
        return text + "<p><br></p><p><br></p><p><span>" +
            "Комментарий от " + createdDate + " из " + getEntityTypeDisplayName() + " " + number +
            "</span></p>";
    }

    protected void mergeActivities(MergeInfo mergeInfo) {
        String recordId = mergeInfo.getMergeFrom().getAsString();
        List<EntityRef> activities = recordsService.query(
                RecordsQuery.create()
                    .withSourceId(AppName.EMODEL + "/" + ACTIVITY_SK)
                    .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                    .withQuery(Predicates.eq(RecordConstants.ATT_PARENT, recordId))
                    .build()
            ).getRecords().stream()
            .filter(record -> !record.getLocalId().startsWith("comment$"))
            .toList();

        for (EntityRef activity : activities) {
            RecordAtts recordAtts = new RecordAtts();
            recordAtts.setId(activity);
            recordAtts.setAtt(RecordConstants.ATT_PARENT, mergeInfo.getMergeIn().getAsString());
            recordAtts.setAtt(RecordConstants.ATT_PARENT_ATT, "has-ecos-activities:ecosActivities");
            recordsService.mutate(recordAtts);
            updateMainDocumentRefProcessVariable(ECOS_ACTIVITY_PROCESS_ID, activity, mergeInfo.getMergeIn().getAsString());
        }
    }

    protected void mergeAssignments(MergeInfo mergeInfo) {
        String recordId = mergeInfo.getMergeFrom().getAsString();
        List<EntityRef> assignments = recordsService.query(
            RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + ASSIGNMENT_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("assoc:associatedWith", recordId))
                .build()
        ).getRecords();

        for (EntityRef assignment : assignments) {
            RecordAtts recordAtts = new RecordAtts();
            recordAtts.setId(assignment);
            recordAtts.setAtt("assoc:associatedWith", mergeInfo.getMergeIn().getAsString());
            recordsService.mutate(recordAtts);
            updateMainDocumentRefProcessVariable(ASSIGNMENT_PROCESS_ID, assignment, mergeInfo.getMergeIn().getAsString());
        }
    }

    protected void updateMainDocumentRefProcessVariable(
        String processDefinitionKey,
        EntityRef document,
        String newValue) {
        EntityRef processRef = recordsService.queryOne(
            RecordsQuery.create()
                .withSourceId("eproc/bpmn-proc")
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                    Predicates.and(
                        Predicates.eq("processDefinitionKey", processDefinitionKey),
                        Predicates.eq("document", document)
                    )
                )
                .withPage(
                    QueryPage.create()
                        .withMaxItems(1)
                        .build()
                ).build()
        );

        if (processRef != null) {
            EntityRef variableRef = recordsService.queryOne(
                RecordsQuery.create()
                    .withSourceId("eproc/bpmn-variable-instance")
                    .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                    .withQuery(
                        Predicates.and(
                            Predicates.eq("processInstance", processRef),
                            Predicates.eq("name", "mainDocumentRef")
                        )
                    )
                    .withPage(
                        QueryPage.create()
                            .withMaxItems(1)
                            .build()
                    ).build()
            );

            if (variableRef != null) {
                recordsService.mutate(
                    variableRef,
                    Map.of(
                        "type", "string",
                        "value", newValue
                    )
                );
            }
        }
    }

    protected void addMergeResultComment(MergeInfo mergeInfo) {
        List<AttInfo> attsInfo = recordsService.getAtt(mergeInfo.getMergeFrom(), "_type.model.attributes[]?json")
            .asList(AttInfo.class);

        StringBuilder sb = new StringBuilder();
        sb.append("<p><span>").append(getEntityTypeDisplayName()).append(" объединен(а) успешно.</span></p>");
        sb.append("<p><br></p>");

        for (AttInfo attInfo : attsInfo) {
            String id = attInfo.getId();
            if (SYNC_REQUEST_SOURCE_COUNT_ATT.equals(id)) {
                continue;
            }
            if (CONTACTS_ATT.equals(id)) {
                handleContactsInComment(sb, attInfo, mergeInfo);
                continue;
            }

            String attValue = recordsService.getAtt(mergeInfo.getMergeFrom(), id).asText();
            if (StringUtils.isNotBlank(attValue)) {
                sb.append("<p><span>");
                appendAttNames(sb, attInfo);
                sb.append(": ").append(attValue);
                sb.append("</span></p>");
            }
        }

        List<String> addedOrders = recordsService.getAtt(mergeInfo.getMergeFrom(), ORDERS_ATT + "[]").asStrList();
        List<String> addedPayments = recordsService.getAtt(mergeInfo.getMergeFrom(), PAYMENTS_ATT + "[]").asStrList();

        if (!addedOrders.isEmpty()) {
            sb.append("<p><span>Заказы: ").append(String.join(", ", addedOrders))
                .append("</span></p>");
        }
        if (!addedPayments.isEmpty()) {
            sb.append("<p><span>Платежи: ").append(String.join(", ", addedPayments))
                .append("</span></p>");
        }


        sb.append("<p><br></p>");

        RecordAtts recordAtts = new RecordAtts();
        recordAtts.setId("emodel/comment@");
        recordAtts.setAtt("record", mergeInfo.getMergeIn().getAsString());
        recordAtts.setAtt("text", sb.toString());
        recordsService.mutate(recordAtts);
    }

    protected void handleContactsInComment(StringBuilder sb, AttInfo attInfo, MergeInfo mergeInfo) {
        // Nothing by default
    }

    protected void appendAttNames(StringBuilder sb, AttInfo attInfo) {
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
}
