package ru.citeck.ecos.ecom.service.deal;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import ru.citeck.ecos.ecom.service.deal.dto.DealData;
import ru.citeck.ecos.ecom.service.deal.exception.YMTooManyRequestException;
import ru.citeck.ecos.endpoints.lib.EcosEndpoint;
import ru.citeck.ecos.secrets.lib.secret.EcosSecret;

import java.time.LocalDate;
import java.time.ZoneOffset;

public class YandexMetrikaClient {

    private static final String ORGANIC_TRAFFIC_SOURCE_ID = "organic";
    private static final String YANDEX_MOBILE_SOURCE_ID = "yandex_mobile";
    private static final String YANDEX_SEARCH_SOURCE_ID = "yandex_search";

    private final RestTemplate restTemplate;
    private final int yearsSearchInterval;

    public YandexMetrikaClient(int yearsSearchInterval) {
        restTemplate = new RestTemplate();
        this.yearsSearchInterval = yearsSearchInterval;
    }

    public String getFirstTrafficSource(EcosEndpoint endpoint, EcosSecret credentials, DealData dealData)
            throws RuntimeException {
        String url = endpoint.getUrl();
        String authToken = credentials.getTokenData().getToken();

        LocalDate date2 = LocalDate.ofInstant(dealData.getDateReceived(), ZoneOffset.UTC);
        LocalDate date1 = date2.minusYears(yearsSearchInterval);
        String urlWithParams = getUrlWithParams(url, dealData.getYmClientId(), date1.toString(), date2.toString());

        HttpHeaders headers = getHttpHeaders(authToken);
        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(urlWithParams, HttpMethod.GET, requestEntity, JsonNode.class);
        if (HttpStatus.OK.equals(response.getStatusCode())) {
            return getFirstTrafficSourceId(response.getBody());
        } else if (HttpStatus.TOO_MANY_REQUESTS.equals(response.getStatusCode())) {
            throw new YMTooManyRequestException(response.toString());
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
                "&dimensions=ym:s:firstTrafficSource, ym:s:searchEngine, ym:s:dateTime" +
                "&sort=ym:s:dateTime" +
                "&filters=ym:s:clientID==" + ymClientId +
                "&date1=" + date1 +
                "&date2=" + date2;
    }

    private String getFirstTrafficSourceId(JsonNode body) {
        if (body != null) {
            JsonNode data = body.get("data");
            if (!data.isEmpty()) {
                JsonNode dimensions = data.get(0).get("dimensions");
                String firstTrafficSourceId = dimensions.get(0).get("id").asText();
                if (ORGANIC_TRAFFIC_SOURCE_ID.equals(firstTrafficSourceId)) {
                    firstTrafficSourceId = dimensions.get(1).get("id").asText();
                    if (YANDEX_MOBILE_SOURCE_ID.equals(firstTrafficSourceId)) {
                        firstTrafficSourceId = YANDEX_SEARCH_SOURCE_ID;
                    }
                }
                return firstTrafficSourceId;
            }
        }
        return null;
    }
}
