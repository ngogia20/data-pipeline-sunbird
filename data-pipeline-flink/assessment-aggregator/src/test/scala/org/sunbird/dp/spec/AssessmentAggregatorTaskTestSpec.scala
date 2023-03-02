package org.sunbird.dp.spec

import java.util

import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext
import org.apache.flink.test.util.MiniClusterWithClientResource
import org.cassandraunit.CQLDataLoader
import org.cassandraunit.dataset.cql.FileCQLDataSet
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.mockito.Mockito
import org.mockito.Mockito._
import org.sunbird.dp.assessment.domain.Event
import org.sunbird.dp.assessment.task.{AssessmentAggregatorConfig, AssessmentAggregatorStreamTask}
import org.sunbird.dp.core.job.FlinkKafkaConnector
import org.sunbird.dp.core.util.CassandraUtil
import org.sunbird.dp.fixture.EventFixture
import org.sunbird.dp.{BaseMetricsReporter, BaseTestSpec}
import org.junit.Assert.{assertEquals, assertNotNull}

import collection.JavaConverters._

class AssessmentAggregatorTaskTestSpec extends BaseTestSpec {

    implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])

    val flinkCluster = new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
      .setConfiguration(testConfiguration())
      .setNumberSlotsPerTaskManager(1)
      .setNumberTaskManagers(1)
      .build)


    val config: Config = ConfigFactory.load("test.conf")
    val assessmentConfig: AssessmentAggregatorConfig = new AssessmentAggregatorConfig(config)
    val mockKafkaUtil: FlinkKafkaConnector = mock[FlinkKafkaConnector](Mockito.withSettings().serializable())
    val gson = new Gson()


    var cassandraUtil: CassandraUtil = _


    override protected def beforeAll(): Unit = {
        super.beforeAll()
        EmbeddedCassandraServerHelper.startEmbeddedCassandra(80000L)
        cassandraUtil = new CassandraUtil(assessmentConfig.dbHost, assessmentConfig.dbPort)
        val session = cassandraUtil.session


        val dataLoader = new CQLDataLoader(session);
        dataLoader.load(new FileCQLDataSet(getClass.getResource("/test.cql").getPath, true, true));
        // Clear the metrics
        testCassandraUtil(cassandraUtil)
        BaseMetricsReporter.gaugeMetrics.clear()

        flinkCluster.before()
    }

    override protected def afterAll(): Unit = {
        super.afterAll()
        try {
            EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
        }catch {
            case ex: Exception =>{

            }
        }
        flinkCluster.after()
    }


    "AssessmentAggregator " should "Update event to db" in {
        when(mockKafkaUtil.kafkaEventSource[Event](assessmentConfig.kafkaInputTopic)).thenReturn(new AssessmentAggreagatorEventSource)
        when(mockKafkaUtil.kafkaEventSink[Event](assessmentConfig.kafkaFailedTopic)).thenReturn(new FailedEventsSink)
        val task = new AssessmentAggregatorStreamTask(assessmentConfig, mockKafkaUtil)
        task.process()
        val failedEvent = gson.fromJson(gson.toJson(FailedEventsSink.values.get(0)), new util.LinkedHashMap[String, AnyRef]().getClass).asInstanceOf[util.Map[String, AnyRef]].asScala
        assert(failedEvent.get("map").get.asInstanceOf[LinkedTreeMap[String, AnyRef]].containsKey("metadata"))
        BaseMetricsReporter.gaugeMetrics(s"${assessmentConfig.jobName}.${assessmentConfig.skippedEventCount}").getValue() should be(1)
        BaseMetricsReporter.gaugeMetrics(s"${assessmentConfig.jobName}.${assessmentConfig.dbReadCount}").getValue() should be(2)
        BaseMetricsReporter.gaugeMetrics(s"${assessmentConfig.jobName}.${assessmentConfig.dbUpdateCount}").getValue() should be(4)
        BaseMetricsReporter.gaugeMetrics(s"${assessmentConfig.jobName}.${assessmentConfig.failedEventCount}").getValue() should be(1)
        BaseMetricsReporter.gaugeMetrics(s"${assessmentConfig.jobName}.${assessmentConfig.batchSuccessCount}").getValue() should be(4)
        val test_row1 = cassandraUtil.findOne("select total_score,total_max_score from sunbird_courses.assessment_aggregator where course_id='do_2128410273679114241112'")
        assert(test_row1.getDouble("total_score") == 2.0)
        assert(test_row1.getDouble("total_max_score") == 2.0)

        val test_row2 = cassandraUtil.findOne("select total_score,total_max_score from sunbird_courses.assessment_aggregator where course_id='do_2128415652377067521125'")
        assert(test_row2.getDouble("total_score") == 3.0)
        assert(test_row2.getDouble("total_max_score") == 4.0)
    }

    def testCassandraUtil(cassandraUtil: CassandraUtil): Unit ={
        cassandraUtil.reconnect()
        val response = cassandraUtil.find("SELECT * FROM sunbird_courses.assessment_aggregator;")
        response should not be(null)
    }
}

class AssessmentAggreagatorEventSource extends SourceFunction[Event] {

    override def run(ctx: SourceContext[Event]) {
        val gson = new Gson()

        val eventMap1 = gson.fromJson(EventFixture.BATCH_ASSESS_EVENT, new util.LinkedHashMap[String, Any]().getClass)
        val eventMap2 = gson.fromJson(EventFixture.BATCH_ASSESS__OLDER_EVENT, new util.LinkedHashMap[String, Any]().getClass)
        val eventMap3 = gson.fromJson(EventFixture.BATCH_ASSESS_FAIL_EVENT, new util.LinkedHashMap[String, Any]().getClass)
        val eventMap4 = gson.fromJson(EventFixture.QUESTION_EVENT_RES_VALUES, new util.LinkedHashMap[String, Any]().getClass)
        val eventMap5 = gson.fromJson(EventFixture.LATEST_BATCH_ASSESS_EVENT, new util.LinkedHashMap[String, Any]().getClass)
        val eventMap6 = gson.fromJson(EventFixture.BATCH_DUPLICATE_QUESTION_EVENT, new util.LinkedHashMap[String, Any]().getClass)
        ctx.collect(new Event(eventMap1))
        ctx.collect(new Event(eventMap2))
        ctx.collect(new Event(eventMap3))
        ctx.collect(new Event(eventMap4))
        ctx.collect(new Event(eventMap5))
        ctx.collect(new Event(eventMap6))
    }

    override def cancel() = {}

}


class FailedEventsSink extends SinkFunction[Event] {

    override def invoke(value: Event): Unit = {
        synchronized {
            FailedEventsSink.values.add(value)
        }
    }
}

object FailedEventsSink {
    val values: util.List[Event] = new util.ArrayList()
}