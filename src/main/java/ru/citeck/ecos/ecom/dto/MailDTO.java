package ru.citeck.ecos.ecom.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.commons.lang3.time.FastDateFormat;

import java.text.ParseException;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

@Data
public class MailDTO {

    public static final FastDateFormat dateFormat;

    static {
        dateFormat = FastDateFormat.getInstance("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    }

    private String from;
    private String fromAddress;
    private String subject;
    private String content;
    private Date date;
    private String kind;


    public void setDate(String dateString) throws ParseException {
        this.date = dateFormat.parse(dateString);
    }

    public Map<String, String> toMap() {
        ObjectMapper oMapper = new ObjectMapper();

        // object -> Map
        return oMapper.convertValue(this, Map.class);
    }
}
