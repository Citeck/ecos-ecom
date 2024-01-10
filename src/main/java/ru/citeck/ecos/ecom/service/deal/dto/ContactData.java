package ru.citeck.ecos.ecom.service.deal.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import ru.citeck.ecos.commons.utils.StringUtils;

@Data
@EqualsAndHashCode(exclude = "contactMain")
public class ContactData {
    private String contactFio;
    private String contactPosition;
    private String contactDepartment;
    private String contactPhone;
    private String contactEmail;
    private String contactComment;
    private Boolean contactMain;

    public boolean isEmpty() {
        return StringUtils.isBlank(contactFio) &&
                StringUtils.isBlank(contactPosition) &&
                StringUtils.isBlank(contactDepartment) &&
                StringUtils.isBlank(contactPhone) &&
                StringUtils.isBlank(contactEmail) &&
                StringUtils.isBlank(contactComment);
    }
}
