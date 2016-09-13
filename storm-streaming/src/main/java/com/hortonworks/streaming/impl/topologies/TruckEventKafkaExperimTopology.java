//Previous import packages from TruckEventKafkatopology
package com.hortonworks.streaming.impl.topologies;

import com.hortonworks.streaming.impl.bolts.common.EventTypeStream;
import org.apache.nifi.remote.client.SiteToSiteClient;
import org.apache.nifi.remote.client.SiteToSiteClientConfig;
import org.apache.nifi.storm.NiFiBolt;
import org.apache.nifi.storm.NiFiDataPacketBuilder;
import org.apache.nifi.storm.NiFiDataPacket;

import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.spout.SchemeAsMultiScheme;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import com.hortonworks.streaming.impl.bolts.*;
import com.hortonworks.streaming.impl.bolts.hdfs.FileTimeRotationPolicy;
import com.hortonworks.streaming.impl.bolts.hive.HiveTablePartitionAction;
import com.hortonworks.streaming.impl.kafka.TruckScheme2;
import org.apache.log4j.Logger;
import org.apache.storm.hdfs.bolt.HdfsBolt;
import org.apache.storm.hdfs.bolt.format.DefaultFileNameFormat;
import org.apache.storm.hdfs.bolt.format.DelimitedRecordFormat;
import org.apache.storm.hdfs.bolt.format.FileNameFormat;
import org.apache.storm.hdfs.bolt.format.RecordFormat;
import org.apache.storm.hdfs.bolt.sync.CountSyncPolicy;
import org.apache.storm.hdfs.bolt.sync.SyncPolicy;
import org.apache.storm.kafka.BrokerHosts;
import org.apache.storm.kafka.KafkaSpout;
import org.apache.storm.kafka.SpoutConfig;
import org.apache.storm.kafka.ZkHosts;

import org.apache.storm.hbase.bolt.HBaseBolt;
import org.apache.storm.hbase.bolt.mapper.SimpleHBaseMapper;
import org.apache.storm.hbase.security.HBaseSecurityUtil;

import java.util.HashMap;
import java.util.Map;

import java.io.Serializable;

/**
 * Pulls data from Kafka Spout named 'kafkaSpout', Route Bolt breaks the data into multiple fields, then parses the data
 * to NiFi Input Port named 'Data from Storm', 2 Hbase Bolts and Count Bolt. Data from Count Bolt is passed to the 3rd
 * HBase bolt in which the incidents are counted per driver.
 */

public class TruckEventKafkaExperimTopology extends BaseTruckEventTopology {
    private static final Logger LOG = Logger.getLogger(TruckEventKafkaExperimTopology.class);

    //HBase table names and column families
    private static final String DANGEROUS_EVENTS_TABLE_NAME = "driver_dangerous_events";
    private static final String EVENTS_TABLE_COLUMN_FAMILY_NAME = "events";

    private static final String EVENTS_TABLE_NAME = "driver_events";
    private static final String ALL_EVENTS_TABLE_COLUMN_FAMILY_NAME = "allevents";

    private static final String EVENTS_COUNT_TABLE_NAME = "driver_dangerous_events_count";
    private static final String EVENTS_COUNT_TABLE_COLUMN_FAMILY_NAME = "counters";

    //HBase RowKey
    private static final String HBASE_ROW_KEY = "hbaseRowKey";

    //Spout and bolt names
    private static final String ROUTE_BOLT = "ROUTE_BOLT";
    private static final String COUNT_BOLT = "COUNT_BOLT";
    private static final String HBASE_BOLT_DANGEROUS_EVENTS = "hbaseDangerousEvents";
    private static final String HBASE_BOLT_DRIVER_INCIDENT_COUNT = "hbaseDangerousEventsCount";
    private static final String HBASE_BOLT_ALL_EVENTS = "hbaseAllDriverEvents";

    public TruckEventKafkaExperimTopology(String configFileLocation) throws Exception {
        super(configFileLocation);
    }

    public static void main(String[] args) throws Exception {
        String configFileLocation = args[0];

        // kafkaspout ==> RouteBolt-writes to one hbase table
        TruckEventKafkaExperimTopology truckTopology = new TruckEventKafkaExperimTopology(configFileLocation);
        truckTopology.buildAndSubmit();
    }

    public void buildAndSubmit() throws Exception {

        TopologyBuilder builder = new TopologyBuilder();

	/* This config is for Storm and it needs be configured with things like the following:
         * 	Zookeeper server, nimbus server, ports, etc... All of this configuration will be picked up
		 * in the ~/.storm/storm.yaml file that will be located on each storm node.
		 */
        Config config = new Config();
        config.setDebug(true);

        Map<String, Object> hbaseConf = new HashMap<String, Object>();
        config.put("hbase.conf", hbaseConf);

        /* Set the number of workers that will be spun up for this topology.
         * Each worker represents a JVM where executor thread will be spawned from */
        Integer topologyWorkers = Integer.valueOf(topologyConfig.getProperty("storm.trucker.topology.workers"));
        config.put(Config.TOPOLOGY_WORKERS, topologyWorkers);

        //Read the nimbus host in from the config file as well
        String nimbusHost = topologyConfig.getProperty("nimbus.host");
        config.put(Config.NIMBUS_HOST, nimbusHost);


        // Set up Kafka Spout to ingest from
        configureKafkaSpout(builder);

        // Set up RouteBolt to break the messages from KafkaSpout into multiple parameters
        configureRouteBolt(builder);

        //Set up NiFi Bolt to send tuples back to NiFi
        configureNiFiBolt(builder);

        //Set up CountBolt to count incidents per driver
        configureCountBolt(builder);

        // Set up 3 HBaseBolts to write to HBase tables
        //driver_dangerous_events, driver_events, data received from RouteBolt
        //driver_dangerous_events_count stores incidents per driver, data received from CountBolt
        configureHBaseBolt(builder);

        //TODO: Create HDFS Bolt
        //configureHdfsBolt(builder);

        //TODO: Create Hive Bolt
        //configureHiveBolt(builder);

        //Try to submit topology
        try {
            StormSubmitter.submitTopology("truck-event-processor", config, builder.createTopology());
        } catch (Exception e) {
            LOG.error("Error submiting Topology", e);
        }

    }

    /* Set up Kafka Spout to ingest data from simulator */
    public int configureKafkaSpout(TopologyBuilder builder) {
        KafkaSpout kafkaSpout = constructKafkaSpout();

        int spoutCount = Integer.valueOf(topologyConfig.getProperty("spout.thread.count"));
        int boltCount = Integer.valueOf(topologyConfig.getProperty("bolt.thread.count"));

        builder.setSpout("kafkaSpout", kafkaSpout, spoutCount);
        return boltCount;
    }


    private KafkaSpout constructKafkaSpout() {
        KafkaSpout kafkaSpout = new KafkaSpout(constructKafkaSpoutConf());
        return kafkaSpout;
    }

    private SpoutConfig constructKafkaSpoutConf() {
        BrokerHosts hosts = new ZkHosts(topologyConfig.getProperty("kafka.zookeeper.host.port"));
        String topic = topologyConfig.getProperty("kafka.topic");
        String zkRoot = topologyConfig.getProperty("kafka.zkRoot");
        String consumerGroupId = topologyConfig.getProperty("kafka.consumer.group.id");

        SpoutConfig spoutConfig = new SpoutConfig(hosts, topic, zkRoot, consumerGroupId);

		/* Custom TruckScheme that will take Kafka message of single truckEvent
		 * and emit a 2-tuple consisting of truckId and truckEvent. This driverId
		 * is required to do a fieldsSorting so that all driver events are sent to the set of bolts */
        spoutConfig.scheme = new SchemeAsMultiScheme(new TruckScheme2());

        return spoutConfig;
    }

    public void configureRouteBolt(TopologyBuilder builder) {
        RouteBolt routeBolt = new RouteBolt(true);
        //Defines new bolt in topology
        builder.setBolt(ROUTE_BOLT, routeBolt, 2).shuffleGrouping("kafkaSpout");
    }

    //NiFi Bolt Pushes 1 tuple from Storm back into NiFi every 4 seconds
    private void configureNiFiBolt(TopologyBuilder builder) {
        //Build a Site-To-Site client config for pushing data
        final SiteToSiteClientConfig outputConfig = new SiteToSiteClient.Builder()
                .url("http://localhost:9090/nifi")
                .portName("Data from Storm")
                .buildConfig();

        final int tickFrequencySeconds = 5;
        final NiFiDataPacketBuilder niFiDataPacketBuilder = new SimpleNiFiDataPacketBuilder();
        final NiFiBolt niFiBolt = new NiFiBolt(outputConfig, niFiDataPacketBuilder, tickFrequencySeconds)
                .withBatchSize(1)
                .withBatchInterval(4);
                ;

        builder.setBolt("nifiOutput", niFiBolt).fieldsGrouping(ROUTE_BOLT, getFields());
    }

     //Simple builder that returns the incoming data packet.
    static class SimpleNiFiDataPacketBuilder implements NiFiDataPacketBuilder, Serializable {

        private static final long serialVersionUID = 3067274587595578836L;

        @Override
        public NiFiDataPacket createNiFiDataPacket(Tuple tuple) {
            return (NiFiDataPacket) tuple.getValues();
        }
    }

    public void configureCountBolt(TopologyBuilder builder) {
        //count bolt should listen in on my routeBolt
        DriverIncidentCount countBolt = new DriverIncidentCount();
        builder.setBolt(COUNT_BOLT, countBolt, 2).shuffleGrouping(ROUTE_BOLT, EventTypeStream.NOT_NORMAL.getStream());
    }

    public void configureHBaseBolt(TopologyBuilder builder) {
        try {
            setAllEventsBolt(builder);

            setNotNormalEventsBolt(builder);

        } catch (Exception e) {
            LOG.error("Error inserting violation event into HBase table", e);
        }
    }

    private void setAllEventsBolt(TopologyBuilder builder) {
        //Store the all events in HBase DriverEventsTable
        HBaseBolt hbaseDriverEventsTable = new HBaseBolt(EVENTS_TABLE_NAME, getMapperDriverEventsTable())
                .withConfigKey("hbase.conf");

        builder.setBolt(HBASE_BOLT_ALL_EVENTS, hbaseDriverEventsTable, 2).fieldsGrouping(ROUTE_BOLT, getFields());
    }

    private SimpleHBaseMapper getMapperDriverEventsTable() {
        return new SimpleHBaseMapper()
                .withRowKeyField(HBASE_ROW_KEY)
                .withColumnFields(getFields())
                .withColumnFamily(ALL_EVENTS_TABLE_COLUMN_FAMILY_NAME);
    }

    private void setNotNormalEventsBolt(TopologyBuilder builder) {
        System.out.println("Stream ID: " + EventTypeStream.NOT_NORMAL.getStream());
        //Store incident events into HBase Table driver_dangerous_events
        final HBaseBolt hbaseDriverDangerousEventsTable =
                new HBaseBolt(DANGEROUS_EVENTS_TABLE_NAME, getMapperDangerousEventsTable()).withConfigKey("hbase.conf");
        builder.setBolt(HBASE_BOLT_DANGEROUS_EVENTS, hbaseDriverDangerousEventsTable, 2)
                .fieldsGrouping(ROUTE_BOLT, EventTypeStream.NOT_NORMAL.getStream(), getFields());

        //Update the running count of all incidents for driver_dangerous_events_count HBase Table,
        // should listen in on count bolt
        final HBaseBolt hbaseEventsCountTable =
                new HBaseBolt(EVENTS_COUNT_TABLE_NAME, getMapperEventsCountTable()).withConfigKey("hbase.conf");
        builder.setBolt(HBASE_BOLT_DRIVER_INCIDENT_COUNT, hbaseEventsCountTable, 2)
                .fieldsGrouping(COUNT_BOLT, getDriverIncidentFields());
    }

    private SimpleHBaseMapper getMapperEventsCountTable() {
        return new SimpleHBaseMapper()
                .withRowKeyField("driverId")
                .withColumnFields(getDriverId())
                .withCounterFields(getIncidentCountPerDriver())
                .withColumnFamily(EVENTS_COUNT_TABLE_COLUMN_FAMILY_NAME);
    }

    private SimpleHBaseMapper getMapperDangerousEventsTable() {
        return new SimpleHBaseMapper()
                .withRowKeyField(HBASE_ROW_KEY)
                .withColumnFields(getFields())
                .withColumnFamily(EVENTS_TABLE_COLUMN_FAMILY_NAME);
    }

    // TODO: Create public static fields for strings

    // TODO: Schould probably be in a global place to be called by all the bolts
    private Fields getFields() {
        return new Fields("driverId", "truckId", "ts", "eventType", "latitude", "longitude", "driverName", "routeId", "routeName", "hbaseRowKey");
    }

    private Fields getDriverId() {
        return new Fields("driverId");
    }

    private Fields getIncidentCountPerDriver() {
        return new Fields("incidentTotalCount");
    }

    //removed "incidentTotalCount" from Fields because we are going to count the incidents per driverId
    private Fields getDriverIncidentFields() {
        return new Fields("driverId");
    }

    //TODO: Create Hdfs Bolt Definition
    //TODO: Refer to how bolt was built in previous topology https://github.com/james94/iot-truck-streaming/blob/hdp25experiment/storm-streaming/src/main/java/com/hortonworks/streaming/impl/topologies/TruckEventProcessorKafkaTopology.java
    //TODO: Refer to http://storm.apache.org/releases/1.0.1/storm-hdfs.html
    //private void configureHdfsBolt(TopologyBuilder builder) {}

    //TODO: Create Hive Bolt Definition
    //TODO: Refer to how bolt was built in previous topology, Hive and HDFS were merged
    //TODO: Refer to http://storm.apache.org/releases/1.0.1/storm-hive.html
    //configureHiveBolt(builder);
}
