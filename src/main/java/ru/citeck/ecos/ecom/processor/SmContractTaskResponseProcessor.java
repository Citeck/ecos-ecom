package ru.citeck.ecos.ecom.processor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.commons.data.MLText;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.dto.SmContractResponseDto;
import ru.citeck.ecos.records2.RecordConstants;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.records3.record.atts.schema.annotation.AttName;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.content.EcosContentApi;

import java.util.HashMap;
import java.util.List;

@Slf4j
@Component
public class SmContractTaskResponseProcessor implements Processor {

    @Autowired
    private RecordsService recordsService;

    @Autowired
    private EcosContentApi ecosContentApi;

    @Override
    public void process(Exchange exchange) throws Exception {
        var response = exchange.getIn().getBody(SmContractResponseDto.class);

        completeTask(response);
        addComment(response);
        uploadNewContent(response);
    }

    private void completeTask(SmContractResponseDto response) {
        var task = getUserTaskToComplete(response);
        var outcome = task.getPossibleOutcomes()
                .stream()
                .filter(out -> out.getId().equals(response.resolution()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No outcome found: " + response.record()));


        var atts = new RecordAtts(task.id);
        atts.set("outcome_" + response.resolution(), true);

        var formInfo = new HashMap<String, Object>();
        formInfo.put("submitName", outcome.name);

        atts.set("_formInfo", formInfo);

        log.debug("Completing task {} with outcome {}", task.id, outcome);

        AuthContext.runAsFullJ(response.user().getLocalId(), response.userAuthorities(), () -> {
            recordsService.mutate(atts);
        });
    }

    private void addComment(SmContractResponseDto response) {
        var atts = new RecordAtts("emodel/comment@");
        atts.set("record", response.record());
        atts.set("text", response.comment());

        AuthContext.runAsFullJ(response.user().getLocalId(), response.userAuthorities(), () -> {
            recordsService.mutate(atts);
        });
    }

    private void uploadNewContent(SmContractResponseDto response) {
        var tempFile = AuthContext.runAsSystem(() -> ecosContentApi.uploadTempFile()
                .withMimeType(StringUtils.substringBefore(response.attachmentContentType(), ";"))
                .withName(response.attachmentName())
                .writeContent(writer -> {
                    writer.writeBytes(response.attachment());
                    return null;
                }));

        var atts = new RecordAtts(response.record());
        atts.set(RecordConstants.ATT_CONTENT, tempFile);

        AuthContext.runAsFullJ(response.user().getLocalId(), response.userAuthorities(), () -> {
            recordsService.mutate(atts);
        });
    }

    private TaskData getUserTaskToComplete(SmContractResponseDto dto) {
        var taskQuery = new TaskQuery();
        taskQuery.setActive(true);
        taskQuery.setDocument(dto.record());

        var query = RecordsQuery.create()
                .withSourceId(AppName.EPROC + "/" + "wftask")
                .withQuery(
                        taskQuery
                )
                .withMaxItems(1000)
                .build();

        return AuthContext.runAsFullJ(dto.user().getLocalId(), dto.userAuthorities(),
                () -> recordsService.query(query, TaskData.class)
                        .getRecords()
                        .stream()
                        .filter(task -> task.getDefinitionKey().equals(dto.taskName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No task found for record " + dto.record())));
    }

    @Data
    static class TaskQuery {
        private boolean active;
        private String document;
    }

    @Data
    static class TaskData {
        @AttName(".id")
        private String id;
        private String definitionKey;
        private List<TaskPossibleOutcomes> possibleOutcomes;

    }

    @Data
    static class TaskPossibleOutcomes {
        private String id;
        private MLText name;
    }
}
