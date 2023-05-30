/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ResponseWrapper {
    private String status;
    @JsonProperty("request_id")
    private String requestId;
    private ResultWrapper result;
    private Error error;

    /**
     * Retrieves {@link #status}
     *
     * @return value of {@link #status}
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets {@link #status} value
     *
     * @param status new value of {@link #status}
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Retrieves {@link #requestId}
     *
     * @return value of {@link #requestId}
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Sets {@link #requestId} value
     *
     * @param requestId new value of {@link #requestId}
     */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * Retrieves {@link #error}
     *
     * @return value of {@link #error}
     */
    public Error getError() {
        return error;
    }

    /**
     * Sets {@link #error} value
     *
     * @param error new value of {@link #error}
     */
    public void setError(Error error) {
        this.error = error;
    }

    /**
     * Retrieves {@link #result}
     *
     * @return value of {@link #result}
     */
    public ResultWrapper getResult() {
        return result;
    }

    /**
     * Sets {@link #result} value
     *
     * @param result new value of {@link #result}
     */
    public void setResult(ResultWrapper result) {
        this.result = result;
    }
}
