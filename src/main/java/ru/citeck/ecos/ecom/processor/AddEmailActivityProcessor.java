package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.commons.data.DataValue;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.commons.json.Json;
import ru.citeck.ecos.commons.utils.StringUtils;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.dto.FindRecordDTO;
import ru.citeck.ecos.ecom.processor.mail.EcomMail;
import ru.citeck.ecos.ecom.processor.mail.EcomMailAttachment;
import ru.citeck.ecos.records2.RecordConstants;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.txn.lib.TxnContext;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.content.EcosContentApi;
import ru.citeck.ecos.webapp.api.content.EcosContentData;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AddEmailActivityProcessor implements Processor {

    private static final String ACTIVITY_SOURCE_ID = AppName.EMODEL + "/activity";
    public static final String MAIL_VARIABLE = "mail";
    public static final String FIND_RECORD_VARIABLE = "findRecord";

    private final RecordsService recordsService;
    private final EcosContentApi ecosContentApi;

    public AddEmailActivityProcessor(
        @NonNull RecordsService recordsService,
        @NonNull EcosContentApi ecosContentApi
    ) {
        this.recordsService = recordsService;
        this.ecosContentApi = ecosContentApi;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        EntityRef record = EntityRef.valueOf(exchange.getIn().getBody(String.class));
        EcomMail mail = exchange.getVariable(MAIL_VARIABLE, EcomMail.class);
        AuthContext.runAsSystemJ(() ->
            TxnContext.doInTxnJ(() ->
                processImpl(record, mail, exchange)
            )
        );
    }

    private void processImpl(EntityRef record, EcomMail mail, Exchange exchange) {
        if (record.isEmpty()) {
            FindRecordDTO findRecordDTO = exchange.getVariable(FIND_RECORD_VARIABLE, FindRecordDTO.class);
            record = findRecord(findRecordDTO);

            if (record == null) {
                throw new IllegalStateException("Record with data " + findRecordDTO + " not found");
            }
        }

        try {
            Map<EntityRef, EcosContentData> createdAttachments = addAttachmentsToRecord(record, mail);
            createMailActivity(record, createdAttachments, mail);
        } catch (Exception e) {
            log.error("Failed to add mail activity with attachment to record {}", record, e);
        }
    }

    private EntityRef findRecord(FindRecordDTO findRecordDTO) {
        String searchAtt = findRecordDTO.getSearchAtt();
        if (searchAtt == null || StringUtils.isBlank(searchAtt)) {
            searchAtt = RecordConstants.ATT_DOC_NUM;
        }

        return recordsService.queryOne(
            RecordsQuery.create()
                .withSourceId(findRecordDTO.getSourceId())
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                    Predicates.eq(searchAtt, findRecordDTO.getSearchValue())
                )
                .build()
        );
    }

    private Map<EntityRef, EcosContentData> addAttachmentsToRecord(EntityRef record, EcomMail mail) {
        Map<EntityRef, EcosContentData> createdAttachments = new HashMap<>();
        for (EcomMailAttachment attachment : mail.getAttachments()) {
            DataValue docAtts = DataValue.createObj()
                .set(RecordConstants.ATT_PARENT, record)
                .set(RecordConstants.ATT_PARENT_ATT, "docs:documents");

            EntityRef docRef = attachment.readData(input ->
                    ecosContentApi.uploadFile()
                        .withEcosType("attachment")
                        .withName(attachment.getName())
                        .withAttributes(docAtts)
                        .writeContentJ(writer -> writer.writeStream(input)),
                () -> null
            );
            if (docRef == null) {
                log.warn("Attachment content is empty. " +
                    "Attachment name: {} " +
                    "Record ref {} " +
                    "mail date {}", attachment.getName(), record, mail.getDate());
                continue;
            }

            EcosContentData meta = ecosContentApi.getContent(docRef);
            if (meta == null) {
                throw new IllegalStateException("Attachment was uploaded, but getContent return null. Mail: " + mail);
            }
            log.debug("Saved document: {} - {} in record {}", attachment.getName(), docRef, record);

            createdAttachments.put(docRef, meta);
        }
        return createdAttachments;
    }

    private void createMailActivity(EntityRef record, Map<EntityRef, EcosContentData> createdAttachments, EcomMail mail) {
        StringBuilder text = new StringBuilder(mail.getContent());
        if (!createdAttachments.isEmpty()) {
            createdAttachments.forEach((docRef, meta) -> {
                text.append("<p><span>");
                Map<String, String> attachmentData = Map.of(
                    "type", "lexical-file-node",
                    "size", String.valueOf(meta.getSize()),
                    "name", meta.getName(),
                    "fileRecordId", docRef.toString()
                );
                text.append(Json.getMapper().toStringNotNull(attachmentData));
                text.append("</span></p>");
            });
        }

        ObjectData attributes = ObjectData.create()
            .set("_type", "email-activity")
            .set("activityDate", mail.getDate())
            .set("text", text)
            .set(RecordConstants.ATT_PARENT, record)
            .set(RecordConstants.ATT_PARENT_ATT, "has-ecos-activities:ecosActivities");
        EntityRef activityRef = recordsService.create(ACTIVITY_SOURCE_ID, attributes);
        log.debug("Email activity created: {} in record {}", activityRef, record);
    }
}
