package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.commons.data.DataValue;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.commons.json.Json;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.dto.MailDTO;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AddEmailActivityProcessor implements Processor {

    private static final String DEAL_SOURCE_ID = AppName.EMODEL + "/deal";
    private static final String ACTIVITY_SOURCE_ID = AppName.EMODEL + "/activity";

    private final RecordsService recordsService;
    private final EcosContentApi ecosContentApi;

    public AddEmailActivityProcessor(
        @NonNull RecordsService recordsService,
        @NonNull EcosContentApi ecosContentApi) {
        this.recordsService = recordsService;
        this.ecosContentApi = ecosContentApi;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        MailDTO mail = exchange.getIn().getBody(MailDTO.class);
        AuthContext.runAsSystemJ(() ->
            TxnContext.doInTxnJ(() ->
                processImpl(mail)
            )
        );
    }

    private void processImpl(MailDTO mail) {
        EntityRef dealRef = findDeal(mail.getDealNumber());
        if (dealRef == null) {
            throw new IllegalStateException("Deal with number " + mail.getDealNumber() + " not found");
        }
        Map<EntityRef, EcosContentData> createdAttachments = addAttachmentsToDeal(dealRef, mail);
        createMailActivity(dealRef, createdAttachments, mail);
    }

    private EntityRef findDeal(String dealNumber) {
        return recordsService.queryOne(
            RecordsQuery.create()
                .withSourceId(DEAL_SOURCE_ID)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("number", dealNumber))
                .build()
        );
    }

    private Map<EntityRef, EcosContentData> addAttachmentsToDeal(EntityRef deal, MailDTO mail) {
        Map<EntityRef, EcosContentData> createdAttachments = new HashMap<>();
        for (EcomMailAttachment attachment : mail.getAttachments()) {
            DataValue docAtts = DataValue.createObj()
                .set(RecordConstants.ATT_PARENT, deal)
                .set(RecordConstants.ATT_PARENT_ATT, "docs:documents");

            EntityRef docRef = ecosContentApi.uploadFile()
                .withEcosType("attachment")
                .withName(attachment.getName())
                .withAttributes(docAtts)
                .writeContentJ(writer -> attachment.readData((writer::writeStream)));
            EcosContentData meta = ecosContentApi.getContent(docRef);
            if (meta == null) {
                throw new IllegalStateException("Attachment was uploaded, but getContent return null. Mail: " + mail);
            }
            log.debug("Saved document: {} - {} in deal {}", attachment.getName(), docRef, deal);

            createdAttachments.put(docRef, meta);
        }
        return createdAttachments;
    }

    private void createMailActivity(EntityRef deal, Map<EntityRef, EcosContentData> createdAttachments, MailDTO mail) {
        String manager = recordsService.getAtt(deal, "manager?id").asText();
        StringBuilder text = new StringBuilder(mail.getBody());
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

        Instant activityDate = Instant.ofEpochMilli(mail.getDate().getTime());
        ObjectData attributes = ObjectData.create()
            .set("_type", "email-activity")
            .set("activityDate", activityDate)
            .set("responsible", manager)
            .set("text", text)
            .set(RecordConstants.ATT_PARENT, deal)
            .set(RecordConstants.ATT_PARENT_ATT, "has-ecos-activities:ecosActivities");
        EntityRef activityRef = recordsService.create(ACTIVITY_SOURCE_ID, attributes);
        log.debug("Email activity created: {} in deal {}", activityRef, deal);
    }
}
