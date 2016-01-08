package com.linkedin.datastream.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.linkedin.datastream.server.providers.CheckpointProvider;
import com.linkedin.datastream.server.api.transport.TransportProvider;

import static org.mockito.Mockito.mock;

/**
 * Tests to validate Message Pool producer
 */
public class TestEventProducerPool {

  private EventProducerPool _eventProducerPool;

  @BeforeTest
  public void setUp() throws Exception {
    CheckpointProvider checkpointProvider = mock(CheckpointProvider.class);
    TransportProvider transportProvider = mock(TransportProvider.class);
    Properties config = new Properties();
    config.put(DatastreamEventProducerImpl.CHECKPOINT_PERIOD_MS, "50");
    _eventProducerPool = new EventProducerPool(checkpointProvider, transportProvider, null, config);
  }

  @Test
  /**
   * Validates if producers are created  when the pool is empty
   */
  public void testEmptyPool() {
    List<DatastreamTask> connectorTasks = new ArrayList<>();
    String connectorType = "connectortype";
    connectorTasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(1)));
    connectorTasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(2)));

    Map<DatastreamTask, DatastreamEventProducer> taskProducerMapConnectorType =
        _eventProducerPool.getEventProducers(connectorTasks, connectorType, false, new ArrayList<>());

    // Number of tasks is same as the number of tasks passed in
    Assert.assertEquals(taskProducerMapConnectorType.size(), 2);

    // All the tasks that were passed in have a corresponding producer
    connectorTasks.forEach(task -> Assert.assertNotNull(taskProducerMapConnectorType.get(task)));

    // The producers are unique for different tasks
    Assert.assertTrue(taskProducerMapConnectorType.get(connectorTasks.get(0)) != taskProducerMapConnectorType
        .get(connectorTasks.get(1)));
  }

  @Test
  /**
   * Validates that producers are not shared across connector types
   */
  public void testProducersNotSharedForDifferentConnectorTypes() {

    // Create tasks for a different connector type
    List<DatastreamTask> connector1tasks = new ArrayList<>();
    List<DatastreamTask> connector2tasks = new ArrayList<>();

    connector1tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(1)));
    connector1tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(2)));
    String connectorType1 = "connectortype1";

    connector2tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(1)));
    connector2tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(2)));
    String connectorType2 = "connectortype2";

    Map<DatastreamTask, DatastreamEventProducer> taskProducerMapConnectorType1 =
        _eventProducerPool.getEventProducers(connector1tasks, connectorType1, false, new ArrayList<>());
    Map<DatastreamTask, DatastreamEventProducer> taskProducerMapConnectorType2 =
        _eventProducerPool.getEventProducers(connector2tasks, connectorType2, false, new ArrayList<>());

    // Check that the producers are not shared
    for (DatastreamEventProducer producer1 : taskProducerMapConnectorType1.values()) {
      for (DatastreamEventProducer producer2 : taskProducerMapConnectorType2.values()) {
        Assert.assertNotEquals(producer1, producer2);
      }
    }
  }

  @Test
  /**
   * Verifies if producer pool reuses producers across multiple calls
   */
  public void testProducerCreationMultipleTimes() {

    List<DatastreamTask> tasks = new ArrayList<>();
    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(1)));
    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(2)));
    String connectorType = "connectorType";

    Map<DatastreamTask, DatastreamEventProducer> taskProducerMap1 =
        _eventProducerPool.getEventProducers(tasks, connectorType, false, new ArrayList<>());

    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(3)));
    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(4)));

    Map<DatastreamTask, DatastreamEventProducer> taskProducerMap2 =
        _eventProducerPool.getEventProducers(tasks, connectorType, false, new ArrayList<>());

    // Check if producers are reused
    Assert.assertTrue(taskProducerMap1.get(tasks.get(0)) == taskProducerMap2.get(tasks.get(0)));
    Assert.assertTrue(taskProducerMap1.get(tasks.get(1)) == taskProducerMap2.get(tasks.get(1)));

    // Check if new producers are generated for the new tasks.
    Set<DatastreamEventProducer> uniqueProducers = new HashSet<>();
    taskProducerMap2.forEach((k, v) -> uniqueProducers.add(v));
    Assert.assertEquals(uniqueProducers.size(), 4);
  }

  @Test
  /**
   * Verify if producers are shared for tasks with same destinations(bootstrap scenario)
   */
  public void testProducerSharedForTasksWithSameDestination() {

    List<DatastreamTask> tasks = new ArrayList<DatastreamTask>();
    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(1)));
    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(2)));
    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(1)));
    String connectorType = "connectorType";

    Map<DatastreamTask, DatastreamEventProducer> taskProducerMap =
        _eventProducerPool.getEventProducers(tasks, connectorType, false, new ArrayList<>());

    // Check if producers are reused
    Assert.assertTrue(taskProducerMap.get(tasks.get(0)) == taskProducerMap.get(tasks.get(2)));
  }

  @Test
  /**
   * Verifies if producer pool correctly returns the unused producers
   */
  public void testUnusedProducers() {

    List<DatastreamTask> tasks = new ArrayList<>();
    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(1)));
    tasks.add(new DatastreamTaskImpl(TestDestinationManager.generateDatastream(2)));
    String connectorType = "connectorType";

    List<DatastreamEventProducer> unusedProducers = new ArrayList<>();
    Map<DatastreamTask, DatastreamEventProducer> taskProducerMap1 =
            _eventProducerPool.getEventProducers(tasks, connectorType, false, unusedProducers);

    // Make sure there is no unused producers at this time
    Assert.assertEquals(unusedProducers.size(), 0);

    tasks.remove(1);

    Map<DatastreamTask, DatastreamEventProducer> taskProducerMap2 =
            _eventProducerPool.getEventProducers(tasks, connectorType, false, unusedProducers);

    // Check if producer for tasks[0] is reused
    Assert.assertTrue(taskProducerMap1.get(tasks.get(0)) == taskProducerMap2.get(tasks.get(0)));

    // Make sure there is exactly one unused producer
    Assert.assertEquals(unusedProducers.size(), 1);
  }
}
