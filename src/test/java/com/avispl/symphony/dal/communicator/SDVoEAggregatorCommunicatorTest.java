/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

@Tag("test")
public class SDVoEAggregatorCommunicatorTest {
    static SDVoEAggregatorCommunicator sdvoeAggregatorCommunicator;

    @BeforeEach
    public void init() throws Exception {
        sdvoeAggregatorCommunicator = new SDVoEAggregatorCommunicator();
        sdvoeAggregatorCommunicator.setPassword("");
        sdvoeAggregatorCommunicator.setHost("10.30.50.130");
        sdvoeAggregatorCommunicator.setProtocol("http");
        sdvoeAggregatorCommunicator.setPort(4188);
        sdvoeAggregatorCommunicator.init();
    }

    @Test
    public void testRetrieveStatistics() throws Exception {
        List<AggregatedDevice> deviceList = sdvoeAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        // Need a few iterations to got through pause/resume states of the adapter
        sdvoeAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        sdvoeAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        deviceList = sdvoeAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        deviceList = sdvoeAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        deviceList = sdvoeAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(deviceList.isEmpty());
        for(AggregatedDevice aggregatedDevice: deviceList) {
            Assert.assertNotNull(aggregatedDevice);
            Assert.assertNotNull(aggregatedDevice.getDeviceId());
            Map<String, String> deviceProperties = aggregatedDevice.getProperties();
            Assert.assertNotNull(deviceProperties);
            Assert.assertFalse(deviceProperties.isEmpty());
        }
    }

    @Test
    public void testRunCommand() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("UART0#BaudRate");
        controllableProperty.setDeviceId("341b2281523c");
        controllableProperty.setValue(57600);
        sdvoeAggregatorCommunicator.controlProperty(controllableProperty);
    }

    @Test
    public void testGetMultipleStatistics() throws Exception {
        sdvoeAggregatorCommunicator.getMultipleStatistics();
    }

}
