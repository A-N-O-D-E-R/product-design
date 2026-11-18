package com.anode.productdesigner.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Une valeur de ~/.productdesigner/application.yml manque pour l'action demandée. */
@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
public class ConfigManquanteException extends RuntimeException {

    public ConfigManquanteException(String message) {
        super(message);
    }
}
