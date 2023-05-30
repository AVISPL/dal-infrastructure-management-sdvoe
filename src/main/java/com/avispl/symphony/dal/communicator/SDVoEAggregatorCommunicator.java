/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
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
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.*;

import static com.avispl.symphony.dal.communicator.Constants.*;
import static com.avispl.symphony.dal.util.ControllablePropertyFactory.*;
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
                    if(logger.isWarnEnabled()) {
                        logger.warn("Process was interrupted!", e);
                    }
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

                boolean retrievedWithErrors = false;
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
                    retrievedWithErrors = true;
                    latestErrors.put(e.toString(), e.getMessage());
                    logger.error("Error occurred during device list retrieval", e);
                }
                if (!retrievedWithErrors) {
                    latestErrors.clear();
                }

                Iterator<AggregatedDevice> deviceIterator = aggregatedDevices.values().iterator();
                while (deviceIterator.hasNext()) {
                    AggregatedDevice aggregatedDevice = deviceIterator.next();
                    devicesExecutionPool.add(executorService.submit(() -> {
                        Map<String, String> deviceProperties = aggregatedDevice.getProperties();
                        boolean retrievedWithError = false;
                        try {
                            processDeviceDetails(aggregatedDevice, netstat.get(aggregatedDevice.getDeviceId()));
                        } catch (Exception e) {
                            if (deviceProperties != null) {
                                deviceProperties.put("Error#API", e.getMessage());
                            }
                            retrievedWithError = true;
                            // remove if device was not retrieved successfully
                            logger.error(String.format("Exception during retrieval device by hardware id '%s'.", aggregatedDevice.getDeviceId()), e);
                        }

                        if (!retrievedWithError) {
                            // Remove error related to a specific device from the collection, since
                            // it is retrieved successfully now.
                            deviceProperties.remove("Error#API");
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

    /**
     *
     * */
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
     * List of all models, present in yml mapping
     * */
    Map<String, PropertiesMapping> models;

    /**
     * Match stream to subscription by device IDs, to match streamer to subscriber and vice-versa
     * */
    Multimap<String, String> streamToSubscriptionDeviceIDs = ArrayListMultimap.create();

    /**
     * Netstat details storage
     * */
    Map<String, Map<String, String>> netstat = new HashMap<>();

    /**
     * Latest aggregator errors
     * */
    Map<String, String> latestErrors = new HashMap<>();

    /**
     * Multicast details storage
     * */
    Map<String, Map<String, String>> multicastData = new ConcurrentHashMap<>();

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
        if (logger.isDebugEnabled()) {
            logger.debug("Internal init is called.");
        }
        setBaseUri("/api");
        models = new PropertiesMappingParser()
                .loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(models);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
        executorService = Executors.newCachedThreadPool();
        adapterInitializationTimestamp = System.currentTimeMillis();
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
        latestErrors.clear();
        super.internalDestroy();
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String deviceId = controllableProperty.getDeviceId();
        String command = controllableProperty.getProperty();
        String value = String.valueOf(controllableProperty.getValue());

        if (command.startsWith(STREAM)) {
            executeSetStreamSourceCommand(deviceId, value);
            return;
        } else if (command.startsWith(UART)) {
            String uartIndex = command.substring(command.indexOf(UART) + 4, command.indexOf("#"));
            if (command.endsWith(BAUD_RATE)) {
                executeSetPropertyCommand(deviceId, String.format(BAUD_RATE_KEY, uartIndex), Integer.valueOf(value));
            } else if (command.endsWith(DATA_BITS)) {
                executeSetPropertyCommand(deviceId, String.format(DATA_BITS_KEY, uartIndex), Integer.valueOf(value));
            } else if (command.endsWith(PARITY)) {
                executeSetPropertyCommand(deviceId, String.format(PARITY_KEY, uartIndex), Integer.valueOf(value));
            } else if (command.endsWith(STOP_BITS)) {
                executeSetPropertyCommand(deviceId, String.format(STOP_BITS_KEY, uartIndex), Integer.valueOf(value));
            }
            return;
        }
        switch (command){
            case REBOOT:
                postDeviceReboot(deviceId);
                break;
            case LOCATE_MODE:
                executeSetPropertyCommand(deviceId, LOCATE_MODE_KEY, "1".equals(value));
                break;
            case RESUME_STREAMING:
                executeSetPropertyCommand(deviceId, RESUME_STREAMING_KEY, "1".equals(value));
                break;
            case DEVICE_NAME:
                executeSetPropertyCommand(deviceId, DEVICE_NAME_KEY, value);
                break;
            case HDMI_OUTPUT_MODE:
                String[] values = value.split("\\|");
                executeSetPropertyCommand(deviceId, String.format(HDCP_OUTPUT_MODE_KEY, values[0]), values[1]);
                break;
            case HDMI_STEREO_AUDIO_SOURCE:
            case HDMI_AUDIO_SOURCE:
                executeSetNodeInputsSourceCommand(deviceId, value);
                break;
            case VIDEO_COMPRESSOR_VERSION:
                values = value.split(":");
                executeSetPropertyCommand(deviceId, String.format(VIDEO_COMPRESSOR_VERSION_KEY, values[0]), Integer.valueOf(values[1]));
                break;
            case VIDEO_DECOMPRESSOR_VERSION:
                values = value.split(":");
                executeSetPropertyCommand(deviceId, String.format(VIDEO_DECOMPRESSOR_VERSION_KEY, values[0]), Integer.valueOf(values[1]));
                break;
            case LED_FUNCTION:
                break;
            case HDMI_TX5v:
                values = value.split("\\|");
                executeSetPropertyCommand(deviceId, String.format(HDMI_TX_5V_KEY, values[0]), values[1]);
                break;
            default:
                if(logger.isWarnEnabled()) {
                    logger.warn("Unsupported control command: " + command);
                }
                break;
        }

        if (aggregatedDevices.containsKey(deviceId)) {
            processDeviceDetails(aggregatedDevices.get(deviceId), netstat.get(deviceId));
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

        apiProperties.put("AdapterVersion", adapterProperties.getProperty("aggregator.version"));
        apiProperties.put("AdapterBuildDate", adapterProperties.getProperty("aggregator.build.date"));
        apiProperties.put("AdapterUptime", normalizeUptime((System.currentTimeMillis() - adapterInitializationTimestamp) / 1000));

        if (latestErrors != null && !latestErrors.isEmpty()) {
            latestErrors.forEach((key, value) -> apiProperties.put("Error#" + normalizeIOType(key), value));
        }
        if (validGeneralMetaDataRetrievalPeriodTimestamp > currentTimestamp) {
            localStatistics = apiProperties;
            extendedStatistics.setStatistics(apiProperties);
            return Collections.singletonList(extendedStatistics);
        }
        JsonNode response = doGet("", JsonNode.class);
        if (SUCCESS.equals(response.get("status").asText())) {
            aggregatedDeviceProcessor.applyProperties(apiProperties, response.get("result"), "API");
        }
        validGeneralMetaDataRetrievalPeriodTimestamp = currentTimestamp + deviceMetaDataRetrievalTimeout;
        localStatistics = apiProperties;

        extendedStatistics.setStatistics(apiProperties);
        return Collections.singletonList(extendedStatistics);
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() {
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

    /**
     * Map device nodes information to properties and controls.
     * @param deviceId id of the device to map nodes for
     * @param deviceNodes array of nodes to map
     * @param controllableProperties reference to controllable properties collection to store controls
     * @param deviceProperties device properties, extracted so far and storage for new properties
     * */
    private void mapDeviceNodes(String deviceId, ArrayNode deviceNodes, List<AdvancedControllableProperty> controllableProperties, Map<String, String> deviceProperties) {
        for (JsonNode node: deviceNodes) {
            Map<String, String> properties = new HashMap<>();
            String nodeType = node.at(TYPE_PATH).asText();
            if (DeviceCapability.capabilitySupported(nodeType)){
                logger.debug("Collecting nodes data for device " + deviceId);
                if (models.containsKey(nodeType)) {
                    aggregatedDeviceProcessor.applyProperties(properties, node, nodeType);
                }
                String index = node.at(INDEX_PATH).asText();

                switch (nodeType) {
                    case STEREO_AUDIO_OUTPUT:
                        Map<String, String> inputNameToPropertyName = new HashMap<>();
                        inputNameToPropertyName.put(MAIN, HDMI_STEREO_AUDIO_SOURCE);
                        inputNameToPropertyName.values().forEach(s -> properties.put(s, ""));
                        controllableProperties.addAll(generateNodeControls(node, inputNameToPropertyName));
                        break;
                    case HDMI_ENCODER:
                        inputNameToPropertyName = new HashMap<>();
                        //inputNameToPropertyName.put("main", "HDMIEncoder#Source");
                        inputNameToPropertyName.put(AUDIO, HDMI_AUDIO_SOURCE);
                        inputNameToPropertyName.values().forEach(s -> properties.put(s, ""));
                        controllableProperties.addAll(generateNodeControls(node, inputNameToPropertyName));

                        String hdcpOutputMode = properties.get(HDMI_OUTPUT_MODE);
                        if (StringUtils.isNotNullOrEmpty(hdcpOutputMode) && !UNSUPPORTED.equals(hdcpOutputMode)) {
                            String propertyValue = nodeType + ":" + index + "|" + hdcpOutputMode;
                            controllableProperties.add(createDropdown(HDMI_OUTPUT_MODE,
                                    Arrays.asList(nodeType + ":" + index + "|FOLLOW_SINK_1", nodeType + ":" + index + "|FOLLOW_SINK_2", nodeType + ":" + index + "|FOLLOW_SOURCE"),
                                    HDMI_OUTPUT_MODES, propertyValue));
                            properties.put(HDMI_OUTPUT_MODE, propertyValue);
                        }
                        String hdmitx5v = properties.get(HDMI_TX5v);
                        if (StringUtils.isNotNullOrEmpty(hdmitx5v)) {
                            String propertyValue = nodeType + ":" + index + "|" + hdmitx5v;
                            controllableProperties.add(createDropdown(HDMI_TX5v,
                                    Arrays.asList(nodeType + ":" + index + "|AUTO", nodeType + ":" + index + "|LOW"), HDMI_TX_MODES, propertyValue));
                            properties.put(HDMI_TX5v, propertyValue);
                        }
                        break;
                    case UART:
                        String baudRate = node.at(BAUD_RATE_PATH).asText();
                        String dataBits = node.at(DATA_BITS_PATH).asText();
                        String stopBits = node.at(STOP_BITS_PATH).asText();
                        String parity = node.at(PARITY_PATH).asText();
                        String groupName = UART + index;

                        properties.put(groupName + BAUD_NAME_PROPERTY, baudRate);
                        properties.put(groupName + DATA_BITS_PROPERTY, dataBits);
                        properties.put(groupName + STOP_BITS_PROPERTY, stopBits);
                        properties.put(groupName + PARITY_PROPERTY, parity);
                        controllableProperties.add(createDropdown(groupName + BAUD_NAME_PROPERTY, baudRateArray, baudRateArray, baudRate));
                        controllableProperties.add(createDropdown(groupName + DATA_BITS_PROPERTY, dataStartBitsArray, dataStartBitsArray, dataBits));
                        controllableProperties.add(createDropdown(groupName + STOP_BITS_PROPERTY, dataStopBitsArray, dataStopBitsArray, stopBits));
                        controllableProperties.add(createDropdown(groupName + PARITY_PROPERTY, parityArray, parityArray, parity));
                        break;
                    case LED:
                        properties.put(LED_FUNCTION, "");
                        controllableProperties.addAll(generateNodeControls(node, null));
                        break;
                    case VIDEO_COMPRESSOR:
                        String videoCompressionVersion = node.at(VERSION_PATH).asText();
                        String currentValue = index + ":" + videoCompressionVersion;
                        controllableProperties.add(createDropdown(VIDEO_COMPRESSOR_VERSION, Arrays.asList(index + ":1", index + ":2"),
                                cdVersions,  currentValue));
                        properties.put(VIDEO_COMPRESSOR_VERSION, currentValue);
                        break;
                    case VIDEO_DECOMPRESSOR:
                        String videoDecompressionVersion = node.at(VERSION_PATH).asText();
                        currentValue = index + ":" + videoDecompressionVersion;
                        controllableProperties.add(createDropdown(VIDEO_DECOMPRESSOR_VERSION, Arrays.asList(index + ":1", index + ":2"),
                                cdVersions, currentValue));
                        properties.put(VIDEO_DECOMPRESSOR_VERSION, currentValue);
                        break;
                    default:
                        break;
                }
            }
            if (!properties.isEmpty()) {
                deviceProperties.putAll(properties);
            }
        }
    }

    /**
     * Create additional device controls
     *
     * @param controlsList list to save controls to
     * @param deviceProperties current map of device properties
     * */
    private void createDeviceControls(List<AdvancedControllableProperty> controlsList, Map<String, String> deviceProperties) {
        controlsList.add(createButton("Reboot", "Reboot", "Rebooting...", 60000));
        controlsList.add(createText(DEVICE_NAME, deviceProperties.get(DEVICE_NAME)));

        String locateMode = deviceProperties.get(LOCATE_MODE);
        if (StringUtils.isNotNullOrEmpty(locateMode)) {
            controlsList.add(createSwitch(LOCATE_MODE, Boolean.parseBoolean(locateMode) ? 1 : 0));
        }
        String resumeStreaming = deviceProperties.get(RESUME_STREAMING);
        if (StringUtils.isNotNullOrEmpty(locateMode)) {
            controlsList.add(createSwitch(RESUME_STREAMING, Boolean.parseBoolean(resumeStreaming) ? 1 : 0));
        }
    }

    /**
     * Execute get command with a specified subset
     * @param uri endpoint to post to
     * @param subset to use for command
     * @return {@link ResponseWrapper} response object
     *
     * @throws Exception if any error occurs
     * */
    private ResponseWrapper executeGetOPWithSubset(String uri, String subset) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("op", "get");
        request.put("subset", subset);

        ResponseWrapper responseWrapper = doPost(uri, request, ResponseWrapper.class);
        if (PROCESSING.equals(responseWrapper.getStatus())) {
            return retrieveRequestResult(responseWrapper.getRequestId());
        }
        return responseWrapper;
    }


    /**
     * Execute set stream source command with a specified value
     * @param deviceId to target a device
     * @param value to use for command
     *
     * @throws Exception if any error occurs
     * */
    private void executeSetStreamSourceCommand(String deviceId, String value) throws Exception {
        Map<String, Object> request = new HashMap<>();
        String[] commandReferenceValue = value.split("\\|");
        request.put("op", "set:property");
        request.put("key", String.format(STREAMS_CONFIGURATION_SOURCE_KEY, commandReferenceValue[0]));
        request.put("value", Integer.valueOf(commandReferenceValue[1]));

        doPost("device/" + deviceId, request);
    }

    /**
     * Execute set node inputs source command with a specified value
     * @param deviceId to target a device
     * @param value to use for command
     *
     * @throws Exception if any error occurs
     * */
    private void executeSetNodeInputsSourceCommand(String deviceId, String value) throws Exception {
        Map<String, Object> request = new HashMap<>();
        String[] commandReferenceValue = value.split("\\|");
        request.put("op", "set:property");
        request.put("key", String.format(NODE_INPUTS_KEY, commandReferenceValue[0], commandReferenceValue[1]));
        request.put("value", Integer.valueOf(commandReferenceValue[2]));

        doPost("device/" + deviceId, request);
    }

    /**
     * Execute set property command with a specified value and deviceId
     * @param deviceId to target a device
     * @param key to set a property to change
     * @param value to set for a property
     *
     * @throws Exception if any error occurs
     * */
    private void executeSetPropertyCommand(String deviceId, String key, Object value) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("op", "set:property");
        request.put("key", key);
        request.put("value", value);

        doPost("device/" + deviceId, request);
    }

    /**
     * Post device reboot command
     * @param deviceId to reboot
     * @throws Exception if any error occurs
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
     * Retrieve netstat details of all devices (or RX/TX based on filters)
     *
     * @return Map<String(deviceId):<Map<String:String>>> structure of netstat details
     * @throws Exception if any error occurs
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
        if (PROCESSING.equals(responseWrapper.getStatus())) {
            return transformNetstatPropertiesToMap(retrieveRequestResult(responseWrapper.getRequestId()));
        }
        return transformNetstatPropertiesToMap(responseWrapper);
    }

    /**
     * Transform netstat properties to map
     *
     * @param responseWrapper netstat response
     * @return Map<String(deviceId):Map<String:String>> structures netstat data
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
            netstatData.put(node.at(DEVICE_ID_PATH).asText(), deviceNetstatData);
        }
        return netstatData;
    }

    /**
     * Retrieve request result with a retry
     * @param requestId to check and execute, until "PROCESSING" state changes to anything else
     * @return {@link ResponseWrapper} command response
     * @throws Exception if any error occurs
     * */
    private ResponseWrapper retrieveRequestResult(String requestId) throws Exception {
        ResponseWrapper responseWrapper = doGet("/request/" + requestId, ResponseWrapper.class);
        if (!PROCESSING.equals(responseWrapper.getStatus())) {
            return responseWrapper;
        }
        // Need to sleep for 500ms before retry
        Thread.sleep(500);
        return retrieveRequestResult(requestId);
    }

    /**
     * Retrieve list of devices, based on filters set. ALL by default.
     *
     * @return {@link JsonNode} devices response
     * @throws Exception if any error occurs
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

    /**
     * Retrieve basic devices metadata
     *
     * @return List of AggregatedDevices
     * @throws Exception if any error occurs
     * */
    private List<AggregatedDevice> retrieveAggregatedDevicesMetadata() throws Exception {
        JsonNode devices = retrieveDevices();
        JsonNode devicesNode = devices.get("result");
        List<AggregatedDevice> aggregatedDevices = new ArrayList<>();
        if (devicesNode != null) {
            aggregatedDevices.addAll(aggregatedDeviceProcessor.extractDevices(devicesNode));
        }
        return aggregatedDevices;
    }

    /**
     * Process device details
     * @param device to process details for
     * @param netstat cached data to add to the device
     * @throws Exception if any error occurs
     * */
    private void processDeviceDetails(AggregatedDevice device, Map<String, String> netstat) throws Exception {
        String deviceId = device.getDeviceId();
        ResponseWrapper deviceDetails = executeGetOPWithSubset("device/" + deviceId, "device");
        if (deviceDetails.getResult() != null) {
            JsonNode deviceResponse = deviceDetails.getResult().getDevices().get(0);

            Map<String, String> deviceProperties = new HashMap<>();
            List<AdvancedControllableProperty> advancedControllableProperties = new ArrayList<>();
            aggregatedDeviceProcessor.applyProperties(deviceProperties, deviceResponse, "Device");
            device.setDeviceName(deviceProperties.get(DEVICE_NAME));

            String deviceMode = deviceProperties.get(DEVICE_MODE);
            boolean isTransmitter = TRANSMITTER.equals(deviceMode);

            ArrayNode streams = (ArrayNode) deviceResponse.at(STREAM_PATH);
            ArrayNode subscriptions = (ArrayNode) deviceResponse.at(SUBSCRIPTIONS_PATH);
            ArrayNode nodes = (ArrayNode) deviceResponse.at(NODES_PATH);
            mapDeviceNodes(deviceId, nodes, advancedControllableProperties, deviceProperties);

            if (subscriptions != null) {
                for (JsonNode subscription: subscriptions) {
                    Map<String, String> subscriptionInfo = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(subscriptionInfo, subscription, "Subscription");

                    if (!STREAMING.equals(subscriptionInfo.get("State"))) {
                        continue;
                    }
                    Map<String, String> multicastDetails = multicastData.get(subscriptionInfo.get("ConfigurationAddress"));
                    String outputType = subscriptionInfo.get("OutputType");

                    String groupPrefix;
                    if (HDMI.equals(outputType)) {
                        groupPrefix = "SubscriptionVideo#";
                    } else {
                        groupPrefix = "Subscription" + normalizeIOType(outputType) + "#";
                    }
                    if (multicastDetails != null) {
                        String inputDeviceID = multicastDetails.get("DeviceID");

                        if (!streamToSubscriptionDeviceIDs.containsEntry(inputDeviceID+outputType, deviceId)) {
                            streamToSubscriptionDeviceIDs.put(inputDeviceID+outputType, deviceId);
                        }
                        AggregatedDevice inputDevice = aggregatedDevices.get(inputDeviceID);
                        if (inputDevice != null) {
                            deviceProperties.put(groupPrefix + "SubscribedTo", inputDevice.getDeviceName());
                        }
                    }
                    for(Map.Entry<String, String> entry: subscriptionInfo.entrySet()) {
                        deviceProperties.put(groupPrefix + entry.getKey(), entry.getValue());
                    }
                }
            }

            Set<String> streamingStreamTypes = new HashSet<>();

            if (streams != null) {
                for (JsonNode stream : streams) {
                    Map<String, String> streamInfo = new HashMap<>();
                    aggregatedDeviceProcessor.applyProperties(streamInfo, stream, "Stream");

                    if (!STREAMING.equals(streamInfo.get("State"))) {
                        continue;
                    }
                    List<String> sourceOptions = new ArrayList<>();
                    List<String> sourceLabels = new ArrayList<>();
                    String streamConfigAddress = streamInfo.get("ConfigurationAddress");
                    String inputType = streamInfo.get("InputType");
                    String activeSource = "0";
                    String groupPrefix;

                    if (streamInfo.containsKey("Source")) {
                        ArrayNode choices = (ArrayNode) stream.at(CONFIGURATION_SOURCE_CHOICES_PATH);
                        if (choices != null) {
                            for (JsonNode choice: choices) {
                                if (streamInfo.get("Source").equals(choice.at(VALUE_PATH).asText())) {
                                    activeSource = choice.at(VALUE_PATH).asText();
                                    aggregatedDeviceProcessor.applyProperties(streamInfo, choice, "StreamSource");
                                }
                                sourceOptions.add(streamInfo.get("InputType") + ":" + stream.at(INDEX_PATH) + "|" + choice.at(VALUE_PATH));
                                sourceLabels.add(choice.at(DESCRIPTION_PATH).asText());
                            }
                        }
                    }

                    if (HDMI.equals(inputType)) {
                        String nodeRef = SCALER.equals(streamInfo.get("Source")) ? "Scaled" : "Native";
                        groupPrefix = "StreamVideo" + normalizeIOType(nodeRef) + "#";
                    } else {
                        groupPrefix = "Stream" + normalizeIOType(inputType) + "#";
                    }
                    streamingStreamTypes.add(groupPrefix);

                    if (StringUtils.isNotNullOrEmpty(streamConfigAddress) && multicastData.containsKey(streamConfigAddress)) {
                        Map<String, String> multicastDetails = multicastData.get(streamConfigAddress);
                        if (multicastDetails != null) {
                            String multicastDeviceId = multicastDetails.get("DeviceID");
                            Collection<String> outputDeviceIds = streamToSubscriptionDeviceIDs.get(multicastDeviceId + inputType);

                            if (!outputDeviceIds.isEmpty()) {
                                List<String> outputDeviceNames = new ArrayList<>();
                                for(String outputDeviceId: outputDeviceIds) {
                                    AggregatedDevice inputDevice = aggregatedDevices.get(outputDeviceId);
                                    if (inputDevice != null) {
                                        outputDeviceNames.add(inputDevice.getDeviceName());
                                    }
                                }
                                if (!outputDeviceNames.isEmpty()) {
                                    deviceProperties.put(groupPrefix + "StreamingTo", String.join(",", outputDeviceNames));
                                }
                            }
                            if (HDMI_DECODER.equals(streamInfo.get("Source"))) {
                                deviceProperties.put(groupPrefix + "Resolution", deviceProperties.get(VIDEO_RESOLUTION));
                            }
                        }
                    }
                    for (Map.Entry<String, String> entry : streamInfo.entrySet()) {
                        deviceProperties.put(groupPrefix + entry.getKey(), entry.getValue());
                    }

                    if (!sourceOptions.isEmpty()) {
                        device.getControllableProperties().removeIf(advancedControllableProperty -> advancedControllableProperty.getName().equals(groupPrefix + "Source"));
                        advancedControllableProperties.add(createDropdown(groupPrefix + "Source", sourceOptions, sourceLabels, String.format("%s:%s|%s", inputType, stream.at("/index"), activeSource)));
                    }
                }
            }
            if(isTransmitter) {
                processDeviceStreamingStatus(deviceProperties, streamingStreamTypes);
            }
            if (netstat != null) {
                String uptime = netstat.get(UPTIME);
                if (StringUtils.isNotNullOrEmpty(uptime)) {
                    deviceProperties.put(UPTIME, normalizeUptime(Long.parseLong(uptime)));
                }
            }
            createDeviceControls(advancedControllableProperties, deviceProperties);
            device.setProperties(deviceProperties);
            device.setControllableProperties(advancedControllableProperties);
        }
    }

    /**
     * Generate controls based on device nodes information
     *
     * @param node node data of device
     * @param inputNameToPropertyName map containing nodeName:propertyName data
     * @return list of AdvancedControllableProperty
     */
    private List<AdvancedControllableProperty> generateNodeControls(JsonNode node, Map<String, String> inputNameToPropertyName) {
        JsonNode configuration = node.at(CONFIGURATION_PATH);
        ArrayNode inputs = (ArrayNode) node.at(INPUTS_PATH);
        List<AdvancedControllableProperty> controllableProperties = new ArrayList<>();
        String nodeIndex = node.at(INDEX_PATH).asText();
        String nodeType = node.at(TYPE_PATH).asText();
        if (configuration != null && !configuration.isEmpty()) {
            JsonNode confChoicesNode = configuration.at(FUNCTION_CHOICES_PATH);
            if (confChoicesNode != null && confChoicesNode.isArray()) {
                ArrayNode choices = (ArrayNode) confChoicesNode;
                String activeSource = configuration.at(FUNCTION_VALUE_PATH).asText();
                AdvancedControllableProperty controllableProperty = new AdvancedControllableProperty();
                AdvancedControllableProperty.DropDown dropDown = new AdvancedControllableProperty.DropDown();
                List<String> sourceOptions = new ArrayList<>();
                List<String> sourceLabels = new ArrayList<>();
                for (JsonNode choice: choices) {
                    if (activeSource.equals(choice.at(VALUE_PATH).asText())) {
                        controllableProperty.setValue(nodeType + ":" + nodeIndex + "|" + activeSource);
                    }
                    sourceOptions.add(nodeType + ":" + nodeIndex + "|" + choice.at(VALUE_PATH));
                    sourceLabels.add(choice.at(DESCRIPTION_PATH).asText());
                }
                dropDown.setLabels(sourceLabels.toArray(new String[0]));
                dropDown.setOptions(sourceOptions.toArray(new String[0]));
                controllableProperty.setName(LED_FUNCTION);
                controllableProperty.setType(dropDown);
                controllableProperty.setTimestamp(new Date());

                controllableProperties.add(controllableProperty);
            }
        }
        if (inputs != null && inputNameToPropertyName != null) {
            for (JsonNode input: inputs) {
                List<String> sourceOptions = new ArrayList<>();
                List<String> sourceLabels = new ArrayList<>();
                AdvancedControllableProperty controllableProperty = new AdvancedControllableProperty();
                AdvancedControllableProperty.DropDown dropDown = new AdvancedControllableProperty.DropDown();
                String inputName = input.at(NAME_PATH).asText();
                if (inputNameToPropertyName.containsKey(inputName)) {
                    ArrayNode choices = (ArrayNode) input.at(CONFIGURATION_SOURCE_CHOICES_PATH);
                    String activeSource = input.at(CONFIGURATION_SOURCE_VALUE_PATH).asText();
                    String inputIndexName = inputName + ":" + input.at(INDEX_PATH).asText();
                    for (JsonNode choice: choices) {
                        if (activeSource.equals(choice.at(VALUE_PATH).asText())) {
                            controllableProperty.setValue(nodeType + ":" + nodeIndex + "|" + inputIndexName + "|" + activeSource);
                        }
                        sourceOptions.add(nodeType + ":" + nodeIndex + "|" + inputIndexName + "|" + choice.at(VALUE_PATH));
                        sourceLabels.add(choice.at(DESCRIPTION_PATH).asText());
                    }
                    dropDown.setLabels(sourceLabels.toArray(new String[0]));
                    dropDown.setOptions(sourceOptions.toArray(new String[0]));
                    controllableProperty.setName(inputNameToPropertyName.get(inputName));
                    controllableProperty.setType(dropDown);
                    controllableProperty.setTimestamp(new Date());

                    controllableProperties.add(controllableProperty);
                }
            }
        }
        return controllableProperties;
    }

    /**
     * Create property dropdown control
     *
     * @param name property name
     * @param options list of control options
     * @param labels list of option labels
     * @param initialValue initial value to set to the control property
     *
     * @return {@link AdvancedControllableProperty}
     * */
    private AdvancedControllableProperty createDropdown(String name, List<String> options, List<String> labels, String initialValue) {
        AdvancedControllableProperty.DropDown dropDown = new AdvancedControllableProperty.DropDown();
        dropDown.setOptions(options.toArray(new String[0]));
        dropDown.setLabels(labels.toArray(new String[0]));

        return new AdvancedControllableProperty(name, new Date(), dropDown, initialValue);
    }

    /**
     * Process device streaming status
     *
     * @param deviceProperties device properties collected so far
     * @param streamTypes supported stream types, streaming at the moment
     * */
    private void processDeviceStreamingStatus(Map<String, String> deviceProperties, Set<String> streamTypes) {
        deviceProperties.put(NATIVE_VIDEO_STREAM_STATE, STOPPED);
        deviceProperties.put(SCALED_VIDEO_STREAM_STATE, STOPPED);
        deviceProperties.put(HDMI_AUDIO_STREAM_STATE, STOPPED);
        for (String streamType: streamTypes) {
            if(streamType.contains(VIDEO_NATIVE)) {
                deviceProperties.put(NATIVE_VIDEO_STREAM_STATE, STREAMING);
            } else if (streamType.contains(VIDEO_SCALED)) {
                deviceProperties.put(SCALED_VIDEO_STREAM_STATE, STREAMING);
            } else if (streamType.contains(HDMI_AUDIO)) {
                deviceProperties.put(HDMI_AUDIO_STREAM_STATE, STREAMING);
            }
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
     * HDMI -> HDMI
     * HDMI_AUDIO -> HDMIAudio
     * STEREO_AUDIO -> StereoAudio
     * etc.
     * Reserved abbreviations: HDMI, RS232, CEC, USB, HID
     * */
    private String normalizeIOType(String type) {
        if (StringUtils.isNullOrEmpty(type)) {
            return type;
        }
        StringBuilder newTypeName = new StringBuilder();
        for(String sub: type.split("_")) {
            if (RESERVED_IO_TYPES.contains(sub)) {
                newTypeName.append(sub);
                continue;
            }
            String lcsub = sub.toLowerCase();
            newTypeName.append(lcsub.substring(0, 1).toUpperCase());
            newTypeName.append(lcsub.substring(1));
        }
        return newTypeName.toString();
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
