package ru.citeck.ecos.ecom.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.ToString;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

@ToString(includeFieldNames=true)
@Data
public class MailDTO {
    private String from;
    private String fromAddress;
    private String subject;
    private String content;
    private Date date;
    //Date: Wed, 12 Oct 2022 08:17:19 +0000
    private String kind;
    private String status = "new";

    private static final SimpleDateFormat SOURCE_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z");

    public void setDate(String dateString) throws ParseException {
        this.date = SOURCE_DATE_FORMAT.parse(dateString);
    }

    public Map<String, String> toMap() {
        ObjectMapper oMapper = new ObjectMapper();

        // object -> Map
        return oMapper.convertValue(this, Map.class);
    }
}
