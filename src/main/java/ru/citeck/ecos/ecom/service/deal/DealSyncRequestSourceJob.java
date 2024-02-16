package ru.citeck.ecos.ecom.service.deal;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import ru.citeck.ecos.commons.task.schedule.Schedules;
import ru.citeck.ecos.commons.utils.StringUtils;
import ru.citeck.ecos.endpoints.lib.EcosEndpoint;
import ru.citeck.ecos.endpoints.lib.EcosEndpoints;
import ru.citeck.ecos.records2.RecordRef;
import ru.citeck.ecos.records2.predicate.PredicateService;
import ru.citeck.ecos.records2.predicate.model.Predicates;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.records3.record.atts.schema.annotation.AttName;
import ru.citeck.ecos.records3.record.dao.query.dto.query.RecordsQuery;
import ru.citeck.ecos.secrets.lib.secret.EcosSecret;
import ru.citeck.ecos.webapp.api.constants.AppName;
import ru.citeck.ecos.webapp.api.lock.LockContext;
import ru.citeck.ecos.webapp.api.task.scheduler.EcosTaskSchedulerApi;
import ru.citeck.ecos.webapp.lib.lock.EcosAppLockService;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static ru.citeck.ecos.ecom.processor.CreateDealProcessor.REQUEST_SOURCE_SK;

@Component
@Slf4j
public class DealSyncRequestSourceJob {

    private static final String CRON_EXPRESSION = "0 0 */6 * * *";

    private static final String YM_API_ENDPOINT_ID = "yandex-metrika-api";
    private static final String DEAL_SK = "deal";
    private static final String OTHER_REQUEST_SOURCE_TYPE = "other";
    private static final String ORGANIC_TRAFFIC_SOURCE_ID = "organic";

    private static final String YM_CLIENT_ID_ATT = "ym_client_id";
    private static final String REQUEST_SOURCE_ATT = "requestSource";

    private static final int YEARS_SEARCH_INTERVAL = 2;

    private final EcosTaskSchedulerApi ecosTaskScheduler;
    private final EcosAppLockService ecosAppLockService;
    private final RecordsService recordsService;
    private final RestTemplate restTemplate;

    @Autowired
    public DealSyncRequestSourceJob(EcosTaskSchedulerApi ecosTaskScheduler,
                                    EcosAppLockService ecosAppLockService,
                                    RecordsService recordsService) {
        this.ecosTaskScheduler = ecosTaskScheduler;
        this.ecosAppLockService = ecosAppLockService;
        this.recordsService = recordsService;
        restTemplate = new RestTemplate();
    }

    @PostConstruct
    private void init() {
        ecosTaskScheduler.scheduleJ(
                "DealSyncRequestSourceJob",
                Schedules.cron(CRON_EXPRESSION),
                () -> ecosAppLockService.doInSyncOrSkipJ("DealSyncRequestSourceJob",
                        Duration.ofSeconds(10), this::sync)
        );
    }

    private void sync(LockContext lockContext) {
        EcosEndpoint endpoint;
        try {
            endpoint = EcosEndpoints.getEndpoint(YM_API_ENDPOINT_ID);
        } catch (Exception e) {
            log.error(e.getMessage());
            return;
        }

        List<RecordRef> deals = getDeals();
        if (!deals.isEmpty()) {
            EcosSecret credentials = endpoint.getCredentials();
            String url = endpoint.getUrl();
            String authToken;
            if (credentials != null) {
                authToken = credentials.getTokenData().getToken();
            } else {
                throw new RuntimeException("Credentials must be not null");
            }

            for (RecordRef deal : deals) {
                DealData dealData = getDealData(deal);
                String requestSourceType = getFirstTrafficSource(url, authToken, dealData);
                if (StringUtils.isNotBlank(requestSourceType)) {
                    RecordRef requestSource = getRequestSourceByType(requestSourceType);
                    if (requestSource == null) {
                        requestSource = getRequestSourceByType(OTHER_REQUEST_SOURCE_TYPE);
                        log.info("requestSource with type=" + requestSourceType + " does not exist. Set \"other\"");
                    }
                    RecordAtts recordAtts = new RecordAtts();
                    recordAtts.setId(deal);
                    recordAtts.setAtt(REQUEST_SOURCE_ATT, requestSource);
                    recordsService.mutate(recordAtts);
                    log.info("Successful set requestSource=" + requestSourceType + " for deal=" + deal);
                }
            }
        }
    }

    private List<RecordRef> getDeals() {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + DEAL_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.and(
                        Predicates.empty(REQUEST_SOURCE_ATT),
                        Predicates.notEmpty(YM_CLIENT_ID_ATT)))
                .build();
        return recordsService.query(query).getRecords();
    }

    private DealData getDealData(RecordRef deal) {
        return recordsService.getAtts(deal, DealData.class);
    }

    private RecordRef getRequestSourceByType(String type) {
        RecordsQuery query = RecordsQuery.create()
                .withSourceId(AppName.EMODEL + "/" + REQUEST_SOURCE_SK)
                .withLanguage(PredicateService.LANGUAGE_PREDICATE)
                .withQuery(Predicates.eq("type", type))
                .build();
        return recordsService.queryOne(query);
    }

    private String getFirstTrafficSource(String url, String token, DealData dealData) {
        LocalDate date2 = LocalDate.ofInstant(dealData.getDateReceived().toInstant(), ZoneId.systemDefault());
        LocalDate date1 = date2.minusYears(YEARS_SEARCH_INTERVAL);
        String urlWithParams = getUrlWithParams(url, dealData.getYmClientId(), date1.toString(), date2.toString());

        HttpHeaders headers = getHttpHeaders(token);
        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(urlWithParams, HttpMethod.GET, requestEntity, JsonNode.class);
        if (HttpStatus.OK.equals(response.getStatusCode())) {
            return getFirstTrafficSourceId(response.getBody());
        }
        return null;
    }

    private HttpHeaders getHttpHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "OAuth " + token);
        return headers;
    }

    private String getUrlWithParams(String url, String ymClientId, String date1, String date2) {
        return url + "&metrics=ym:s:users" +
                "&dimensions=ym:s:firstTrafficSource, ym:s:searchEngine" +
                "&filters=ym:s:clientID==" + ymClientId +
                "&date1=" + date1 +
                "&date2=" + date2;
    }

    private String getFirstTrafficSourceId(JsonNode body) {
        if (body != null) {
            JsonNode dimensions = body.get("data").get(0).get("dimensions");
            String firstTrafficSourceId = dimensions.get(0).get("id").asText();
            if (ORGANIC_TRAFFIC_SOURCE_ID.equals(firstTrafficSourceId)) {
                firstTrafficSourceId = dimensions.get(1).get("id").asText();
            }
            return firstTrafficSourceId;
        }
        return null;
    }

    @Data
    private static class DealData {
        @AttName("ym_client_id")
        private String ymClientId;
        private Date dateReceived;
    }
}
