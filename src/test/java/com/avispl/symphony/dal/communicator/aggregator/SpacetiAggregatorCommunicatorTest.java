package com.avispl.symphony.dal.communicator.aggregator;

import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/**
 * Unit test for simple App.
 */
@Tag("test")
public class SpacetiAggregatorCommunicatorTest 
{
    static SpacetiAggregatorCommunicator mockAggregatorCommunicator;

    @BeforeEach
    public void init() throws Exception {
        mockAggregatorCommunicator = new SpacetiAggregatorCommunicator();
        mockAggregatorCommunicator.setPassword(System.getenv("API2_TOKEN_SPACETIHQ"));
        mockAggregatorCommunicator.setHost("api.spaceti.net");
        mockAggregatorCommunicator.setProtocol("https");
        mockAggregatorCommunicator.setPort(443);
    }

    @Test
    public void getDevicesWithFilteringTest() throws Exception {
        mockAggregatorCommunicator.init();
        mockAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(5000);
        List<AggregatedDevice> devices = mockAggregatorCommunicator.retrieveMultipleStatistics();
        Assert.assertFalse(devices.isEmpty());
        Assert.assertEquals(50, devices.size());
        Assert.assertNotNull(devices.get(0).getSerialNumber());
    }

    @Test
    public void pingTest() throws Exception {
        mockAggregatorCommunicator.init();
        int pingLatency = mockAggregatorCommunicator.ping();
        Assert.assertNotEquals(0, pingLatency);
        System.out.println("Ping latency calculated: " + pingLatency);
    }
}
