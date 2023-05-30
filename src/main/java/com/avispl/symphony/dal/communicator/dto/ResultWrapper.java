/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.dto;

import com.fasterxml.jackson.databind.node.ArrayNode;

public class ResultWrapper {
    private ArrayNode devices;
    private ArrayNode statistics;
    private ArrayNode multicast;

    /**
     * Retrieves {@link #devices}
     *
     * @return value of {@link #devices}
     */
    public ArrayNode getDevices() {
        return devices;
    }

    /**
     * Sets {@link #devices} value
     *
     * @param devices new value of {@link #devices}
     */
    public void setDevices(ArrayNode devices) {
        this.devices = devices;
    }

    /**
     * Retrieves {@link #statistics}
     *
     * @return value of {@link #statistics}
     */
    public ArrayNode getStatistics() {
        return statistics;
    }

    /**
     * Sets {@link #statistics} value
     *
     * @param statistics new value of {@link #statistics}
     */
    public void setStatistics(ArrayNode statistics) {
        this.statistics = statistics;
    }

    /**
     * Retrieves {@link #multicast}
     *
     * @return value of {@link #multicast}
     */
    public ArrayNode getMulticast() {
        return multicast;
    }

    /**
     * Sets {@link #multicast} value
     *
     * @param multicast new value of {@link #multicast}
     */
    public void setMulticast(ArrayNode multicast) {
        this.multicast = multicast;
    }
}
