/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
import com.avispl.symphony.dal.communicator.dto.Error;
import com.avispl.symphony.dal.communicator.dto.ResponseWrapper;
import com.avispl.symphony.dal.communicator.dto.ResultWrapper;
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.*;

import static java.util.stream.Collectors.toList;

/**
 * SDVoE API Communicator to retrieve information about BlueRiver endpoints.
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public class SDVoEAggregatorCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {

    /**
     * Process that is running constantly and triggers collecting data from SDVoE API endpoints,
     * based on the given timeouts and thresholds.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class SDVoEDeviceDataLoader implements Runnable {
        private volatile boolean inProgress;

        public SDVoEDeviceDataLoader() {
            inProgress = true;
        }

        @Override
        public void run() {
            mainloop:
            while (inProgress) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    // Ignore for now
                }

                if (!inProgress) {
                    break mainloop;
                }

                // next line will determine whether SDVoE monitoring was paused
                updateAggregatorStatus();
                if (devicePaused) {
                    continue mainloop;
                }

                while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException e) {
                        if(logger.isWarnEnabled()) {
                            logger.warn("Process was interrupted!", e);
                        }
                    }
                }

                Map<String, Map<String, String>> netstat = new HashMap<>();
                try {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Fetching SDVoE devices list");
                    }
                    fetchDevicesList();
                    netstat.putAll(retrieveDevicesNetstat());

                    updateMulticastData();
                    if (logger.isDebugEnabled()) {
                        logger.debug("Fetched devices list: " + aggregatedDevices);
                    }
                } catch (Exception e) {
                    logger.error("Error occurred during device list retrieval", e);
                }

                Iterator<AggregatedDevice> deviceIterator = aggregatedDevices.values().iterator();
                while (deviceIterator.hasNext()) {
                    AggregatedDevice aggregatedDevice = deviceIterator.next();
                    devicesExecutionPool.add(executorService.submit(() -> {
                        boolean retrievedWithError = false;
                        try {
                            processDeviceDetails(aggregatedDevice, netstat.get(aggregatedDevice.getDeviceId()));
                        } catch (Exception e) {
                            e.printStackTrace();
                            retrievedWithError = true;
                            // remove if device was not retrieved successfully

                            logger.error(String.format("Exception during retrieval device by hardware id '%s'.", aggregatedDevice.getDeviceId()), e);
                            deviceIterator.remove();
                        }

                        if (!retrievedWithError) {
                            // Remove error related to a specific device from the collection, since
                            // it is retrieved successfully now.
                            //latestErrors.keySet().removeIf(s -> s.contains(String.format("[%s]", aggregatedDevice.getDeviceId())));
                        }
                    }));
                }
                if (!inProgress) {
                    break mainloop;
                }
                do {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException e) {
                        if (!inProgress) {
                            break;
                        }
                    }
                    devicesExecutionPool.removeIf(Future::isDone);
                } while (!devicesExecutionPool.isEmpty());
                // We don't want to fetch devices statuses too often, so by default it's currentTime + 30s
                // otherwise - the variable is reset by the retrieveMultipleStatistics() call, which
                // launches devices detailed statistics collection
                nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;

                if (logger.isDebugEnabled()) {
                    logger.debug("Finished collecting devices statistics cycle at " + new Date());
                }
            }
            // Finished collecting
        }

        /**
         * Triggers main loop to stop
         */
        public void stop() {
            inProgress = false;
        }
    }

    private AggregatedDeviceProcessor aggregatedDeviceProcessor;

    /**
     * Executor that runs all the async operations, that {@link #deviceDataLoader} is posting and
     * {@link #devicesExecutionPool} is keeping track of
     */
    private static ExecutorService executorService;

    /**
     * Runner service responsible for collecting data and posting processes to {@link #devicesExecutionPool}
     */
    private SDVoEDeviceDataLoader deviceDataLoader;

    /**
     * Pool for keeping all the async operations in, to track any operations in progress and cancel them if needed
     */
    private List<Future> devicesExecutionPool = new ArrayList<>();

    /**
     *
     * */
    Map<String, Map<String, String>> multicastData = new ConcurrentHashMap<>();

    Map<String, Map<String, String>> devicesNodeDataCache = new ConcurrentHashMap<>();
    Map<String, Map<String, String>> devicesStreamDataCache = new ConcurrentHashMap<>();
    Map<String, Map<String, String>> devicesSubscriptionsDataCache = new ConcurrentHashMap<>();

    /**
     * Map is Subscription: Stream deviceIds.
     * To get Stream by Subscription - direct mode is used
     * Otherwise - reverse mode should be used
     * */
    Map<String, String> subscriptionToStreamDeviceIDs = new ConcurrentHashMap<>();
    Map<String, String> streamToSubscriptionDeviceIDs = new ConcurrentHashMap<>();

    /**
     * Local statistics map, used as an aggregator cache storage
     * */
    private Map<String, String> localStatistics = new HashMap<>();

    /**
     * Devices this aggregator is responsible for
     * Data is cached and retrieved every {@link #defaultMetaDataTimeout}
     */
    private ConcurrentHashMap<String, AggregatedDevice> aggregatedDevices = new ConcurrentHashMap<>();

    /**
     * Adapter metadata, collected from the version.properties
     */
    private Properties adapterProperties;

    /**
     * Filter to limit the number of devices added into #aggregatedDevices list.
     * Possible values are ALL, ALL_RX, ALL_TX
     * */
    private DeviceFilter deviceFilter;

    /**
     * Device adapter instantiation timestamp.
     */
    private long adapterInitializationTimestamp;

    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link SDVoEAggregatorCommunicator#retrieveMultipleStatistics()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;

    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
     */
    private volatile long validRetrieveStatisticsTimestamp;

    /**
     * Time period within which the device metadata (basic devices information) cannot be refreshed.
     * Ignored if device list is not yet retrieved or the cached device list is empty {@link SDVoEAggregatorCommunicator#aggregatedDevices}
     */
    private volatile long validDeviceMetaDataRetrievalPeriodTimestamp;
    /**
     * Time period within which the general adapter metadata (firmware details) cannot be refreshed.
     */
    private volatile long validGeneralMetaDataRetrievalPeriodTimestamp;

    /**
     * Aggregator inactivity timeout. If the {@link SDVoEAggregatorCommunicator#retrieveMultipleStatistics()}  method is not
     * called during this period of time - device is considered to be paused, thus the Cloud API
     * is not supposed to be called
     */
    private static final long retrieveStatisticsTimeOut = 3 * 60 * 1000;

    /**
     * If the {@link SDVoEAggregatorCommunicator#deviceMetaDataRetrievalTimeout} is set to a value that is too small -
     * devices list will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultMetaDataTimeout = 60 * 1000 / 2;

    /**
     * Device metadata retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long deviceMetaDataRetrievalTimeout = 60 * 1000 * 10;

    /**
     * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
     * new devices statistics loop will be launched before the next monitoring iteration. To avoid that -
     * this variable stores a timestamp which validates it, so when the devices statistics is done collecting, variable
     * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
     * {@link #aggregatedDevices} resets it to the currentTime timestamp, which will re-activate data collection.
     */
    private static long nextDevicesCollectionIterationTimestamp;

    /**
     * Whether service is running.
     */
    private volatile boolean serviceRunning;

    /**
     * Retrieves {@code {@link #deviceMetaDataRetrievalTimeout }}
     *
     * @return value of {@link #deviceMetaDataRetrievalTimeout}
     */
    public long getDeviceMetaDataRetrievalTimeout() {
        return deviceMetaDataRetrievalTimeout;
    }

    /**
     * Sets {@code deviceMetaDataInformationRetrievalTimeout}
     *
     * @param deviceMetaDataRetrievalTimeout the {@code long} field
     */
    public void setDeviceMetaDataRetrievalTimeout(long deviceMetaDataRetrievalTimeout) {
        this.deviceMetaDataRetrievalTimeout = Math.max(defaultMetaDataTimeout, deviceMetaDataRetrievalTimeout);
    }

    /**
     * Retrieves {@link #deviceFilter}
     *
     * @return value of {@link #deviceFilter}
     */
    public String getDeviceFilter() {
        return deviceFilter.toString();
    }

    /**
     * Sets {@link #deviceFilter} value
     *
     * @param deviceFilter new value of {@link #deviceFilter}
     */
    public void setDeviceFilter(String deviceFilter) {
        try {
            this.deviceFilter = DeviceFilter.valueOf(deviceFilter);
        } catch (Exception e) {
            logger.error("Exception while applying device filtering. Using ALL by default.", e);
        }
    }

    @Override
    protected void internalInit() throws Exception {
        setBaseUri("/api");

        Map<String, PropertiesMapping> models = new PropertiesMappingParser()
                .loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(models);

        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));

        adapterInitializationTimestamp = System.currentTimeMillis();
        if (logger.isDebugEnabled()) {
            logger.debug("Internal init is called.");
        }

        executorService = Executors.newCachedThreadPool();
        executorService.submit(deviceDataLoader = new SDVoEDeviceDataLoader());

        validDeviceMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis();
        validGeneralMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis();
        serviceRunning = true;

        super.internalInit();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void internalDestroy() {
        if (logger.isDebugEnabled()) {
            logger.debug("Internal destroy is called.");
        }
        serviceRunning = false;

        if (deviceDataLoader != null) {
            deviceDataLoader.stop();
            deviceDataLoader = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        devicesExecutionPool.forEach(future -> future.cancel(true));
        devicesExecutionPool.clear();
        aggregatedDevices.clear();
        super.internalDestroy();
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String deviceId = controllableProperty.getDeviceId();
        String command = controllableProperty.getProperty();

        switch (command){
            case "Reboot":
                postDeviceReboot(deviceId);
                break;
            default:
                if(logger.isWarnEnabled()) {
                    logger.warn("Unsupported control command: " + command);
                }
                break;
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controlProperties) throws Exception {
        if (CollectionUtils.isEmpty(controlProperties)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }
        for (ControllableProperty controllableProperty : controlProperties) {
            controlProperty(controllableProperty);
        }
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        long currentTimestamp = System.currentTimeMillis();
        Map<String, String> apiProperties = localStatistics;
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();

        //List<AdvancedControllableProperty> controllableProperties = new ArrayList<>();

        apiProperties.put("AdapterVersion", adapterProperties.getProperty("aggregator.version"));
        apiProperties.put("AdapterBuildDate", adapterProperties.getProperty("aggregator.build.date"));
        apiProperties.put("AdapterUptime", normalizeUptime((System.currentTimeMillis() - adapterInitializationTimestamp) / 1000));

        if (validGeneralMetaDataRetrievalPeriodTimestamp > currentTimestamp) {
            localStatistics = apiProperties;
            extendedStatistics.setStatistics(apiProperties);
            //extendedStatistics.setControllableProperties(controllableProperties);
            return Collections.singletonList(extendedStatistics);
        }
        JsonNode response = doGet("", JsonNode.class);
        if ("SUCCESS".equals(response.get("status").asText())) {
            aggregatedDeviceProcessor.applyProperties(apiProperties, response.get("result"), "API");
        }
        validGeneralMetaDataRetrievalPeriodTimestamp = currentTimestamp + deviceMetaDataRetrievalTimeout;
        localStatistics = apiProperties;

        extendedStatistics.setStatistics(apiProperties);
        return Collections.singletonList(extendedStatistics);
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Adapter initialized: %s, executorService exists: %s, serviceRunning: %s", isInitialized(), executorService != null, serviceRunning));
        }
        if (executorService == null || executorService.isTerminated() || executorService.isShutdown()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Restarting executor service and initializing with the new data loader");
            }
            // Due to the bug that after changing properties on fly - the adapter is destroyed but is not initialized properly afterwards,
            // so executor service is not running. We need to make sure executorService exists
            executorService = Executors.newCachedThreadPool();
            executorService.submit(deviceDataLoader = new SDVoEDeviceDataLoader());
        }
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Aggregator Multiple statistics requested. Aggregated Devices collected so far: %s. Runner thread running: %s. Executor terminated: %s",
                    aggregatedDevices.size(), serviceRunning, executorService.isTerminated()));
        }

        long currentTimestamp = System.currentTimeMillis();
        nextDevicesCollectionIterationTimestamp = currentTimestamp;
        updateValidRetrieveStatisticsTimestamp();

        aggregatedDevices.values().forEach(aggregatedDevice -> aggregatedDevice.setTimestamp(currentTimestamp));
        return new ArrayList<>(aggregatedDevices.values());
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> deviceIds) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("SDVoE retrieveMultipleStatistics deviceIds=" + String.join(" ", deviceIds));
        }
        return retrieveMultipleStatistics()
                .stream()
                .filter(aggregatedDevice -> deviceIds.contains(aggregatedDevice.getDeviceId()))
                .collect(toList());
    }

    @Override
    protected void authenticate() throws Exception {

    }

    //private void requireAPI(){    }

    private void mapDeviceNodes(String deviceId, ArrayNode deviceNodes, Map<String, String> deviceProperties) {
        for (JsonNode node: deviceNodes) {
            Map<String, String> properties = new HashMap<>();
            String nodeType = node.at("/type").asText();
            if (DeviceCapability.capabilitySupported(nodeType)){
                aggregatedDeviceProcessor.applyProperties(properties, node, nodeType);
            }
            if (!properties.isEmpty()) {
                deviceProperties.putAll(properties);
                devicesNodeDataCache.put(deviceId + "_" + nodeType, properties);
            }
        }
    }

    private ResponseWrapper executeGetOPWithSubset(String uri, String subset) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("op", "get");
        request.put("subset", subset);

        ResponseWrapper responseWrapper = doPost(uri, request, ResponseWrapper.class);
        if ("PROCESSING".equals(responseWrapper.getStatus())) {
            return retrieveRequestResult(responseWrapper.getRequestId());
        }
        return responseWrapper;
    }

    /**
     *
     * */
    private Map<String, Map<String, String>> retrieveDevicesNetstat() throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("op", "netstat");
        request.put("option", "read");
        request.put("filter", "counter");
        String deviceFilterValue;
        if (deviceFilter == null) {
            deviceFilterValue = DeviceFilter.ALL.toString();
        } else {
            deviceFilterValue = deviceFilter.toString();
        }

        ResponseWrapper responseWrapper = doPost("device/" + deviceFilterValue, request, ResponseWrapper.class);
        if ("PROCESSING".equals(responseWrapper.getStatus())) {
            return transformNetstatPropertiesToMap(retrieveRequestResult(responseWrapper.getRequestId()));
        }
        return transformNetstatPropertiesToMap(responseWrapper);
    }

    /**
     *
     * */
    private Map<String, Map<String, String>> transformNetstatPropertiesToMap(ResponseWrapper responseWrapper) {
        Map<String, Map<String, String>> netstatData = new HashMap<>();
        ResultWrapper resultWrapper = responseWrapper.getResult();
        if (resultWrapper == null) {
            return netstatData;
        }
        ArrayNode arrayNode = resultWrapper.getStatistics();
        if (arrayNode == null) {
            return netstatData;
        }
        for (JsonNode node: arrayNode) {
            Map<String, String> deviceNetstatData = new HashMap<>();
            aggregatedDeviceProcessor.applyProperties(deviceNetstatData, node, "Netstat");
            netstatData.put(node.get("device_id").asText(), deviceNetstatData);
        }
        return netstatData;
    }

    /**
     *
     * */
    private void postDeviceReboot(String deviceId) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("op", "reboot");
        ResponseWrapper response = doPost("device/" + deviceId, request, ResponseWrapper.class);
        Error error = response.getError();
        if (error != null) {
            throw new RuntimeException(error.getMessage());
        }
    }

    /**
     * TODO: repeatedly check this until it's successful
     * */
    private ResponseWrapper retrieveRequestResult(String requestId) throws Exception {
        ResponseWrapper responseWrapper = doGet("/request/" + requestId, ResponseWrapper.class);
        if (!"PROCESSING".equals(responseWrapper.getStatus())) {
            return responseWrapper;
        }
        // Need to sleep for 500ms before retry
        Thread.sleep(500);
        return retrieveRequestResult(requestId);
    }

    /**
     *
     * */
    private JsonNode retrieveDevices() throws Exception {
        String deviceFilterValue;
        if (deviceFilter == null) {
            deviceFilterValue = DeviceFilter.ALL.toString();
        } else {
            deviceFilterValue = deviceFilter.toString();
        }
        return doGet("device/" + deviceFilterValue, JsonNode.class);
    }

    /**
     * IP: Details structure. Based on IP we can figure out the device Id at that IP address,
     * So we can retrieve all other necessary streaming details
     * */
    private void updateMulticastData() throws Exception {
       ResponseWrapper response = doGet("multicast", ResponseWrapper.class);
       ResultWrapper resultWrapper = response.getResult();
       if (resultWrapper == null) {
           return;
       }
       ArrayNode multicastArray = resultWrapper.getMulticast();
       List<String> multicastIpAddressList = new ArrayList<>();
       for(JsonNode mc: multicastArray) {
           Map<String, String> deviceMulticastData = new HashMap<>();
           aggregatedDeviceProcessor.applyProperties(deviceMulticastData, mc, "Multicast");
           String ipAddress = deviceMulticastData.get("Address");
           multicastIpAddressList.add(ipAddress);
           multicastData.put(ipAddress, deviceMulticastData);
       }
       multicastData.entrySet().removeIf(entry -> !multicastIpAddressList.contains(entry.getKey()));
    }

    /**
     * Fetch full devices list, or list of devices based on {@link #deviceFilter}
     *
     * @throws Exception if any error occurs
     */
    private void fetchDevicesList() throws Exception {
        long currentTimestamp = System.currentTimeMillis();
        if (aggregatedDevices.size() > 0 && validDeviceMetaDataRetrievalPeriodTimestamp > currentTimestamp) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("General devices metadata retrieval is in cooldown. %s seconds left",
                        (validDeviceMetaDataRetrievalPeriodTimestamp - currentTimestamp) / 1000));
            }
            return;
        }
        // Clear latest errors because devices are retrieved from scratch now. This would only happen if hardwareIdFilter is not active.
        //latestErrors.clear();
        validDeviceMetaDataRetrievalPeriodTimestamp = currentTimestamp + deviceMetaDataRetrievalTimeout;
        List<AggregatedDevice> devicesStatistics = retrieveAggregatedDevicesMetadata();

        devicesStatistics.forEach(device -> {
            String deviceId = device.getDeviceId();

            device.setTimestamp(currentTimestamp);
            if (aggregatedDevices.containsKey(deviceId)) {
                aggregatedDevices.get(deviceId).setDeviceOnline(device.getDeviceOnline());
            } else {
                aggregatedDevices.put(deviceId, device);
            }
        });

        if (logger.isDebugEnabled()) {
            logger.debug("Updated SDVoE endpoints metadata: " + aggregatedDevices);
        }

        if (devicesStatistics.isEmpty()) {
            // If all the devices were not populated for any specific reason (no devices available, filtering, etc)
            aggregatedDevices.clear();
        }

        nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
    }

    private List<AggregatedDevice> retrieveAggregatedDevicesMetadata() throws Exception {
        JsonNode devices = retrieveDevices();
        JsonNode devicesNode = devices.get("result");
        List<AggregatedDevice> aggregatedDevices = new ArrayList<>();
        if (devicesNode != null) {
            aggregatedDevices.addAll(aggregatedDeviceProcessor.extractDevices(devicesNode));
        }
        return aggregatedDevices;
    }

    private void processDeviceDetails(AggregatedDevice device, Map<String, String> netstat) throws Exception {
        String deviceId = device.getDeviceId();
        ResponseWrapper deviceDetails = executeGetOPWithSubset("device/" + deviceId, "device");
        if (deviceDetails.getResult() != null) {
            JsonNode deviceResponse = deviceDetails.getResult().getDevices().get(0);

            Map<String, String> deviceProperties = new HashMap<>();
            aggregatedDeviceProcessor.applyProperties(deviceProperties, deviceResponse, "Device");

            ArrayNode streams = (ArrayNode) deviceResponse.at("/streams");
            ArrayNode subscriptions = (ArrayNode) deviceResponse.at("/subscriptions");
            ArrayNode nodes = (ArrayNode) deviceResponse.at("/nodes");
            mapDeviceNodes(deviceId, nodes, deviceProperties);

            if (subscriptions != null) {
                for (JsonNode subscription: subscriptions) {
                    Map<String, String> subscriptionInfo = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(subscriptionInfo, subscription, "Subscription");

                    if ("STREAMING".equals(subscriptionInfo.get("State"))) {
                        Map<String, String> multicastDetails = multicastData.get(subscriptionInfo.get("ConfigurationAddress"));
                        String outputType = subscriptionInfo.get("OutputType");
                        if (multicastDetails != null) {
                            String inputDeviceID = multicastDetails.get("DeviceID");
                            subscriptionToStreamDeviceIDs.put(deviceId, inputDeviceID);
                            streamToSubscriptionDeviceIDs.put(inputDeviceID, deviceId);
                            AggregatedDevice inputDevice = aggregatedDevices.get(inputDeviceID);
                            if (inputDevice != null) {
                                deviceProperties.put("Output" + outputType + "#" + "SubscribedTo", inputDevice.getDeviceName());
                            }
                        }
                        for(Map.Entry<String, String> entry: subscriptionInfo.entrySet()) {
                            deviceProperties.put("Output" + outputType + "#" + entry.getKey(), entry.getValue());
                        }
                    }
                }
            }

            if (streams != null) {
                for (JsonNode stream : streams) {
                    Map<String, String> streamInfo = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(streamInfo, stream, "Stream");

                    if (streamInfo.containsKey("Source")) {
                        ArrayNode sources = (ArrayNode) stream.at("/configuration/source/choices");
                        if (sources != null) {
                            for (JsonNode source: sources) {
                                if (streamInfo.get("Source").equals(source.at("/value").asText())) {
                                    aggregatedDeviceProcessor.applyProperties(streamInfo, source, "StreamSource");
                                    break;
                                }
                            }
                        }
                    }

//                    devicesStreamDataCache.put(deviceId + "_" + streamInfo.get("InputType"), streamInfo);
                    String streamConfigAddress = streamInfo.get("ConfigurationAddress");
                    String outputType = streamInfo.get("InputType");
                    if (StringUtils.isNotNullOrEmpty(streamConfigAddress) && multicastData.containsKey(streamConfigAddress)) {
                        Map<String, String> multicastDetails = multicastData.get(streamConfigAddress);
                        if (multicastDetails != null) {
                            String multicastDeviceId = multicastDetails.get("DeviceID");
                            String outputDeviceId = streamToSubscriptionDeviceIDs.get(multicastDeviceId);

////                            Map<String, String> streamNodeInfo = devicesStreamDataCache.get(multicastDeviceId + "_" + multicastDetails.get("StreamType"));
////                            if (streamNodeInfo != null) {
//                                Map<String, String> nodeDetails = devicesNodeDataCache.get(multicastDeviceId + "_" + multicastDetails.get("StreamType"));
//                                if (nodeDetails != null) {
//                                    String videoResolution = nodeDetails.get("VideoInput#Resolution");
//                                    if (StringUtils.isNotNullOrEmpty(videoResolution)) {
//                                        streamInfo.put("Resolution", nodeDetails.get("VideoInput#Resolution"));
//                                    }
//                                }
////                            }
//
                            if (outputDeviceId != null) {
                                AggregatedDevice inputDevice = aggregatedDevices.get(outputDeviceId);
                                if (inputDevice != null) {
                                    if ("HDMI_DECODER".equals(streamInfo.get("Source"))) {
                                        deviceProperties.put("Input" + outputType + "#" + "Resolution", inputDevice.getProperties().get("VideoInput#Resolution"));
                                    }
                                    deviceProperties.put("Input" + outputType + "#" + "SubscribedTo", inputDevice.getDeviceName());
                                }
                            } else if (multicastDeviceId != null && multicastDeviceId.equals(deviceId)) {
                                if ("HDMI_DECODER".equals(streamInfo.get("Source"))) {
                                    deviceProperties.put("Input" + outputType + "#" + "Resolution", deviceProperties.get("VideoInput#Resolution"));
                                }
                            }
                        }
                    }

                    if ("STREAMING".equals(streamInfo.get("State"))) {
                        for (Map.Entry<String, String> entry : streamInfo.entrySet()) {
                            deviceProperties.put("Input" + outputType + "#" + entry.getKey(), entry.getValue());
                        }
                    }
                }
            }

            if (netstat != null) {
                deviceProperties.putAll(netstat);
            }

            Error deviceError = deviceDetails.getError();
            if (deviceError != null) {
                deviceProperties.put("Error#Reason", deviceError.getReason());
                deviceProperties.put("Error#Message", deviceError.getMessage());
            }
            device.setProperties(deviceProperties);
        }
    }

    /**
     * Uptime is received in seconds, need to normalize it and make it human readable, like
     * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
     * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
     * We don't need to add a segment of time if it's 0.
     *
     * @param uptimeSeconds value in seconds
     * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
     */
    private String normalizeUptime(long uptimeSeconds) {
        StringBuilder normalizedUptime = new StringBuilder();

        long seconds = uptimeSeconds % 60;
        long minutes = uptimeSeconds % 3600 / 60;
        long hours = uptimeSeconds % 86400 / 3600;
        long days = uptimeSeconds / 86400;

        if (days > 0) {
            normalizedUptime.append(days).append(" day(s) ");
        }
        if (hours > 0) {
            normalizedUptime.append(hours).append(" hour(s) ");
        }
        if (minutes > 0) {
            normalizedUptime.append(minutes).append(" minute(s) ");
        }
        if (seconds > 0) {
            normalizedUptime.append(seconds).append(" second(s)");
        }
        return normalizedUptime.toString().trim();
    }

    /**
     * Update the status of the device.
     * The device is considered as paused if did not receive any retrieveMultipleStatistics()
     * calls during {@link SDVoEAggregatorCommunicator#validRetrieveStatisticsTimestamp}
     *
     */
    private synchronized void updateAggregatorStatus() {
        devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
    }

    /**
     * Update general aggregator status (paused or active) and update the value, based on which
     * it the device is considered paused (2 minutes inactivity -> {@link #retrieveStatisticsTimeOut})
     */
    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAggregatorStatus();
    }
}
