package ru.citeck.ecos.ecom.service;

import org.apache.commons.lang3.StringUtils;

public class Utils {

    public static boolean hasCriticalTagsInSubject(String subject, String tagsString) {

        if (StringUtils.isBlank(tagsString) || StringUtils.isBlank(subject)) {
            return false;
        }
        String[] tags = tagsString.toLowerCase().split(",");
        String lowerSubject = subject.toLowerCase();

        for (String tag : tags) {
            if (lowerSubject.contains(tag.trim())) {
                return true;
            }
        }
        return false;
    }
}
