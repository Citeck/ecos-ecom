package ru.citeck.ecos.ecom.dto;

import java.util.HashMap;
import java.util.Map;

import static ru.citeck.ecos.ecom.processor.ProcessStates.UNAUTHORIZED;
/**
 * Contains process data in cache
 */
public class ProcessDTO {
    private int state;
    private Map<String, Object> data;
    private Boolean allDataReceived = false;

    public ProcessDTO() {
        state = UNAUTHORIZED;
        data = new HashMap<>();
    }

    public int getState() {
        return state;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setState(int state) {
        this.state = state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessDTO)) return false;

        ProcessDTO that = (ProcessDTO) o;

        if (state != that.state) return false;
        return data.equals(that.data);
    }

    @Override
    public int hashCode() {
        int result = state;
        result = 31 * result + data.hashCode();
        return result;
    }

    public Boolean getAllDataReceived() {
        return allDataReceived;
    }

    public void setAllDataReceived(Boolean allDataReceived) {
        this.allDataReceived = allDataReceived;
    }
}
