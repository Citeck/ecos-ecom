package ru.citeck.ecos.ecom.service.crm.dto;

import lombok.Data;
import ru.citeck.ecos.records3.record.atts.schema.annotation.AttName;

import java.time.Instant;

@Data
public class LeadData {
    @AttName("ym_client_id")
    private String ymClientId;
    private Integer syncRequestSourceCount;
    private Instant dateReceived;
}
