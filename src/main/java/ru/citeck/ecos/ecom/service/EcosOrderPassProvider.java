package ru.citeck.ecos.ecom.service;

import ecos.com.fasterxml.jackson210.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.ExchangeBuilder;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.citeck.ecos.commons.data.MLText;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.events2.EventsService;
import ru.citeck.ecos.events2.listener.ListenerConfig;
import ru.citeck.ecos.events2.type.RecordStatusChangedEvent;
import ru.citeck.ecos.records2.RecordRef;
import ru.citeck.ecos.records2.rest.RemoteRecordsUtils;
import ru.citeck.ecos.records3.RecordsService;
import ru.citeck.ecos.records3.record.atts.dto.RecordAtts;
import ru.citeck.ecos.records3.record.atts.schema.annotation.AttName;
import ru.citeck.ecos.records2.predicate.model.Predicates;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.Date;

@Slf4j
@Service
public class EcosOrderPassProvider {

    private static final String EMODEL_APP = "emodel";
    private static final String sourceId = "order-pass";

    @Autowired
    private RecordsService recordsService;
    @Autowired
    private EventsService eventsService;

    @Autowired
    private ProducerTemplate producer;
    @Autowired
    private CamelContext camelContext;

    //private HashMap record = new HashMap();

    @PostConstruct
    public void init() {

        //eventsService.addListener(new ListenerConfig<>("id", "record-status-changed", StatusChanged.class, record, false, event->eventAction(event), Predicates.empty(""), true));

        eventsService.addListener(ListenerConfig.<StatusChanged>create()
                .withEventType(RecordStatusChangedEvent.TYPE)
                .withDataClass(StatusChanged.class)
                .withActionJ(event -> onEvent(event))
                .withFilter(Predicates.and(Predicates.contains("after{name?json,id:?localId}", "passConfirmed"), Predicates.contains("record?id", sourceId)))
                .build()
        );

        //record["docStatusTitle"] = event.after.name.getClosest(RU_LOCALE)
        //record["eventType"] = "status.changed"
        //record["userId"] = event.user
        //record["username"] = event.user
        //record["creationTime"] = formatTime(event.time)
        //record["comments"] =
        //        "${buildStatusMsg(event.before)} -> ${buildStatusMsg(event.after)}"
        //.withEventType(RecordStatusChangedEvent.TYPE)
        //    withDataClass(StatusChanged.class)
        //    withAction { event ->
        //historyRecordService.saveOrUpdateRecord(HistoryRecordEntity(), record)
    }

    void onEvent(StatusChanged event){
        log.debug("event status changed" + event.record.toString());
        log.debug("event" + event.toString());

        String telegramChatID = getTelegramIdFromOrderPass(event.record.toString());
        if (!telegramChatID.isEmpty()) {
            String message = "Пропуск заказан";
            Exchange exchangeRequest = ExchangeBuilder.anExchange(camelContext)
                    .withHeader("TELEGRAM_CHAT_ID", telegramChatID)
                    .withBody(createNotifyMessage(telegramChatID, message))
                    .build();
            Exchange exchangeResponse = producer.send("direct:notifyUser", exchangeRequest);
        }
    }

    private static OutgoingTextMessage createNotifyMessage(String telegramChatID, String message) {
        OutgoingTextMessage outgoingMessage = new OutgoingTextMessage();
        outgoingMessage.setChatId(telegramChatID);
        outgoingMessage.setText(message);
        return outgoingMessage;
    }

    public RecordRef createOrderPass(String fio, Date date, RecordRef initiator, String chatId) {
        OrderPassDto orderPassDto = new OrderPassDto();
        orderPassDto.setVisitorFullName(fio);
        orderPassDto.setVisitingDate(date);
        orderPassDto.setInitiator(initiator);
        orderPassDto.setTelegramChatID(chatId);

        ObjectData targetAttributesData = ObjectData.create(orderPassDto);
        //targetAttributesData.set(RecordConstants.ATT_TYPE, "emodel/type@order-pass");

        RecordRef ref = RecordRef.create(EMODEL_APP, sourceId, "");
        RecordAtts recordAtts = new RecordAtts(ref, targetAttributesData);
        try {
            RecordRef resultRef = RemoteRecordsUtils.runAsSystem(() -> recordsService.mutate(recordAtts));
            log.debug("Mutated {}", resultRef);
            return resultRef;
        } catch (Exception e) {
            log.error("Failed to mutate record {}", recordAtts, e);
            return null;
        }

    }

    public String getTelegramIdFromOrderPass(String ref) {
        String telegramChatID = recordsService.getAtt(ref, "telegramChatID?str").asText();

        log.debug("getTelegramIdFromOrderPass: " + telegramChatID);

        return telegramChatID;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class OrderPassDto {
        @JsonProperty("?id")
        private RecordRef id;

        @JsonProperty("visitorFullName")
        private String visitorFullName;

        @JsonProperty("visitorOrganization")
        private String visitorOrganization;

        @JsonProperty("visitingDate")
        private Date visitingDate;

        @JsonProperty("initiator")
        private RecordRef initiator;

        @JsonProperty("telegramChatID")
        private String telegramChatID;

    }
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StatusChanged {

        @AttName("record?id")
        private RecordRef record;

        private StatusValue before;
        private StatusValue after;

        @AttName("event.time")
        private Instant time;
        @AttName("event.user")
        private String user;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StatusValue {
        private String id;
        private MLText name;
    }
}
