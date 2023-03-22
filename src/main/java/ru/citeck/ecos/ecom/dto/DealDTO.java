package ru.citeck.ecos.ecom.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.Date;
import java.util.Map;

@Data
public class DealDTO {
    private String from;
    private String fromAddress;
    private String subject;
    private String company;
    private String fio;
    private String phone;
    private String email;
    private String comment;
    private Date dateReceived;
    private String status = "new";
    private String source;
    private String siteFrom;
    private String gaClientId;
    private String ymClientId;
    private String emessage;
    private Boolean createdAutomatically;

    public Map<String, String> toMap() {
        ObjectMapper oMapper = new ObjectMapper();

        // object -> Map
        return oMapper.convertValue(this, Map.class);
    }
}
