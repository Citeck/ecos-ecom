package ru.citeck.ecos.ecom.service.deal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.commons.task.schedule.Schedules;
import ru.citeck.ecos.commons.utils.StringUtils;
import ru.citeck.ecos.ecom.service.deal.dto.DealData;
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

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;

import static ru.citeck.ecos.ecom.processor.CreateDealProcessor.REQUEST_SOURCE_SK;

@Component
@Slf4j
public class DealSyncRequestSourceJob {

    private static final String YM_API_ENDPOINT_ID = "yandex-metrika-api";
    private static final String DEAL_SK = "deal";
    private static final EntityRef OTHER_REQUEST_SOURCE = EntityRef.create(AppName.EMODEL, REQUEST_SOURCE_SK, "other");
    private static final EntityRef UNKNOWN_REQUEST_SOURCE = EntityRef.create(AppName.EMODEL, REQUEST_SOURCE_SK, "unknown");

    private static final String YM_CLIENT_ID_ATT = "ym_client_id";
    private static final String REQUEST_SOURCE_ATT = "requestSource";
    private static final String SYNC_REQUEST_SOURCE_COUNT_ATT = "syncRequestSourceCount";

    private static final int MAX_ITERATION = 10_000;
    private static final int MAX_SYNC_REQUEST_SOURCE_COUNT = 4;

    private final EcosTaskSchedulerApi ecosTaskScheduler;
    private final EcosAppLockService ecosAppLockService;
    private final RecordsService recordsService;
    private final YandexMetrikaClient yandexMetrikaClient;

    private final String cronSyncExpression;

    @Autowired
    public DealSyncRequestSourceJob(EcosTaskSchedulerApi ecosTaskScheduler,
                                    EcosAppLockService ecosAppLockService,
                                    RecordsService recordsService,
                                    @Value("${ecos.deal.ym-sync.cron-expression}") String ymSyncCronExpression,
                                    @Value("${ecos.deal.ym-sync.search-interval}") Integer ymSyncYearsSearchInterval) {
        this.ecosTaskScheduler = ecosTaskScheduler;
        this.ecosAppLockService = ecosAppLockService;
        this.recordsService = recordsService;
        this.cronSyncExpression = ymSyncCronExpression;
        yandexMetrikaClient = new YandexMetrikaClient(ymSyncYearsSearchInterval);
    }

    @PostConstruct
    private void init() {
        ecosTaskScheduler.scheduleJ(
                "DealSyncRequestSourceJob",
                Schedules.cron(cronSyncExpression),
                () -> ecosAppLockService.doInSyncOrSkipJ("DealSyncRequestSourceJob",
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
        List<EntityRef> deals = getDeals(excludedDealsCount);
        int iter = 0;
        while (!deals.isEmpty() && iter < MAX_ITERATION) {
            for (EntityRef deal : deals) {
                DealData dealData = getDealData(deal);
                String requestSourceType = null;
                try {
                    requestSourceType = yandexMetrikaClient.getFirstTrafficSource(endpoint, credentials, dealData);
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
                    Integer syncRequestSourceCount = dealData.getSyncRequestSourceCount();
                    if (syncRequestSourceCount == null || syncRequestSourceCount == MAX_SYNC_REQUEST_SOURCE_COUNT) {
                        syncRequestSourceCount = 0;
                    }
                    syncRequestSourceCount++;
                    if (syncRequestSourceCount >= MAX_SYNC_REQUEST_SOURCE_COUNT) {
                        ObjectData data = ObjectData.create();
                        data.set(REQUEST_SOURCE_ATT, UNKNOWN_REQUEST_SOURCE);
                        data.set(SYNC_REQUEST_SOURCE_COUNT_ATT, syncRequestSourceCount);
                        updateDeal(deal, data);
                        log.info("Sync attempt limit exceeded for deal=" + deal +
                                " Set requestSource \"unknown\"");
                    } else {
                        updateSyncRequestSourceCountForDeal(deal, syncRequestSourceCount);
                    }
                    excludedDealsCount++;
                }
            }
            deals = getDeals(excludedDealsCount);
            iter++;
        }
    }

    private List<EntityRef> getDeals(int skipCount) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + DEAL_SK)
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

    private DealData getDealData(EntityRef deal) {
        return recordsService.getAtts(deal, DealData.class);
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
        updateDeal(deal, data);
    }

    private void updateSyncRequestSourceCountForDeal(EntityRef deal, Integer syncRequestSourceCount) {
        ObjectData data = ObjectData.create();
        data.set(SYNC_REQUEST_SOURCE_COUNT_ATT, syncRequestSourceCount);
        updateDeal(deal, data);
    }

    private void updateDeal(EntityRef deal, ObjectData data) {
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
