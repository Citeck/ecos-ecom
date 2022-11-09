package ru.citeck.ecos.ecom.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.telegram.model.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.exceptions.TelegramApiException;
import ru.citeck.ecos.ecom.dto.ProcessDTO;
import ru.citeck.ecos.ecom.service.EcosAuthorityProvider;
import ru.citeck.ecos.ecom.service.EcosOrderPassProvider;
import ru.citeck.ecos.records2.RecordRef;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ru.citeck.ecos.ecom.processor.ProcessStates.*;

@Slf4j
@Component
public class OrderPassProcessor implements Processor {

    private Map<UserChatKey, ProcessDTO> dataCacheMap = new ConcurrentHashMap<>();
    @Autowired
    private EcosAuthorityProvider ecosAuthorityProvider;
    @Autowired
    private EcosOrderPassProvider ecosOrderPassProvider;

    @Override
    public void process(Exchange exchange) throws Exception {
        if (exchange.getIn().getBody() == null) {
            // fail fast
            log.debug("Received exchange with empty body, skipping");
            return;
        }
        IncomingMessage message = (IncomingMessage) exchange.getIn().getBody();

        OutgoingTextMessage outgoingTextMessage = handleIncomingMessage(message);
        log.debug(outgoingTextMessage.getText());
        exchange.getIn().setBody(outgoingTextMessage);
    }

    private OutgoingTextMessage handleIncomingMessage(@NotNull IncomingMessage message) throws TelegramApiException {
        UserChatKey userChatKey = new UserChatKey(message.getFrom().getId(), message.getChat().getId());
        ProcessDTO processDTO = dataCacheMap.get(userChatKey);
        String user = getUserByUserId(message.getFrom().getId());
        log.debug("handle");
        if (user.isEmpty()) {
            user = tryUserAuthorised(message, userChatKey);
            processDTO = null;
        }

        if (!user.isEmpty() && !user.equals("authorizationRequired") && !user.equals("unAuthorized")) {
            int state = STARTSTATE;
            if (processDTO != null) {
                state = processDTO.getState();
            } else {
                processDTO = new ProcessDTO();
                processDTO.setState(state);
                dataCacheMap.put(userChatKey, processDTO);
            }
            if (message.getText() != null && message.getText().equals("/order_pass")) {
                dataCacheMap.put(userChatKey, new ProcessDTO());
                dataCacheMap.get(userChatKey).setState(STARTSTATE);
                state = STARTSTATE;
            }

            OutgoingTextMessage sendMessageRequest;
            if (message.getText() != null && message.getText().equals("/help")) {
                sendMessageRequest = sendHelpMessage(userChatKey);
            } else {
                switch (state) {
                    case STARTSTATE:
                        sendMessageRequest = sendStartProcessMessage(userChatKey, processDTO);
                        break;
                    case FIO_STATE:
                        String FIO = message.getText();
                        processDTO.getData().put("fio", FIO);
                        sendMessageRequest = sendDateRequest(userChatKey, processDTO);
                        break;
                    case DATE_STATE:
                        processDTO.setAllDataReceived(true);
                        String orderPassCreated = createOrderPass(userChatKey, processDTO, user);
                        if (orderPassCreated.equals("success"))
                            sendMessageRequest = sendDataReceivedMessage(userChatKey, processDTO, message);
                        else sendMessageRequest = sendDataReceivedErrorMessage(userChatKey);
                        break;
                    default:
                        return sendHelpMessage(userChatKey);
                }
            }

            return sendMessageRequest;
        } else if (user.equals("unAuthorized"))
            return sendUnauthorizedMessage(userChatKey);
        else return sendMessageDefault(userChatKey);
    }

    private String tryUserAuthorised(IncomingMessage message, UserChatKey userChatKey) throws TelegramApiException {
        if (message.getContact() != null) {
            //userChatKey.setPhone(message.getContact().getPhone_number());
            return authoriseUser(message);
        } else {
            return "authorizationRequired";
        }
    }

    private String authoriseUser(IncomingMessage message) throws TelegramApiException {
        String phoneNumber = message.getContact().getPhone_number();
        log.debug("authoriseUser");

        if (phoneNumber == null) {
            log.debug("authoriseUser phone is null");
            log.debug(message.getText());
            log.debug(message.getContact().toString());
            return "authorizationRequired";
        }
        String user = null;
        user = ecosAuthorityProvider.getUserByPhoneNumber(phoneNumber);
        log.debug("User by phone: " + user);

        if (!user.isEmpty()) {
            ecosAuthorityProvider.setUserTelegramId(user, message.getFrom().getId().toString());
        } else
            user = "unAuthorized";
        return user;
    }

    private String getUserByUserId(Long id) {
        if (id == null) {
            return null;
        }
        String user = null;
        user = ecosAuthorityProvider.getUserByTelegramId(id.toString());

        return user;
    }

    private OutgoingTextMessage sendHelpMessage(UserChatKey userChatKey) {
        return sendHelpMessage(userChatKey.getChatId(), "Отправьте /order_pass для создания нового пропуска.");
    }

    private OutgoingTextMessage sendDataReceivedErrorMessage(UserChatKey userChatKey) {
        return sendHelpMessage(userChatKey.getChatId(), "Произошла ошибка, попробуйте еще раз. Отправьте /order_pass для создания нового пропуска.");
    }

    private OutgoingTextMessage sendDataReceivedMessage(UserChatKey userChatKey, ProcessDTO processDTO, IncomingMessage message) {
        String date = message.getText();
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy");
        Date visitDate = null;
        try {
            visitDate = format.parse(date);
        } catch (ParseException e) {
            return sendMessage(userChatKey.getChatId(), null, "Введите дату предполагаемого посещения в формате ДД.ММ.ГГГГ");
        }
        if (visitDate != null) {
            processDTO.getData().put("date", visitDate);
        }
        processDTO.setState(DATA_RECEIVED);
        return sendMessage(userChatKey.getChatId(), null, "Данные введены успешно. Ожидайте согласования пропуска, информация поступит в чат");
    }

    private OutgoingTextMessage sendDateRequest(UserChatKey userChatKey, ProcessDTO processDTO) {
        processDTO.setState(DATE_STATE);
        return sendMessage(userChatKey.getChatId(), null, "Введите дату предполагаемого посещения в формате ДД.ММ.ГГГГ");
    }

    private OutgoingTextMessage sendStartProcessMessage(UserChatKey userChatKey, ProcessDTO processDTO) {
        ReplyKeyboardMarkup keyboardRemove = ReplyKeyboardMarkup.builder()
                .removeKeyboard(true)
                .build();
        keyboardRemove.setSelective(true);
        processDTO.setState(FIO_STATE);
        return sendMessage(userChatKey.getChatId(), keyboardRemove, "Вы успешно авторизировались. В данный момент можно заказать пропуск. Укажите ФИО посетителя.");
    }

    private OutgoingTextMessage sendUnauthorizedMessage(UserChatKey userChatKey) {
        ReplyKeyboardMarkup replyKeyboardMarkup = getAuthoriseKeyboard();
        return sendMessage(userChatKey.getChatId(), replyKeyboardMarkup, "По вашему телефону не найден пользователь в системе ECOS. Обратитесь к администратору.");
    }

    private OutgoingTextMessage sendMessageDefault(UserChatKey userChatKey) {
        ReplyKeyboardMarkup replyKeyboardMarkup = getAuthoriseKeyboard();
        return sendMessage(userChatKey.getChatId(), replyKeyboardMarkup, "Для авторизации необходимо отправить свой контакт боту.");
    }

    private static OutgoingTextMessage sendMessage(String chatId, ReplyKeyboardMarkup replyKeyboardMarkup, String message) {
        OutgoingTextMessage sendMessage = new OutgoingTextMessage();
        sendMessage.setChatId(chatId);
        if (replyKeyboardMarkup != null) {
            sendMessage.setReplyMarkup(replyKeyboardMarkup);
        }
        sendMessage.setText(message);
        return sendMessage;
    }

    private static OutgoingTextMessage sendHelpMessage(String chatId, String message) {
        OutgoingTextMessage outgoingMessage = new OutgoingTextMessage();
        outgoingMessage.setChatId(chatId);
        outgoingMessage.setText(message);
        return outgoingMessage;
    }

    private ReplyKeyboardMarkup getAuthoriseKeyboard() {
        List<InlineKeyboardButton> keyboard = new ArrayList<>();
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText("Авторизоваться");
        button.setRequestContact(true);
        keyboard.add(button);

        ReplyKeyboardMarkup replyKeyboardMarkup = ReplyKeyboardMarkup.builder()
                .selective(true)
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .keyboard().addRow(keyboard)
                .close()
                .build();

        return replyKeyboardMarkup;
    }

    private RecordRef getUserRefByUserName(String user) {

        RecordRef userRef = null;
        userRef = ecosAuthorityProvider.getUserRefByUserName(user);

        return userRef;
    }

    private String createOrderPass(UserChatKey userChatKey, ProcessDTO processDTO, String user) {
        RecordRef mutatedRef = ecosOrderPassProvider.createOrderPass((String) processDTO.getData().get("fio"),
                (Date) processDTO.getData().get("date"),
                getUserRefByUserName(user),
                userChatKey.getChatId());
        if (!Objects.isNull(mutatedRef)) {
            dataCacheMap.remove(userChatKey);
            return "success";
        } else
            return "error";
    }

    public String getBotUsername() {
        return "ECOS Telegram Bot";
    }

}
