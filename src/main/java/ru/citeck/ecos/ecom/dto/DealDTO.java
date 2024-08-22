package ru.citeck.ecos.ecom.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import ru.citeck.ecos.commons.data.ObjectData;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
public class DealDTO {
    private String from;
    private String fromAddress;
    private String subject;
    private String company;
    private String name;
    private String counterparty;
    private String comment;
    private Date dateReceived;
    private String status = "new";
    private String requestCategory;
    private String requestSource;
    private String siteFrom;
    private String numberOfUsers;
    private String gaClientId;
    private String ymClientId;
    private String emessage;
    private Boolean createdAutomatically;
    private List<ObjectData> contacts;

    public Map<String, String> toMap() {
        ObjectMapper oMapper = new ObjectMapper();

        // object -> Map
        return oMapper.convertValue(this, Map.class);
    }
}
