package ru.citeck.ecos.ecom.service;

import org.apache.commons.lang3.StringUtils;
import ru.citeck.ecos.records2.RecordRef;
import ru.citeck.ecos.records2.request.error.RecordsError;
import ru.citeck.ecos.records3.RecordsServiceFactory;
import ru.citeck.ecos.records3.record.request.RequestContext;

import java.util.List;
import java.util.function.Supplier;

public class Utils {

    public static boolean hasCriticalTagsInSubject(String subject, String tagsString) {
        boolean hasCriticalTag = false;
        if (StringUtils.isNotBlank(tagsString)) {
            String[] subjectWords = subject.toLowerCase().split(" ");
            String[] tags = tagsString.toLowerCase().split(",");

            for (String tag : tags) {
                for (String subjectWord : subjectWords) {
                    if (tag.trim().equals(subjectWord.trim())) {
                        hasCriticalTag = true;
                        break;
                    }
                }
                if (hasCriticalTag) {
                    break;
                }
            }
        }
        return hasCriticalTag;
    }

    public static boolean isEmptyRecordRef(RecordRef ref) {
        return RecordRef.isEmpty(ref) || StringUtils.isBlank(ref.getId());
    }

    public static <T> T queryImpl(RecordsServiceFactory recordsServiceFactory, Supplier<T> supplier) {
        return RequestContext.doWithCtxJ(recordsServiceFactory, (builder) -> {
        }, requestContext -> {
            List<RecordsError> before = requestContext.getRecordErrors();
            T inResult = supplier.get();
            List<RecordsError> after = requestContext.getRecordErrors();
            if (after.size() > before.size()) {
                RecordsError recordsError = after.get(after.size() - 1);
                throw new RuntimeException(recordsError.toString());
            }

            return inResult;
        });
    }
}
