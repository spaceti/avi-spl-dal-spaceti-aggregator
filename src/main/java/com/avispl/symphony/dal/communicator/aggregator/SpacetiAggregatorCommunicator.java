package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.Device;
import com.avispl.symphony.api.dal.Version;
import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.api.dal.ping.Pingable;
import com.avispl.symphony.dal.communicator.RestCommunicator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;


public class SpacetiAggregatorCommunicator extends RestCommunicator implements Pingable, Aggregator
{
    private List<AggregatedDevice> devices = new ArrayList<>();

    public static void main( String[] args )
    {
        System.out.println( "Hello World!" );
    }

    // public void destroy() {
    //     // this method is called upon a Symphony shutdown
    //     // here all persistent resources, sockets have to be released
    //     System.out.printf("Destroying aggregator instance " + SpacetiAggregatorCommunicator.class);
    // }

    public boolean isInitialized() {
        // has to return true once aggregator is fully initialized
        return true;
    }

    // public void init() throws Exception {
    //     // this method is called after instance is created and
    //     // JavaBean properties are set
    //     System.out.printf("Initializing aggregator instance " + SpacetiAggregatorCommunicator.class);

    //     devices.addAll(Arrays.asList(
    //             ConfigAggregatedDevice.createDevice("Lights"),
    //             ConfigAggregatedDevice.createDevice("Projector"),
    //             ConfigAggregatedDevice.createDevice("Touchscreen")));
    // }

    public Version retrieveSoftwareVersion() throws Exception {
        // version of software / firmware target aggregator is running
        return new Version("1.12.3");
    }

    public String getAddress() {
        // IP address / hostname of the aggregator
        return "10.0.0.3";
    }

    public int getPingTimeout() {
        // ping timeout value
        return 60;
    }

    public int ping() throws Exception {
        // has to perform ping and return ping latency to a target device
        return new Random().nextInt(40);
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {

        // Device aggregator has to return statistics for all devices it aggregates
        // see javadoc for com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice for all that needs to be returned
        // Code in this sample constructs dummy instances of AggregatedDevice with fake data
        // In real adapter following information needs to be retrieved from the target device and mapped to AggregatedDevice instance
        // using remote network calls

        return Collections.unmodifiableList(devices);
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> list) throws Exception {
        // same as retrieveMultipleStatistics(), but just for given device identifiers
        return retrieveMultipleStatistics().stream()
                .filter(list::contains)
                .collect(Collectors.toList());
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


}
