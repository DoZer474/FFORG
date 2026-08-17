package com.fireflink.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Every FireFlink API response observed so far follows this outer shape:
 * { "responseCode": 200, "responseObject": {...or [...]}, "errorCode": 0, "message": "SUCCESS" }
 *
 * @param <T> the shape of responseObject for a given endpoint
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiEnvelope<T> {
    public int responseCode;
    public T responseObject;
    public int errorCode;
    public String message;
}
