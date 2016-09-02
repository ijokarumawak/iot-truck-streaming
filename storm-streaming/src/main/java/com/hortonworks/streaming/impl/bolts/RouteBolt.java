package com.hortonworks.streaming.impl.bolts;

import com.hortonworks.streaming.impl.bolts.common.EventType;
import com.hortonworks.streaming.impl.bolts.common.EventTypeStream;
import com.hortonworks.streaming.impl.topologies.TruckEventKafkaExperimTopology;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;

import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

import java.sql.Timestamp;

//new imported packages for TruckHBaseBolt
import org.apache.storm.utils.BatchHelper;
import org.apache.storm.utils.TupleUtils;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.storm.hbase.bolt.mapper.HBaseMapper;
import org.apache.storm.hbase.security.HBaseSecurityUtil;
import org.apache.storm.hbase.common.ColumnList;

import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;

/**
 * Basic bolt for writing to HBase.
 * <p>
 * Note: Each HBaseBolt defined in Topology is tied to a specific table.
 */

public class RouteBolt extends BaseRichBolt {

    private static final Logger LOG = Logger.getLogger(RouteBolt.class);
    private static final byte[] INCIDENT_RUNNING_TOTAL_COLUMN = Bytes.toBytes("incidentRunningTotal");
    private static final long serialVersionUID = 2946379346389650318L;

    //three HBase table names with their associated column family names

    private static final String EVENTS_COUNT_TABLE_NAME = "driver_dangerous_events_count";
    private static final String EVENTS_COUNT_TABLE_COLUMN_FAMILY_NAME = "counters";

    private OutputCollector outputCollector;
    private boolean persistAllEvents;

    public RouteBolt(Boolean persistAllEvents) {
        this.persistAllEvents = persistAllEvents;
    }


    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        LOG.info("The PersistAllEvents Flag is set to: " + persistAllEvents);
        this.outputCollector = outputCollector;
    }

    public void execute(Tuple input) {
        LOG.info("About to insert tuple[" + input + "] into HBase...");

        //declare 11 variables to be emitted by bolt
        int driverId = input.getIntegerByField("driverId");
        int truckId = input.getIntegerByField("truckId");
        Timestamp eventTime = (Timestamp) input.getValueByField("eventTime");
        long ts = eventTime.getTime();
        String eventType = input.getStringByField("eventType");
        double longitude = input.getDoubleByField("longitude");
        double latitude = input.getDoubleByField("latitude");
        String driverName = input.getStringByField("driverName");
        int routeId = input.getIntegerByField("routeId");
        String routeName = input.getStringByField("routeName");

        //long incidentTotalCount = 0;
        String hbaseRowKey = constructHbaseRowKey(driverId, truckId, eventTime);


        LOG.info("driver ID " + driverId + "truckId " + truckId + " eventTime " + ts);
        LOG.info("eventType " + eventType + " longitude " + longitude + " latitude " + latitude);
        LOG.info("driverName " + driverName + " routeId " + routeId + " routeName " + routeName);

        LOG.info("Checking EventType from enum: " + EventType.NORMAL.getType());


        // Not Normal Events
        if (!eventType.equals(EventType.NORMAL.getType())) {
            LOG.info("Checking EventType from enum in if: " + EventType.NORMAL.getType());
            //RouteBolt emits a tuple with 11 fields relating to truckevents
            outputCollector.emit(EventTypeStream.NOT_NORMAL.getStream(), input, new Values(driverId, truckId, ts, eventType, longitude, latitude,
                   driverName, routeId, routeName, hbaseRowKey));
        }

        // All other events go into the "default" stream
        //RouteBolt emits a tuple with 11 fields relating to truckevents
        outputCollector.emit("default", input, new Values(driverId, truckId, ts, eventType, longitude, latitude,
                driverName, routeId, routeName, hbaseRowKey));

        //acknowledge even if there is an error
        outputCollector.ack(input);
    }

    //retrieves infraction count per driver
   /*private long getInfractionCountForDriver(int driverId) {
        try {
            byte[] driverCount = Bytes.toBytes(driverId);
            Get get = new Get(driverCount);
            Result result = eventsCountTable.get(get);
            long count = 0;
            if (result != null) {
                byte[] countBytes = result.getValue(Bytes.toBytes(EVENTS_COUNT_TABLE_COLUMN_FAMILY_NAME),
                        INCIDENT_RUNNING_TOTAL_COLUMN);
                if (countBytes != null) {
                    count = Bytes.toLong(countBytes);
                }

            }
            return count;
        } catch (Exception e) {
            LOG.error("Error getting infraction count", e);
            //return Long.MIN_VALUE;
            //throw new RuntimeException("Error getting infraction count");
        }
    
    }*/

    /*Declares RouteBolt emits 1-tuples with 11 fields called "driverId", "truckId", "eventTime", "eventType", "longitude", "latitude",
            "incidentTotalCount", "driverName", "routeId", "routeName", "hbaseRowKey".*/
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("driverId", "truckId", "ts", "eventType", "longitude", "latitude",
                "driverName", "routeId", "routeName", "hbaseRowKey"));
        declarer.declareStream(EventTypeStream.NOT_NORMAL.getStream(), new Fields("driverId", "truckId", "ts", "eventType", "longitude", "latitude",
                "driverName", "routeId", "routeName", "hbaseRowKey"));
    }

    //Constructs HBaseRowKey
    private String constructHbaseRowKey(int driverId, int truckId, Timestamp ts2) {
        long reverseTime = Long.MAX_VALUE - ts2.getTime();
        String rowKey = driverId + "|" + truckId + "|" + reverseTime;
        return rowKey;
    }


}
