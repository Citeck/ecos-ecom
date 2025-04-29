package ru.citeck.ecos.ecom.service.crm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.commons.task.schedule.Schedules;
import ru.citeck.ecos.commons.utils.StringUtils;
import ru.citeck.ecos.ecom.service.crm.dto.LeadData;
import ru.citeck.ecos.endpoints.lib.EcosEndpoint;
import ru.citeck.ecos.endpoints.lib.EcosEndpoints;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.records3.record.dao.query.dto.query.SortBy;
import ru.citeck.ecos.secrets.lib.secret.EcosSecret;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.entity.EntityRef;
import ru.citeck.ecos.webapp.api.lock.LockContext;
import ru.citeck.ecos.webapp.api.task.scheduler.EcosTaskSchedulerApi;
import ru.citeck.ecos.webapp.lib.lock.EcosAppLockService;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;

import static ru.citeck.ecos.ecom.processor.CreateLeadProcessor.REQUEST_SOURCE_SK;

@Component
@Slf4j
public class LeadSyncRequestSourceJob {

    private static final String YM_API_ENDPOINT_ID = "yandex-metrika-api";
    private static final String LEAD_SK = "lead";
    private static final EntityRef OTHER_REQUEST_SOURCE = EntityRef.create(AppName.EMODEL, REQUEST_SOURCE_SK, "other");
    private static final EntityRef UNKNOWN_REQUEST_SOURCE = EntityRef.create(AppName.EMODEL, REQUEST_SOURCE_SK, "unknown");

    private static final String YM_CLIENT_ID_ATT = "ym_client_id";
    private static final String REQUEST_SOURCE_ATT = "requestSource";
    public static final String SYNC_REQUEST_SOURCE_COUNT_ATT = "syncRequestSourceCount";

    private static final int MAX_ITERATION = 10_000;
    private static final int MAX_SYNC_REQUEST_SOURCE_COUNT = 4;

    private final EcosTaskSchedulerApi ecosTaskScheduler;
    private final EcosAppLockService ecosAppLockService;
    private final RecordsService recordsService;
    private final YandexMetrikaClient yandexMetrikaClient;

    private final String cronSyncExpression;

    @Autowired
    public LeadSyncRequestSourceJob(EcosTaskSchedulerApi ecosTaskScheduler,
                                    EcosAppLockService ecosAppLockService,
                                    RecordsService recordsService,
                                    @Value("${ecos.lead.ym-sync.cron-expression}") String ymSyncCronExpression,
                                    @Value("${ecos.lead.ym-sync.search-interval}") Integer ymSyncYearsSearchInterval) {
        this.ecosTaskScheduler = ecosTaskScheduler;
        this.ecosAppLockService = ecosAppLockService;
        this.recordsService = recordsService;
        this.cronSyncExpression = ymSyncCronExpression;
        yandexMetrikaClient = new YandexMetrikaClient(ymSyncYearsSearchInterval);
    }

    @PostConstruct
    private void init() {
        ecosTaskScheduler.scheduleJ(
                "LeadSyncRequestSourceJob",
                Schedules.cron(cronSyncExpression),
                () -> ecosAppLockService.doInSyncOrSkipJ("LeadSyncRequestSourceJob",
                        Duration.ofSeconds(10), this::sync)
        );
    }

    private void sync(LockContext lockContext) {
        EcosEndpoint endpoint;
        EcosSecret credentials;
        try {
            endpoint = EcosEndpoints.getEndpoint(YM_API_ENDPOINT_ID);
            credentials = endpoint.getCredentials();
            if (credentials == null) {
                throw new RuntimeException("Credentials must be not null");
            }
        } catch (Exception e) {
            logException("Error in getEndpoint for " + YM_API_ENDPOINT_ID, e);
            return;
        }

        int excludedDealsCount = 0;
        List<EntityRef> deals = getLeads(excludedDealsCount);
        int iter = 0;
        while (!deals.isEmpty() && iter < MAX_ITERATION) {
            for (EntityRef deal : deals) {
                LeadData leadData = getLeadData(deal);
                String requestSourceType = null;
                try {
                    requestSourceType = yandexMetrikaClient.getFirstTrafficSource(endpoint, credentials, leadData);
                } catch (Exception e) {
                    if (e instanceof HttpClientErrorException &&
                            HttpStatus.BAD_REQUEST.equals(((HttpClientErrorException) e).getStatusCode())) {
                        logException("Error in yandexMetrikaClient process getFirstTrafficSource", e);
                    } else {
                        throw e;
                    }
                }

                if (StringUtils.isNotBlank(requestSourceType)) {
                    EntityRef requestSource = getRequestSourceById(requestSourceType);
                    if (requestSource == null) {
                        requestSource = OTHER_REQUEST_SOURCE;
                        log.info("requestSource with type=" + requestSourceType + " does not exist. Set \"other\"");
                    }
                    updateRequestSoursForDeal(deal, requestSource);
                    log.info("Successful set requestSource=" + requestSourceType + " for deal=" + deal);
                } else {
                    Integer syncRequestSourceCount = leadData.getSyncRequestSourceCount();
                    if (syncRequestSourceCount == null || syncRequestSourceCount == MAX_SYNC_REQUEST_SOURCE_COUNT) {
                        syncRequestSourceCount = 0;
                    }
                    syncRequestSourceCount++;
                    if (syncRequestSourceCount >= MAX_SYNC_REQUEST_SOURCE_COUNT) {
                        ObjectData data = ObjectData.create();
                        data.set(REQUEST_SOURCE_ATT, UNKNOWN_REQUEST_SOURCE);
                        data.set(SYNC_REQUEST_SOURCE_COUNT_ATT, syncRequestSourceCount);
                        updateLead(deal, data);
                        log.info("Sync attempt limit exceeded for deal=" + deal +
                                " Set requestSource \"unknown\"");
                    } else {
                        updateSyncRequestSourceCountForDeal(deal, syncRequestSourceCount);
                    }
                    excludedDealsCount++;
                }
            }
            deals = getLeads(excludedDealsCount);
            iter++;
        }
    }

    private List<EntityRef> getLeads(int skipCount) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + LEAD_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.and(
                        Predicates.empty(REQUEST_SOURCE_ATT),
                        Predicates.notEmpty(YM_CLIENT_ID_ATT)))
                .addSort(new SortBy("_created", true))
                .withMaxItems(100)
                .withSkipCount(skipCount)
                .build();
        return recordsService.query(query).getRecords();
    }

    private LeadData getLeadData(EntityRef deal) {
        return recordsService.getAtts(deal, LeadData.class);
    }

    private EntityRef getRequestSourceById(String id) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + REQUEST_SOURCE_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("id", id))
                .build();
        return recordsService.queryOne(query);
    }

    private void updateRequestSoursForDeal(EntityRef deal, EntityRef requestSource) {
        ObjectData data = ObjectData.create();
        data.set(REQUEST_SOURCE_ATT, requestSource);
        updateLead(deal, data);
    }

    private void updateSyncRequestSourceCountForDeal(EntityRef deal, Integer syncRequestSourceCount) {
        ObjectData data = ObjectData.create();
        data.set(SYNC_REQUEST_SOURCE_COUNT_ATT, syncRequestSourceCount);
        updateLead(deal, data);
    }

    private void updateLead(EntityRef deal, ObjectData data) {
        RecordAtts recordAtts = new RecordAtts();
        recordAtts.setId(deal);
        recordAtts.setAtts(data);
        recordsService.mutate(recordAtts);
    }

    private void logException(String message, Exception e) {
        if (log.isDebugEnabled()) {
            log.error(message, e);
        } else {
            log.error(e.getMessage());
        }
    }
}
