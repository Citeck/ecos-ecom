package ru.citeck.ecos.ecom.service.cameldsl;

import com.google.common.base.Splitter;
import ecos.com.fasterxml.jackson210.core.JsonProcessingException;
import ecos.com.fasterxml.jackson210.core.type.TypeReference;
import ecos.com.fasterxml.jackson210.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Handler;
import org.apache.commons.lang3.StringUtils;
import org.apache.xerces.dom.DeferredElementNSImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.citeck.ecos.commons.data.DataValue;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.context.lib.auth.AuthContext;
import ru.citeck.ecos.ecom.service.documents.DocumentDao;
import ru.citeck.ecos.records2.RecordRef;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.webapp.api.content.EcosContentApi;
import ru.citeck.ecos.webapp.api.entity.EntityRef;
import ru.citeck.ecos.webapp.api.properties.EcosWebAppProps;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents Camel-Endpoint.
 * Gets data from the exchange body and calls record mutation.
 * Possible input body formats: record from data source, xml node, csv-string, map-string
 */
@Slf4j
@Data
public class RecordsDaoEndpoint {
    /**
     * Represents any value of property in transformation map
     */
    private static final String ANY_VALUE = "*";

    private EcosContentApi ecosContentApi;
    private RecordsService recordsService;
    private DocumentDao documentDao;
    private Environment environment;

    private EcosWebAppProps ecosWebAppProps;

    @Value("${server.port}")
    int port;

    /**
     * Source ID of target RecordDao
     */
    private String sourceId;
    /**
     * AppName of target RecordDao
     */
    private String appName;
    /**
     * Name of source attribute/property wich represents primary key
     */
    private String pkProp;
    /**
     * Map of match between source and target property names
     * <p><i>Example:</i>
     * <code><p>
     * sourcePropName1: targetPropName1<br/>
     * sourcePropName2: targetPropName2<br/>
     * ...<br/>
     * name: content<br/>
     * documentState: state<br/>
     * </code>
     */
    private Map<String, String> columnMap;
    /**
     * Transformation map of source attribute/property values.
     * Source value change due to map before placing to target attribute/property.
     * <p>For now it sets through {@link #setValueConvertMap(String)}
     */
    private Map<String, Map<String, String>> transformMap;
    private String valueConvertMap;
    /**
     * Delimiter between values in a CSV-file line or between key-value pairs in a Map-string
     */
    private String delimiter = ",";
    /**
     * Key-value separator for Map-string
     * <p><i>Example of Map-string:</i>
     * <code><p>id=541, type=txt, title=Code values</code>
     * where '<b>=</b>' is keyValueSeparator and '<b>,</b>' is {@link #delimiter}
     */
    private String keyValueSeparator = "=";
    private Iterable<String> propTitleList;

    public RecordsDaoEndpoint() {
    }

    @Handler
    public void mutate(Exchange exchange, String appName, String sourceId, String runAsUser) {
        if (sourceId == null) {
            throw new IllegalArgumentException(
                    "Target data source ID was not defined \n"
                            + "(See configuration yml-file at 'beans'-section properties.sourceId)");
        }
        this.setColumnMap(exchange.getMessage().getHeader("recordsDaoColumnMap", HashMap.class));
        Object body = exchange.getMessage().getBody();
        Integer splitIdx = (Integer) Optional.ofNullable(exchange.getProperty(ExchangePropertyKey.SPLIT_INDEX)).orElse(0);
        if (splitIdx == 0 && body instanceof String) {
            String titles = (String) body;
            propTitleList = Splitter.on(delimiter).omitEmptyStrings().split(titles);
        }
        Map<String, Map<String, String>> tmpValueConvertMap = transformMap;

        final Map<String, String> propsMap = getPropMap(exchange.getMessage().getBody(), splitIdx);
        if (propsMap == null) {
            log.debug("Read the first data string '{}'", body);
            return;
        }

        String idValue = null;
        if (StringUtils.isNotBlank(pkProp)) {
            Object pkValue = propsMap.get(pkProp);
            if (pkValue != null) {
                idValue = String.valueOf(pkValue);
            }
        }
        ObjectData targetAttributesData = ObjectData.create();
        targetAttributesData.set("id", idValue);
        columnMap.forEach((srcColumn, targetColumn) -> {
                    Object value = propsMap.get(srcColumn);
                    if (tmpValueConvertMap != null && tmpValueConvertMap.containsKey(srcColumn)) {
                        Map<String, String> valueMap = tmpValueConvertMap.get(srcColumn);
                        String replacementValue = valueMap.get(String.valueOf(value));
                        if (StringUtils.isNotBlank(replacementValue)) {
                            value = replacementValue;
                        } else {
                            String anyValue = valueMap.get(ANY_VALUE);
                            if (StringUtils.isNotBlank(anyValue)) {
                                value = anyValue;
                            }
                        }
                    }
                    targetAttributesData.set(targetColumn, value);
                }
        );

        RecordRef ref = RecordRef.create(appName, sourceId, "");
        RecordAtts recordAtts = new RecordAtts(ref, targetAttributesData);
        log.debug("Record atts to mutate {}, as user {}", recordAtts, runAsUser);

        mutateSafeAsUserOrSystem(recordAtts, runAsUser, exchange);
    }

    private void mutateSafeAsUserOrSystem(RecordAtts recordAtts, String runAsUser, Exchange exchange) {
        try {
            AtomicReference<RecordRef> resultRef = new AtomicReference<>(RecordRef.EMPTY);

            if (StringUtils.isBlank(runAsUser)) {
                AuthContext.runAsSystemJ(() -> resultRef.set(recordsService.mutate(recordAtts)));
            } else {
                var userAuthorities = AuthContext.runAsSystem(() ->
                        recordsService.getAtt(runAsUser, "authorities.list[]").asList(String.class)
                );

                AuthContext.runAsFullJ(runAsUser, userAuthorities, () -> resultRef.set(
                        recordsService.mutate(recordAtts)
                ));
            }

            log.debug("Mutated {}", resultRef.get());
            List<EntityRef> savedDocuments = documentDao.saveDocumentsForSDRecord(exchange, resultRef.get());
            if (!savedDocuments.isEmpty()){
                savedDocuments.forEach(entityRef -> log.debug("Saved document {}", entityRef));
            }
            if (resultRef.get().getSourceId().contains("sd-request-type")){
                addDocsLinksForExistingRecord(savedDocuments, resultRef.get(), runAsUser);
            }
        } catch (Exception e) {
            log.error("Failed to mutate record {}", recordAtts, e);
        }
    }

    private void addDocsLinksForExistingRecord(List<EntityRef> savedDocuments, RecordRef ref, String runAsUser){
        var links = savedDocuments.stream().map(unit -> ecosContentApi.getDownloadUrl(unit))
                .map(unit -> getHost() + unit).toList();
            String allLinks = StringUtils.join(links, ", ");

        if (StringUtils.isBlank(runAsUser)) {
            AtomicReference<DataValue> d = new AtomicReference<>();
            AuthContext.runAsSystemJ(() -> d.set(recordsService.getAtt(ref, "letterContent")));
            AuthContext.runAsSystemJ(() -> recordsService.mutateAtt(ref, "letterContent", d.get() + " " + allLinks));
        } else {
            var userAuthorities = AuthContext.runAsSystem(() ->
                    recordsService.getAtt(runAsUser, "authorities.list[]").asList(String.class)
            );
            AtomicReference<DataValue> d = new AtomicReference<>();
            AuthContext.runAsFullJ(runAsUser, userAuthorities, () -> d.set(recordsService.getAtt(ref, "letterContent")));
            AuthContext.runAsFullJ(runAsUser, userAuthorities, () -> recordsService.mutateAtt(ref, "letterContent",
                    d.get() + " " + allLinks));
        }
    }

    /**
     * Parse the body of exchange message
     *
     * @param body  exchange message line expected
     * @param index line index in splitted message
     * @return map of property name-value pairs
     */
    private Map<String, String> getPropMap(Object body, int index) {
        if (body instanceof Map) {
            //Result of query to DataSource is LinkedHashMap
            log.trace("Convert body to Map<String, String>");
            return (Map<String, String>) body;
        } else {
            if (body instanceof DeferredElementNSImpl) {
                log.trace("Convert body to Xml");
                Map<String, String> map = new HashMap<>();
                DeferredElementNSImpl xmlNode = (DeferredElementNSImpl) body;
                for (int idx = 0; idx < xmlNode.getAttributes().getLength(); idx++) {
                    Node node = xmlNode.getAttributes().item(idx);
                    map.put(node.getLocalName(), node.getTextContent());
                }
                NodeList nodes = xmlNode.getChildNodes();
                for (int idx = 0; idx < nodes.getLength(); idx++) {
                    Node node = nodes.item(idx);
                    if (node.getLocalName() != null) {
                        map.put(node.getLocalName(), node.getTextContent());
                    }
                }
                return map;
            } else if (body instanceof String) {
                try {
                    log.trace("Convert body to Map");
                    return Splitter.on(delimiter).trimResults().omitEmptyStrings()
                            .withKeyValueSeparator(keyValueSeparator)
                            .split((String) body);
                } catch (IllegalArgumentException e) {
                    log.trace("Failed conversion body to Map");
                    if (index == 0) {
                        return null;
                    }
                    log.trace("Convert body to List");
                    try {
                        Iterable<String> values = Splitter.on(delimiter).omitEmptyStrings().split((String) body);
                        Map<String, String> map = new HashMap<>();
                        Iterator<String> titleIterator = propTitleList.iterator();
                        Iterator<String> valueIterator = values.iterator();
                        while (titleIterator.hasNext() && valueIterator.hasNext()) {
                            map.put(titleIterator.next(), valueIterator.next());
                        }
                        return map;
                    } catch (IllegalArgumentException exception) {
                        log.trace("Failed conversion body to List");
                    }
                }
            }
        }
        throw new IllegalArgumentException("Unsupported body format " + body.getClass());
    }

    /**
     * Set convertation map from a string
     * Map represents how to change the source value of the property before
     * setting it to a target attribute
     *
     * @param valueConvertMap JSON-map of transformation
     *                        {"type": {"*": "YAML"}, "state": {"1":"STARTED", "*": "STOPPED"}}
     */
    public void setValueConvertMap(String valueConvertMap) {
        if (StringUtils.isNotBlank(valueConvertMap)) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                transformMap = mapper.readValue(valueConvertMap, new TypeReference<Map<String, Map<String, String>>>() {
                });
            } catch (JsonProcessingException e) {
                log.error("Failed to parse the transform value map", e);
            }
        }
    }

    private String getHost(){
        return ecosWebAppProps.getWebUrl();
    }

    @Autowired
    public void setEcosContentApi(EcosContentApi ecosContentApi) {
        this.ecosContentApi = ecosContentApi;
    }

    @Autowired
    public void setRecordsService(RecordsService recordsService) {
        this.recordsService = recordsService;
    }

    @Autowired
    public void setDocumentDao(DocumentDao documentDao) {
        this.documentDao = documentDao;
    }

    @Autowired

    public void setEcosWebAppProps(EcosWebAppProps ecosWebAppProps) {
        this.ecosWebAppProps = ecosWebAppProps;
    }
}
