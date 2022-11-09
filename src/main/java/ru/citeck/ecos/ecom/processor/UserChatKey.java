package ru.citeck.ecos.ecom.processor;

import java.util.Objects;

/**
 * Class - key for different users processes
 */
public class UserChatKey {
    private Long userId;
    private String chatId;
    //private String phone;

    public UserChatKey(Long userId, String chatId) {
        this.userId = userId;
        this.chatId = chatId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getChatId() {
        return chatId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserChatKey)) return false;

        UserChatKey that = (UserChatKey) o;

        if (!Objects.equals(userId, that.userId)) return false;
        return Objects.equals(chatId, that.chatId);
    }

    @Override
    public int hashCode() {
        int result = userId != null ? userId.hashCode() : 0;
        result = 31 * result + (chatId != null ? chatId.hashCode() : 0);
        return result;
    }

    //public String getPhone() {
    //    return phone;
    //}

    //public void setPhone(String phone) {
    //    this.phone = phone;
    //}
}
