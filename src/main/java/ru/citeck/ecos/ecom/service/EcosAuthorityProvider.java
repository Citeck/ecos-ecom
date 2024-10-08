package ru.citeck.ecos.ecom.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

@Slf4j
@Service
public class EcosAuthorityProvider {

    private static final String EMODEL_APP = "emodel";
    private static final String PERSON_SK = "person";

    @Autowired
    private RecordsService recordsService;

    public String getUserByPhoneNumber(String phoneNumber) {
        //RecordRef peopleRecordRef = RecordRef.create(ALFRESCO_APP, PEOPLE_SK, phoneNumber);

        RecordsQuery query = RecordsQuery.create()
                .withSourceId(EMODEL_APP + "/" + PERSON_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("mobile", phoneNumber))
                .build();

        String result = AuthContext.runAsSystem(() -> recordsService.queryOne(query, "id").asText());

        return result;
    }

    public EntityRef getUserRefByUserName(String user) {
        //RecordRef peopleRecordRef = RecordRef.create(ALFRESCO_APP, PEOPLE_SK, phoneNumber);

        RecordsQuery query = RecordsQuery.create()
                .withSourceId(EMODEL_APP + "/" + PERSON_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("id", user))
                .build();

        EntityRef result = AuthContext.runAsSystem(() -> recordsService.queryOne(query));

        return result;
    }

    public String getUserByTelegramId(String id) {
        //RecordRef peopleRecordRef = RecordRef.create(ALFRESCO_APP, PEOPLE_SK, phoneNumber);

        RecordsQuery query = RecordsQuery.create()
                .withSourceId(EMODEL_APP + "/" + PERSON_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("telegramUserId", id))
                //.withQuery(Predicates.and(
                //        Predicates.eq("TYPE", PERSON_SK),
                //        Predicates.eq("telegramUserId", id)))
                .build();

        log.debug(query.toString());

        String result = AuthContext.runAsSystem(() -> recordsService.queryOne(query, "id").asText());
        log.debug("user by id: " + result);

        return result;
    }

    public void setUserTelegramId(String userName, String id) {
        RecordsQuery userQuery = RecordsQuery.create()
                .withSourceId(EMODEL_APP + "/" + PERSON_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(
                        Predicates.eq("id", userName))
                .build();
        log.debug(userQuery.toString());

        EntityRef userRecordRef = AuthContext.runAsSystem(() -> recordsService.queryOne(userQuery));
        log.debug(userRecordRef.toString());

        RecordAtts toMutate = new RecordAtts();
        toMutate.setId(userRecordRef);
        toMutate.setAtt("telegramUserId", id);
        String result = AuthContext.runAsSystem(() -> recordsService.mutate(toMutate)).toString();
        log.debug("mutated user recordRef: " + result);
    }

}
