package ru.citeck.ecos.ecom.routes;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.citeck.ecos.commons.data.ObjectData;
import ru.citeck.ecos.config.lib.consumer.bean.EcosConfig;
import ru.citeck.ecos.ecom.processor.ReadMailboxPTProcessor;
import ru.citeck.ecos.ecom.processor.mail.EcomMailReaderProcessor;
import ru.citeck.ecos.ecom.service.cameldsl.MailBodyExtractor;
import ru.citeck.ecos.ecom.service.pt.MailboxMessageMover;
import ru.citeck.ecos.secrets.lib.EcosSecrets;
import ru.citeck.ecos.secrets.lib.secret.basic.BasicSecretData;
import ru.citeck.ecos.webapp.api.entity.EntityRef;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class ReadMailboxPTRoute extends RouteBuilder {

    public static final String ROUTE_ID = "readMailboxPTRoute";
    public static final String DISABLED_URI = "disabled";

    private static final String PROP_BASE_URL = "ptGlobalBaseImapUrl";
    private static final String PROP_USERNAME = "ptGlobalUsername";
    private static final String PROP_PASSWORD = "ptGlobalPassword";
    private static final String PROP_SOURCE_FOLDER = "ptGlobalSourceFolder";

    @EcosConfig("mail-inbox-pt")
    private ObjectData config;

    private final ReadMailboxPTProcessor readMailboxPTProcessor;
    private final MailboxMessageMover mailboxMessageMover;

    @Autowired
    public ReadMailboxPTRoute(
        ReadMailboxPTProcessor readMailboxPTProcessor,
        MailboxMessageMover mailboxMessageMover
    ) {
        this.readMailboxPTProcessor = readMailboxPTProcessor;
        this.mailboxMessageMover = mailboxMessageMover;
    }

    @Override
    public void configure() {
        MailboxContext context = buildContext();

        onException(Exception.class)
            .handled(true)
            .log("Project Tracker mailbox processing failed: ${exception.message}");

        EcomCamelMailUtils.fromMailUri(this, context.endpointUri)
            .routeId(ROUTE_ID)
            .process(exchange -> {
                if (context.baseUrl != null) {
                    exchange.setProperty(PROP_BASE_URL, context.baseUrl);
                    exchange.setProperty(PROP_USERNAME, context.username);
                    exchange.setProperty(PROP_PASSWORD, context.password);
                    exchange.setProperty(PROP_SOURCE_FOLDER, context.sourceFolder);
                }
            })
            .to("log:raw-email-pt?level=DEBUG&showHeaders=true")
            .bean(MailBodyExtractor.class, "extract(*)")
            .process(new EcomMailReaderProcessor())
            .to("log:parsed-email-pt?level=DEBUG")
            .process(readMailboxPTProcessor)
            .process(buildMoveProcessor());
    }

    private Processor buildMoveProcessor() {
        return (Exchange exchange) -> {
            String baseUrl = exchange.getProperty(PROP_BASE_URL, String.class);
            if (StringUtils.isBlank(baseUrl)) {
                return;
            }
            ReadMailboxPTProcessor.ImportOutcome outcome = exchange.getProperty(
                ReadMailboxPTProcessor.IMPORT_RESULT_PROPERTY,
                ReadMailboxPTProcessor.ImportOutcome.class
            );
            if (outcome == null) {
                return;
            }
            // Read success/error folders fresh on each exchange so UI-mutations of mail-inbox-pt
            // take effect without a route rebuild (field `config` is kept live by ecos-config).
            String successFolder = config != null ? config.get("successFolder").asText() : "";
            String errorFolder = config != null ? config.get("errorFolder").asText() : "";
            String target = switch (outcome) {
                case IMPORTED, DUPLICATE -> successFolder;
                case NO_TARGET, FAILED -> errorFolder;
            };
            if (StringUtils.isBlank(target)) {
                return;
            }
            String messageId = exchange.getIn().getHeader("Message-ID", String.class);
            if (messageId != null) {
                messageId = messageId.trim();
                if (messageId.startsWith("<") && messageId.endsWith(">")) {
                    messageId = messageId.substring(1, messageId.length() - 1);
                }
            }
            mailboxMessageMover.move(new MailboxMessageMover.MoveParams(
                baseUrl,
                exchange.getProperty(PROP_USERNAME, String.class),
                exchange.getProperty(PROP_PASSWORD, String.class),
                exchange.getProperty(PROP_SOURCE_FOLDER, String.class),
                target,
                messageId
            ));
        };
    }

    private MailboxContext buildContext() {
        if (config == null || !config.get("enabled").asBoolean()) {
            log.info("Project Tracker mailbox is disabled");
            return MailboxContext.disabled();
        }
        String imap = config.get("imap").asText();
        if (StringUtils.isBlank(imap)) {
            log.warn("Project Tracker mailbox has no IMAP URL configured");
            return MailboxContext.disabled();
        }
        String credsRefStr = config.get("credentials").asText();
        if (StringUtils.isBlank(credsRefStr)) {
            log.warn("Project Tracker mailbox has no credentials configured");
            return MailboxContext.disabled();
        }

        EntityRef credsRef = EntityRef.valueOf(credsRefStr);
        BasicSecretData secretData = EcosSecrets.getBasicDataOrNull(credsRef.getLocalId());
        if (secretData == null) {
            log.warn("Project Tracker mailbox secret not found: {}", credsRef);
            return MailboxContext.disabled();
        }
        String username = secretData.getUsername();
        String password = secretData.getPassword();
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            log.warn("Project Tracker mailbox credentials are incomplete for {}", credsRef);
            return MailboxContext.disabled();
        }

        String folder = config.get("folder").asText();
        if (StringUtils.isBlank(folder)) {
            folder = "INBOX";
        }
        long delay = config.get("delay").asLong(60_000L);

        String separator = imap.contains("?") ? "&" : "?";
        String uri = imap + separator
            + "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
            + "&folderName=" + URLEncoder.encode(folder, StandardCharsets.UTF_8)
            + "&delay=" + delay
            + "&delete=false&unseen=true";

        return new MailboxContext(uri, imap, username, password, folder);
    }

    private record MailboxContext(
        String endpointUri,
        String baseUrl,
        String username,
        String password,
        String sourceFolder
    ) {
        static MailboxContext disabled() {
            return new MailboxContext(DISABLED_URI, null, null, null, null);
        }
    }
}
