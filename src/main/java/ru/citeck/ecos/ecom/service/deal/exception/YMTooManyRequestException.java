package ru.citeck.ecos.ecom.service.deal.exception;

public class YMTooManyRequestException extends RuntimeException {

    public YMTooManyRequestException(String message) {
        super(message);
    }
}
