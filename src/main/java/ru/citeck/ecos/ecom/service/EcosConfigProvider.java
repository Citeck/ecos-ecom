package ru.citeck.ecos.ecom.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.citeck.ecos.records2.RecordRef;
import ru.citeck.ecos.records2.rest.RemoteRecordsUtils;
import ru.citeck.ecos.records3.RecordsService;

@Service
public class EcosConfigProvider {

    @Autowired
    private RecordsService recordsService;

    public static final String TELEGRAM_ENABLED_CONFIG_KEY = "telegram-bot-enabled";
    public static final String TELEGRAM_BOT_TOKEN_CONFIG_KEY = "telegram-bot-token";

    private static final String EMODEL_APP = "emodel";
    private static final String ECOS_CONFIG_SK = "ecos-config";

    //public EcosConfigProvider(RecordsService recordsService) {
    //    this.recordsService = recordsService;
    //}

    public String getConfigValue(String key) {
        return RemoteRecordsUtils.runAsSystem(() -> recordsService.getAtt(RecordRef.create(EMODEL_APP, ECOS_CONFIG_SK,
                key), "?str").asText());
    }
}
