package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;

@Tag("wmock")
public class SDVoEAggregatorCommunicatorWiremockTest {
    private SDVoEAggregatorCommunicator sdvoeCommunicator;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(WireMockConfiguration.DYNAMIC_PORT);

    @Before
    public void setUp() throws Exception {
        wireMockRule.start();
        sdvoeCommunicator = new SDVoEAggregatorCommunicator();
        sdvoeCommunicator.setHost("localhost");
        sdvoeCommunicator.setPort(wireMockRule.port());
        sdvoeCommunicator.init();
    }

    @Test
    public void testRetrieveMultipleStatistics() throws Exception {
        List<AggregatedDevice> deviceList = sdvoeCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        // Need a few iterations to got through pause/resume states of the adapter
        sdvoeCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        sdvoeCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        deviceList = sdvoeCommunicator.retrieveMultipleStatistics();
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
    public void testGetMultipleStatistics() throws Exception {
        List<Statistics> statistics = sdvoeCommunicator.getMultipleStatistics();
        Assert.assertNotNull(statistics);
        Statistics extendedStatistics = statistics.get(0);
        Assert.assertNotNull(extendedStatistics);
        Map<String, String> stats = ((ExtendedStatistics)extendedStatistics).getStatistics();
        Assert.assertNotNull(stats);
        Assert.assertFalse(stats.isEmpty());
        Assert.assertNotNull(stats.get("AdapterVersion"));
        Assert.assertNotNull(stats.get("AdapterBuildDate"));
        Assert.assertNotNull(stats.get("AdapterUptime"));
        Assert.assertNotNull(stats.get("Server"));
        Assert.assertNotNull(stats.get("Vendor"));
        Assert.assertNotNull(stats.get("APIVersion"));
        Assert.assertNotNull(stats.get("APIBuild"));
    }
}
