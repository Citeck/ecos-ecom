package ru.citeck.ecos.ecom.service.deal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.commons.task.schedule.Schedules;
import ru.citeck.ecos.commons.utils.StringUtils;
import ru.citeck.ecos.ecom.service.deal.dto.DealData;
import ru.citeck.ecos.endpoints.lib.EcosEndpoint;
import ru.citeck.ecos.endpoints.lib.EcosEndpoints;
import ru.citeck.ecos.records2.RecordRef;
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
    private static final String OTHER_REQUEST_SOURCE_TYPE = "other";

    private static final String YM_CLIENT_ID_ATT = "ym_client_id";
    private static final String REQUEST_SOURCE_ATT = "requestSource";

    private final EcosTaskSchedulerApi ecosTaskScheduler;
    private final EcosAppLockService ecosAppLockService;
    private final RecordsService recordsService;
    private final YandexMetrikaClient yandexMetrikaClient;

    private final String cronSyncExpression;

    @Autowired
    public DealSyncRequestSourceJob(EcosTaskSchedulerApi ecosTaskScheduler,
                                    EcosAppLockService ecosAppLockService,
                                    RecordsService recordsService,
                                    @Value("${ecos.deal.ymSyncCronExpression}") String ymSyncCronExpression,
                                    @Value("${ecos.deal.ymSyncYearsSearchInterval}") Integer ymSyncYearsSearchInterval) {
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
        List<RecordRef> deals = getDeals(excludedDealsCount);
        while (!deals.isEmpty()) {
            for (EntityRef deal : deals) {
                DealData dealData = getDealData(deal);
                String requestSourceType = null;
                try {
                    requestSourceType = yandexMetrikaClient.getFirstTrafficSource(endpoint, credentials, dealData);
                } catch (Exception e) {
                    logException("Error in yandexMetrikaClient process getFirstTrafficSource", e);
                }

                if (StringUtils.isNotBlank(requestSourceType)) {
                    EntityRef requestSource = getRequestSourceByType(requestSourceType);
                    if (requestSource == null) {
                        requestSource = getRequestSourceByType(OTHER_REQUEST_SOURCE_TYPE);
                        log.info("requestSource with type=" + requestSourceType + " does not exist. Set \"other\"");
                    }
                    RecordAtts recordAtts = new RecordAtts();
                    recordAtts.setId(deal);
                    recordAtts.setAtt(REQUEST_SOURCE_ATT, requestSource);
                    recordsService.mutate(recordAtts);
                    log.info("Successful set requestSource=" + requestSourceType + " for deal=" + deal);
                } else {
                    excludedDealsCount++;
                }
            }
            deals = getDeals(excludedDealsCount);
        }
    }

    private List<RecordRef> getDeals(int skipCount) {
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

    private EntityRef getRequestSourceByType(String type) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + REQUEST_SOURCE_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("type", type))
                .build();
        return recordsService.queryOne(query);
    }

    private void logException(String message, Exception e) {
        if (log.isDebugEnabled()) {
            log.error(message, e);
        } else {
            log.error(e.getMessage());
        }
    }
}
