package ru.citeck.ecos.ecom.service.documents;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.attachment.AttachmentMessage;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.citeck.ecos.commons.data.DataValue;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.records2.RecordConstants;
import ru.citeck.ecos.webapp.api.content.EcosContentApi;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import javax.activation.DataHandler;
import java.io.IOException;
import java.util.*;

@Service
@Slf4j
public class DocumentDaoEndpoint implements DocumentDao {

    @Autowired
    EcosContentApi ecosContentApi;

    @SneakyThrows
    public List<EntityRef> saveDocumentsForSDRecord(Exchange exchange, EntityRef entityRef) {
        List<EntityRef> docs = new ArrayList<>();
        AttachmentMessage attachmentMessage = exchange.getMessage(AttachmentMessage.class);
        Map<String, DataHandler> attachments = attachmentMessage.getAttachments();
        Set<String> docsName = attachments.keySet();
        if (CollectionUtils.isEmpty(docsName)) {
            return Collections.emptyList();
        }
        for (String name : docsName) {
            DataHandler dh = attachments.get(name);
            docs.add(AuthContext.runAsSystem(() -> ecosContentApi
                    .uploadFile().withEcosType("attachment")
                    .withName(dh.getName())
                    .withAttributes(createDataValueForDoc(entityRef))
                    .writeContent(writer -> {
                        try {
                            writer.writeStream(dh.getInputStream());
                        } catch (IOException e) {
                            log.warn("Unable to save document {}", dh.getName());
                        }
                        return null;
                    })));
            }
        return docs;
    }

    private DataValue createDataValueForDoc(EntityRef entityRef) {
        if (entityRef.isEmpty()){
            throw new IllegalArgumentException("EntityRef cannot be empty");
        }
        return DataValue.createObj().set(RecordConstants.ATT_PARENT, entityRef)
                .set(RecordConstants.ATT_PARENT_ATT, "docs:documents");
    }
}
