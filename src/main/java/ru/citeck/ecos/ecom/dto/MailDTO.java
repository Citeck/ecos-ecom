package ru.citeck.ecos.ecom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import ru.citeck.ecos.ecom.processor.mail.EcomMailAttachment;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
public class MailDTO {

    private String from;
    private String fromAddress;
    private String subject;
    private String content;
    private Date date;
    private String kind;
    private String leadNumber;
    private List<EcomMailAttachment> attachments;

    @JsonProperty("docs:documents")
    private List<EntityRef> documents;

    public Map<String, String> toMap() {
        ObjectMapper oMapper = new ObjectMapper();

        // object -> Map
        return oMapper.convertValue(this, Map.class);
    }
}
