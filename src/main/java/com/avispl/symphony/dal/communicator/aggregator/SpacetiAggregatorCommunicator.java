package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.avispl.symphony.dal.util.StringUtils;

import org.apache.commons.lang3.tuple.Pair;
import java.util.stream.Collectors;

import javax.security.auth.login.FailedLoginException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;


public class SpacetiAggregatorCommunicator extends RestCommunicator implements Aggregator, Monitorable
{
    /**
     * Build an instance of SpacetiAggregatorCommunicator
     * Setup aggregated devices processor, initialize adapter properties
     *
     * @throws IOException if unable to locate mapping yml file or properties file
     */
    public SpacetiAggregatorCommunicator() throws IOException {
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
    }

    class SpacetiDeviceDataLoader implements Runnable {
        private volatile boolean inProgress;

        public SpacetiDeviceDataLoader() {
            inProgress = true;
        }

        @Override
        public void run() {
            mainloop:
            while (inProgress) {
                logger.debug("Main loop");
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    // Ignore for now
                }

                if (!inProgress) {
                    break mainloop;
                }

                // next line will determine whether Spaceti monitoring was paused
                updateAggregatorStatus();
                if (devicePaused) {
                    continue mainloop;
                }

                try {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Fetching devices list");
                    }
                    fetchDevicesList();
                    //knownErrors.remove(ROOMS_LIST_RETRIEVAL_ERROR_KEY);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Fetched devices list: " + aggregatedDevices);
                    }
                } catch (Exception e) {
                    //knownErrors.put(ROOMS_LIST_RETRIEVAL_ERROR_KEY, limitErrorMessageByLength(e.getMessage(), maxErrorLength));
                    logger.error("Error occurred during device list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage(), e);
                }

                if (!inProgress) {
                    break mainloop;
                }

                int aggregatedDevicesCount = aggregatedDevices.size();
                if (aggregatedDevicesCount == 0) {
                    continue mainloop;
                }

                while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException e) {
                        //
                    }
                }

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


    private static final String SPACETI_DEVICES_URL = "v2/devices";
    /**
     * API Token used for authorization in Spaceti API
     */
    private volatile String authorizationToken;
    private ConcurrentHashMap<String, AggregatedDevice> aggregatedDevices = new ConcurrentHashMap<>();
    /**
     * Device adapter instantiation timestamp.
     */
    private long adapterInitializationTimestamp;
    /**
     * Executor that runs all the async operations, that {@link #deviceDataLoader} is posting and
     * {@link #devicesExecutionPool} is keeping track of
     */
    private static ExecutorService executorService;
    /**
     * Runner service responsible for collecting data and posting processes to {@link #devicesExecutionPool}
     */
    private SpacetiDeviceDataLoader deviceDataLoader;
    /**
     * Time period within which the device metadata (basic devices information) cannot be refreshed.
     * Ignored if device list is not yet retrieved or the cached device list is empty {@link SpacetiAggregatorCommunicator#aggregatedDevices}
     */
    private volatile long validDeviceMetaDataRetrievalPeriodTimestamp;
    /**
     * Whether service is running.
     */
    private volatile boolean serviceRunning;
    /**
     * Time period within which the device metrics (dynamic information) cannot be refreshed.
     * Ignored if metrics data is not yet retrieved
     */
    private volatile long validMetricsDataRetrievalPeriodTimestamp;
    private AggregatedDeviceProcessor aggregatedDeviceProcessor;
    /**
     * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
     * new devices statistics loop will be launched before the next monitoring iteration. To avoid that -
     * this variable stores a timestamp which validates it, so when the devices statistics is done collecting, variable
     * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
     * {@link #aggregatedDevices} resets it to the currentTime timestamp, which will re-activate data collection.
     */
    private static long nextDevicesCollectionIterationTimestamp;
    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link ZoomRoomsAggregatorCommunicator#retrieveMultipleStatistics()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;
    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
     */
    private volatile long validRetrieveStatisticsTimestamp;
    /**
     * Aggregator inactivity timeout. If the {@link ZoomRoomsAggregatorCommunicator#retrieveMultipleStatistics()}  method is not
     * called during this period of time - device is considered to be paused, thus the Cloud API
     * is not supposed to be called
     */
    private static final long retrieveStatisticsTimeOut = 3 * 60 * 1000;
    /**
     * Adapter metadata, collected from the version.properties
     */
    private Properties adapterProperties;


    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {
        // Device aggregator has to return statistics for all devices it aggregates
        // see javadoc for com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice for all that needs to be returned
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Adapter initialized: %s, executorService exists: %s, serviceRunning: %s", isInitialized(), executorService != null, serviceRunning));
        }
        if (executorService == null) {
            // Due to the bug that after changing properties on fly - the adapter is destroyed but adapter is not initialized properly,
            // so executor service is not running. We need to make sure executorService exists
            executorService = Executors.newFixedThreadPool(8);
            executorService.submit(deviceDataLoader = new SpacetiDeviceDataLoader());
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
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> list) throws Exception {
        // same as retrieveMultipleStatistics(), but just for given device identifiers
        return retrieveMultipleStatistics().stream()
                .filter(aggregatedDevice -> list.contains(aggregatedDevice.getDeviceId()))
                .collect(Collectors.toList());
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        Map<String, String> statistics = new HashMap<>();
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();

        statistics.put("AdapterVersion", adapterProperties.getProperty("mock.aggregator.version"));
        statistics.put("AdapterBuildDate", adapterProperties.getProperty("mock.aggregator.build.date"));
        statistics.put("AdapterUptime", normalizeUptime((System.currentTimeMillis() - adapterInitializationTimestamp) / 1000));

        extendedStatistics.setStatistics(statistics);
        return Collections.singletonList(extendedStatistics);
    }


    /**
     * {@inheritDoc}
     *
     * JWT authentication type does not need any specific method to authenticate since it's based on a static jwt.
     * OAuth authentication has a separate process based on clientId/clientSecret and accountId.
     */
    @Override
    protected void authenticate() throws Exception {
    }

    private void fetchDevicesList() throws Exception {
        long currentTimestamp = System.currentTimeMillis();
        if (logger.isDebugEnabled()) {
            logger.debug("Fetching devices list");
        }

        List<AggregatedDevice> spacetiDevices = new ArrayList<>();
        try {
            processPaginatedSpacetiRetrieval(spacetiDevices);
        }
        catch (Exception e) {
            throw new RuntimeException("Error occurred during devices list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage(), e);
        }

        List<String> retrievedDeviceIds = new ArrayList<>();
        spacetiDevices.forEach(aggregatedDevice -> {
            String deviceId = aggregatedDevice.getDeviceId();
            retrievedDeviceIds.add(deviceId);
            if (aggregatedDevices.containsKey(deviceId)) {
                aggregatedDevices.get(deviceId).setDeviceOnline(aggregatedDevice.getDeviceOnline());
                aggregatedDevices.get(deviceId).setProperties(aggregatedDevice.getProperties());
            } else {
                aggregatedDevices.put(deviceId, aggregatedDevice);
            }
        });
        // Remove devices that were not populated by the API
        aggregatedDevices.keySet().removeIf(existingDevice -> !retrievedDeviceIds.contains(existingDevice));

        if (spacetiDevices.isEmpty()) {
            // If all the devices were not populated for any specific reason (no devices available, filtering, etc)
            aggregatedDevices.clear();
        }

        nextDevicesCollectionIterationTimestamp = System.currentTimeMillis();
    }

    /**
     * Retrieve Spaceti devices with support of the Spaceti API pagination (next page token)
     *
     * @param spacetiDevices to save all retrieved devices to
     * @throws Exception if any communication error occurs
     * */
    private void processPaginatedSpacetiRetrieval(List<AggregatedDevice> spacetiDevices) throws Exception {
        boolean hasNextPage = true;
        String nextPageURL = SPACETI_DEVICES_URL;
        Pair<JsonNode, String> response;
        while(hasNextPage) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Receiving page with URL: %s", nextPageURL));
            }
            response = retrieveSpacetiDevices(nextPageURL);
            nextPageURL = response.getRight();
            hasNextPage = StringUtils.isNotNullOrEmpty(nextPageURL);
            spacetiDevices.addAll(aggregatedDeviceProcessor.extractDevices(response.getLeft()));
        }
    }

    /**
     * Retrieve list of Spaceti devices available
     *
     * @param nextPageURL token to reference the next URL to retrieve
     * @return response pair of JsonNode and next_url
     * @throws Exception if a communication error occurs
     */
    private Pair<JsonNode, String> retrieveSpacetiDevices(String nextPageURL) throws Exception {
        JsonNode response = doGet(nextPageURL, JsonNode.class);
        
        if (response == null) {
            return Pair.of(null, null);
        }

        if (!response.at("/next").isNull()) {
            String[] nextUrlSplit = response.at("/next").asText().split("/");
            String nextUrl = String.join("/", Arrays.copyOfRange(nextUrlSplit, 3, nextUrlSplit.length));
            return Pair.of(response, nextUrl);
        }
        
        return Pair.of(response, null);
    }
     
    /**
     * {@inheritDoc}
     */
    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        headers.add("Content-Type", "application/json");
        headers.add("Authorization", "Bearer " + authorizationToken);
        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    protected void internalInit() throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Internal init is called.");
        }
        adapterInitializationTimestamp = System.currentTimeMillis();
        authorizationToken = getPassword();

        executorService = Executors.newFixedThreadPool(8);
        executorService.submit(deviceDataLoader = new SpacetiDeviceDataLoader());

        long currentTimestamp = System.currentTimeMillis();
        validDeviceMetaDataRetrievalPeriodTimestamp = currentTimestamp;
        validMetricsDataRetrievalPeriodTimestamp = currentTimestamp;
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

        // devicesExecutionPool.forEach(future -> future.cancel(true));
        // devicesExecutionPool.clear();

        aggregatedDevices.clear();
        super.internalDestroy();
    }  

    /**
     * Update the status of the device.
     * The device is considered as paused if did not receive any retrieveMultipleStatistics()
     * calls during {@link ZoomRoomsAggregatorCommunicator#validRetrieveStatisticsTimestamp}
     */
    private synchronized void updateAggregatorStatus() {
        devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
    }
    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAggregatorStatus();
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
}
