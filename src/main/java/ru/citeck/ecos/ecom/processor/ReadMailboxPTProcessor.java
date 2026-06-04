package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.ecom.processor.mail.EcomMail;
import ru.citeck.ecos.ecom.service.pt.ProjectEmailImportRequest;
import ru.citeck.ecos.ecom.service.pt.ProjectEmailImportService;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

@Slf4j
@Component
public class ReadMailboxPTProcessor implements Processor {

    private final ProjectEmailImportService importService;

    public ReadMailboxPTProcessor(ProjectEmailImportService importService) {
        this.importService = importService;
    }

    public static final String PROJECT_REF_PROPERTY = "ptProjectRef";
    public static final String IMPORT_RESULT_PROPERTY = "ptImportResult";

    public enum ImportOutcome {
        IMPORTED,
        DUPLICATE,
        NO_TARGET,
        FAILED
    }

    @Override
    public void process(Exchange exchange) {
        EcomMail mail = exchange.getIn().getBody(EcomMail.class);
        if (mail == null) {
            log.debug("Received exchange with null body, skipping");
            exchange.setProperty(IMPORT_RESULT_PROPERTY, ImportOutcome.NO_TARGET);
            return;
        }
        String messageId = header(exchange, "Message-ID");
        String inReplyTo = header(exchange, "In-Reply-To");
        String to = header(exchange, "To");
        String cc = header(exchange, "Cc");

        EntityRef explicitProject = EntityRef.EMPTY;
        Object projectProp = exchange.getProperty(PROJECT_REF_PROPERTY);
        if (projectProp instanceof EntityRef) {
            explicitProject = (EntityRef) projectProp;
        } else if (projectProp instanceof String str && !str.isEmpty()) {
            explicitProject = EntityRef.valueOf(str);
        }

        ProjectEmailImportRequest request = new ProjectEmailImportRequest(
            mail, messageId, inReplyTo, to, cc, explicitProject
        );
        ImportOutcome outcome;
        try {
            ProjectEmailImportService.ImportResult result = importService.importEmail(request);
            if (result instanceof ProjectEmailImportService.ImportResult.Imported) {
                outcome = ImportOutcome.IMPORTED;
            } else if (result instanceof ProjectEmailImportService.ImportResult.Duplicate) {
                outcome = ImportOutcome.DUPLICATE;
            } else if (result instanceof ProjectEmailImportService.ImportResult.NoTarget) {
                outcome = ImportOutcome.NO_TARGET;
            } else {
                outcome = ImportOutcome.FAILED;
            }
        } catch (Exception e) {
            log.error("Email import failed: {}", e.getMessage(), e);
            outcome = ImportOutcome.FAILED;
        }
        exchange.setProperty(IMPORT_RESULT_PROPERTY, outcome);
    }

    private static String header(Exchange exchange, String name) {
        return exchange.getIn().getHeader(name, String.class);
    }
}
