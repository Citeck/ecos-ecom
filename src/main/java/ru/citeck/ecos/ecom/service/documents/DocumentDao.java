package ru.citeck.ecos.ecom.service.documents;

import com.sun.mail.util.BASE64DecoderStream;
import org.apache.camel.Exchange;
import org.apache.camel.attachment.AttachmentMessage;
import ru.citeck.ecos.commons.data.DataValue;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import javax.activation.DataHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public interface DocumentDao {

    public List<EntityRef> saveDocumentsForSDRecord(Exchange exchange, EntityRef entityRef);
}
