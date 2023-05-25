package com.avispl.symphony.dal.communicator.dto;

public class Error {
    private String reason;
    private String message;

    /**
     * Retrieves {@link #reason}
     *
     * @return value of {@link #reason}
     */
    public String getReason() {
        return reason;
    }

    /**
     * Sets {@link #reason} value
     *
     * @param reason new value of {@link #reason}
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * Retrieves {@link #message}
     *
     * @return value of {@link #message}
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets {@link #message} value
     *
     * @param message new value of {@link #message}
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
