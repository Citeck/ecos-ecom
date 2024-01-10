package ru.citeck.ecos.ecom.service.deal.dto;

import lombok.Data;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

@Data
public class MergeInfo {
    private EntityRef mergeFrom;
    private EntityRef mergeIn;
}
