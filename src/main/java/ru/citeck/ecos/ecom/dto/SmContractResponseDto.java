package ru.citeck.ecos.ecom.dto;

import ru.citeck.ecos.webapp.api.entity.EntityRef;

import java.util.List;

public record SmContractResponseDto(
        String taskName,
        String record,
        String resolution,
        EntityRef user,
        List<String> userAuthorities,
        String comment,

        byte[] attachment,

        String attachmentName,

        String attachmentContentType
) {
}
