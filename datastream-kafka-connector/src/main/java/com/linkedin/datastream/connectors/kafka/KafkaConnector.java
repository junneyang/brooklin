package com.linkedin.datastream.connectors.kafka;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamMetadataConstants;
import com.linkedin.datastream.common.DatastreamSource;
import com.linkedin.datastream.common.ThreadUtils;
import com.linkedin.datastream.metrics.BrooklinMetricInfo;
import com.linkedin.datastream.server.DatastreamTask;
import com.linkedin.datastream.server.api.connector.Connector;
import com.linkedin.datastream.server.api.connector.DatastreamValidationException;


public class KafkaConnector implements Connector {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaConnector.class);

  public static final String CONNECTOR_NAME = "kafka";
  public static final String CONFIG_COMMIT_INTERVAL_MILLIS = "commitIntervalMs";
  public static final String CONFIG_CONSUMER_FACTORY_CLASS = "consumerFactoryClassName";
  public static final String CONFIG_WHITE_LISTED_CLUSTERS = "whiteListedClusters";
  private final KafkaConsumerFactory<?, ?> _consumerFactory;
  private final Properties _consumerProps;
  private final Set<KafkaBrokerAddress> _whiteListedBrokers;

  public KafkaConnector(String name, long commitIntervalMillis, KafkaConsumerFactory<?, ?> kafkaConsumerFactory,
      Properties kafkaConsumerProps, List<KafkaBrokerAddress> whitelistedBrokers) {
    _consumerFactory = kafkaConsumerFactory;
    _consumerProps = kafkaConsumerProps;
    _name = name;
    _commitIntervalMillis = commitIntervalMillis;
    _whiteListedBrokers = new HashSet<>(whitelistedBrokers);
  }

  private final String _name;
  private final long _commitIntervalMillis;
  private final ExecutorService _executor = Executors.newCachedThreadPool(new ThreadFactory() {
    private AtomicInteger threadCounter = new AtomicInteger(0);

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r);
      t.setDaemon(true);
      t.setName(_name + " worker thread " + threadCounter.incrementAndGet());
      t.setUncaughtExceptionHandler((thread, e) -> {
        LOG.error("thread " + thread.getName() + " has died due to uncaught exception", e);
      });
      return t;
    }
  });
  private final ConcurrentHashMap<DatastreamTask, KafkaConnectorTask> _runningTasks = new ConcurrentHashMap<>();

  @Override
  public void start() {
    //nop
  }

  @Override
  public void stop() {
    _runningTasks.values().forEach(KafkaConnectorTask::stop);
    //TODO - should wait?
    _runningTasks.clear();
    if (!ThreadUtils.shutdownExecutor(_executor, Duration.of(5, ChronoUnit.SECONDS), LOG)) {
      LOG.warn("Failed to shut down cleanly.");
    }
    LOG.info("stopped.");
  }

  @Override
  public void onAssignmentChange(List<DatastreamTask> tasks) {
    LOG.info("onAssignmentChange called with tasks {}", tasks);

    HashSet<DatastreamTask> toCancel = new HashSet<>(_runningTasks.keySet());
    toCancel.removeAll(tasks);

    for (DatastreamTask task : toCancel) {
      KafkaConnectorTask connectorTask = _runningTasks.remove(task);
      connectorTask.stop();
    }

    for (DatastreamTask task : tasks) {
      if (_runningTasks.containsKey(task)) {
        continue; //already running
      }
      LOG.info("creating task for {}.", task);
      KafkaConnectorTask connectorTask =
          new KafkaConnectorTask(_consumerFactory, _consumerProps, task, _commitIntervalMillis);
      _runningTasks.put(task, connectorTask);
      _executor.submit(connectorTask);
    }
  }

  @Override
  public List<BrooklinMetricInfo> getMetricInfos() {
    return Collections.unmodifiableList(KafkaConnectorTask.getMetricInfos());
  }

  @Override
  public void initializeDatastream(Datastream stream, List<Datastream> allDatastreams)
      throws DatastreamValidationException {
    LOG.info("Initialize datastream {}", stream);
    DatastreamSource source = stream.getSource();
    String connectionString = source.getConnectionString();

    //TODO - better validation and canonicalization
    //its possible to list the same broker as a hostname or IP
    //(kafka://localhost:666 vs kafka://127.0.0.1:666 vs kafka://::1:666/topic)
    //the "best" thing to do would be connect to _ALL_ brokers listed, and from each broker
    //get the cluster members and the cluster unique ID (which only exists in kafka ~0.10+)
    //and then:
    //1. fail if brokers listed are member of different clusters
    //2. "normalize" the connection string to be either all members as they appear in metadata
    //   or have the cluster unique ID somehow
    try {
      KafkaConnectionString parsed = KafkaConnectionString.valueOf(connectionString);
      source.setConnectionString(parsed.toString()); //ordered now

      if (!isWhiteListedCluster(parsed)) {
        String msg =
            String.format("Kafka connector is not white-listed for the cluster %s. Current white-listed clusters %s.",
                connectionString, _whiteListedBrokers);
        LOG.error(msg);
        throw new DatastreamValidationException(msg);
      }
      try (Consumer<?, ?> consumer = KafkaConnectorTask.createConsumer(_consumerFactory, _consumerProps,
          "partitionFinder", parsed)) {
        int numPartitions = consumer.partitionsFor(parsed.getTopicName()).size();
        if (!source.hasPartitions()) {
          LOG.info("Kafka source {} has {} partitions.", parsed, numPartitions);
          source.setPartitions(numPartitions);
        } else {
          if (source.getPartitions() != numPartitions) {
            String msg =
                String.format("Source is configured with %d partitions, But the topic %s actually has %d partitions",
                    source.getPartitions(), parsed.getTopicName(), numPartitions);
            LOG.error(msg);
            throw new DatastreamValidationException(msg);
          }
        }

        // Try to see if the start position requested is indeed possible to seek to.
        if (stream.getMetadata().containsKey(DatastreamMetadataConstants.START_POSITION)) {
          long ts = Long.parseLong(stream.getMetadata().get(DatastreamMetadataConstants.START_POSITION));
          Map<TopicPartition, Long> topicTimestamps = IntStream.range(0, source.getPartitions())
              .mapToObj(x -> new TopicPartition(parsed.getTopicName(), x))
              .collect(Collectors.toMap(Function.identity(), x -> ts));
          Map<TopicPartition, OffsetAndTimestamp> offsets = consumer.offsetsForTimes(topicTimestamps);

          LOG.info("Returned offsets {} for timestamp {}", offsets, ts);
          if (offsets.values().stream().anyMatch(x -> x == null)) {
            String msg = String.format(
                "Datastream is configured to start from a timestamp %d. But kafka is not able to map the timestamp to an offset {%s}",
                ts, offsets);
            LOG.warn(msg);
            throw new DatastreamValidationException(msg);
          }
        }
      }
    } catch (Exception e) {
      LOG.error("Initialization threw an exception.", e);
      throw new DatastreamValidationException(e);
    }
  }

  private Boolean isWhiteListedCluster(KafkaConnectionString connectionStr) {
    return _whiteListedBrokers.isEmpty() || connectionStr.getBrokers().stream().anyMatch(_whiteListedBrokers::contains);
  }
}