package ru.citeck.ecos.ecom.service.deal.dto;

import lombok.Data;
import ru.citeck.ecos.records3.record.atts.schema.annotation.AttName;

import java.time.Instant;

@Data
public class DealData {
    @AttName("ym_client_id")
    private String ymClientId;
    private Instant dateReceived;
}
