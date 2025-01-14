package org.apache.seatunnel.metrics.spark

import java.util
import java.util.{Locale, Properties}
import java.util.concurrent.TimeUnit
import scala.collection.JavaConversions._
import com.codahale.metrics
import com.codahale.metrics.{Counter, Histogram, Meter, _}
import org.apache.seatunnel.metrics.core.reporter.MetricReporter
import org.apache.seatunnel.metrics.core.{Gauge, _}
import org.apache.seatunnel.metrics.prometheus.PrometheusPushGatewayReporter
import org.apache.spark.internal.Logging

object SeatunnelMetricSink {
  trait SinkConfig extends Serializable {
    def metricsNamespace: Option[String]

    def sparkAppId: Option[String]

    def sparkAppName: Option[String]

    def executorId: Option[String]
  }
}

abstract class SeatunnelMetricSink(
    property: Properties,
    registry: MetricRegistry,
    sinkConfig: SeatunnelMetricSink.SinkConfig) extends Logging {

  import sinkConfig._

  protected class SeatunnelMetricReporter(registry: MetricRegistry, metricFilter: MetricFilter)
    extends ScheduledReporter(
      registry,
      "seatunnel-reporter",
      metricFilter,
      TimeUnit.SECONDS,
      TimeUnit.MILLISECONDS) {

    override def report(
        gauges: util.SortedMap[String, metrics.Gauge[_]],
        counters: util.SortedMap[String, Counter],
        histograms: util.SortedMap[String, Histogram],
        meters: util.SortedMap[String, Meter],
        timers: util.SortedMap[String, Timer]) = {

      val role: String = (sparkAppId, executorId) match {
        case (Some(_), Some("driver")) | (Some(_), Some("<driver>")) => "driver"
        case (Some(_), Some(_)) => "executor"
        case _ => "unknown"
      }

      val job: String = role match {
        case "driver" => metricsNamespace.getOrElse(sparkAppId.get)
        case "executor" => metricsNamespace.getOrElse(sparkAppId.get)
        case _ => metricsNamespace.getOrElse("unknown")
      }

      // val instance: String = "instance"
      val appName: String = sparkAppName.getOrElse("")

      logInfo(s"role=$role, job=$job")

      val dimensionKeys = new util.LinkedList[String]()
      val dimensionValues = new util.LinkedList[String]()
      dimensionKeys.add("job_name")
      dimensionValues.add(appName)
      dimensionKeys.add("job_id")
      dimensionValues.add(job)
      dimensionKeys.add("role")
      dimensionValues.add(role)

      val countersIndex = new util.HashMap[org.apache.seatunnel.metrics.core.Counter, MetricInfo]
      val gaugesIndex = new util.HashMap[org.apache.seatunnel.metrics.core.Gauge[_], MetricInfo]
      val histogramsIndex =
        new util.HashMap[org.apache.seatunnel.metrics.core.Histogram, MetricInfo]
      val metersIndex = new util.HashMap[org.apache.seatunnel.metrics.core.Meter, MetricInfo]

      for (metricName <- gauges.keySet()) {
        val metric = gauges.get(metricName)
        val num = numeric(metric.getValue)
        if (num.toString != Long.MaxValue.toString) {
          gaugesIndex.put(
            new SimpleGauge(num),
            newMetricInfo(metricName, dimensionKeys, dimensionValues))
        } else {
          logError(metricName + " is not a number ")
        }
      }

      for (metricName <- counters.keySet()) {
        val metric = counters.get(metricName)
        countersIndex.put(
          new SimpleCounter(metric.getCount),
          newMetricInfo(metricName, dimensionKeys, dimensionValues))
      }

      for (metricName <- meters.keySet()) {
        val metric = meters.get(metricName)
        metersIndex.put(
          new SimpleMeter(metric.getMeanRate, metric.getCount),
          newMetricInfo(metricName, dimensionKeys, dimensionValues))
      }

      for (metricName <- histograms.keySet()) {
        val metric = histograms.get(metricName)
        histogramsIndex.put(
          new SimpleHistogram(
            metric.getCount,
            metric.getSnapshot.getMin,
            metric.getSnapshot.getMax,
            metric.getSnapshot.getStdDev,
            metric.getSnapshot.getMean,
            new util.HashMap[java.lang.Double, java.lang.Double]() {
              0.75 -> metric.getSnapshot.get75thPercentile();
              0.95 -> metric.getSnapshot.get95thPercentile();
              0.99 -> metric.getSnapshot.get99thPercentile()
            }),
          newMetricInfo(metricName, dimensionKeys, dimensionValues))
      }
//      val reporter = new PrometheusPushGatewayReporter
//      val config = new MetricConfig
//      config.setJobName(pollJobName)
//      config.setHost(pollHost)
//      config.setPort(pollPort)
//      reporter.open(config)
//      reporter.report(gaugesIndex, countersIndex, histogramsIndex, metersIndex)
      try {
        val aClass = Class.forName(pollReporter)
        val reporter = aClass.newInstance.asInstanceOf[MetricReporter]
        val config = new MetricConfig
        config.setJobName(pollJobName)
        config.setHost(pollHost)
        config.setPort(pollPort)
        reporter.open(config)
        reporter.report(gaugesIndex, countersIndex, histogramsIndex, metersIndex)
      } catch {
        case e: Exception =>
          throw new RuntimeException(e)
      }
    }

  }

  val CONSOLE_DEFAULT_PERIOD = 10
  val CONSOLE_DEFAULT_UNIT = "SECONDS"
  val CONSOLE_DEFAULT_HOST = "localhost"
  val CONSOLE_DEFAULT_PORT = 9091
  val CONSOLE_DEFAULT_JOB_NAME = "sparkJob"
  val CONSOLE_DEFAULT_REPORTER_NAME = "org.apache.seatunnel.metrics.console.ConsoleLogReporter"

  val CONSOLE_KEY_INTERVAL = "interval"
  val CONSOLE_KEY_UNIT = "unit"
  val CONSOLE_KEY_HOST = "host"
  val CONSOLE_KEY_PORT = "port"
  val CONSOLE_KEY_JOB_NAME = "jobName"
  val CONSOLE_KEY_REPORTER_NAME = "reporterName"

  val KEY_RE_METRICS_FILTER = "metrics-filter-([a-zA-Z][a-zA-Z0-9-]*)".r

  val pollPeriod: Long = Option(property.getProperty(CONSOLE_KEY_INTERVAL)) match {
    case Some(s) => s.toInt
    case None => CONSOLE_DEFAULT_PERIOD
  }

  val pollUnit: TimeUnit = Option(property.getProperty(CONSOLE_KEY_UNIT)) match {
    case Some(s) => TimeUnit.valueOf(s.toUpperCase(Locale.ROOT))
    case None => TimeUnit.valueOf(CONSOLE_DEFAULT_UNIT)
  }

  val pollHost: String = Option(property.getProperty(CONSOLE_KEY_HOST)) match {
    case Some(s) => s
    case None => CONSOLE_DEFAULT_HOST
  }

  val pollPort: Int = Option(property.getProperty(CONSOLE_KEY_PORT)) match {
    case Some(s) => s.toInt
    case None => CONSOLE_DEFAULT_PORT
  }

  val pollJobName: String = Option(property.getProperty(CONSOLE_KEY_JOB_NAME)) match {
    case Some(s) => s
    case None => CONSOLE_DEFAULT_JOB_NAME
  }

  val pollReporter: String = Option(property.getProperty(CONSOLE_KEY_REPORTER_NAME)) match {
    case Some(s) => s
    case None => CONSOLE_DEFAULT_REPORTER_NAME
  }

  val metricsFilter: MetricFilter = MetricFilter.ALL

  val seatunnelReporter = new SeatunnelMetricReporter(registry, metricsFilter)

  def start(): Unit = {
    seatunnelReporter.start(pollPeriod, pollUnit)
  }

  def stop(): Unit = {
    seatunnelReporter.stop()
  }

  def report(): Unit = {
    seatunnelReporter.report()
  }

  private def numeric(a: Any): Number = {
    // val NumericString = Array("double","Double", "float","Float", "int","Int", "long", "Long", "short","Short")
    a.getClass.getSimpleName match {
      case "Integer" => a.toString.toInt
      case "Double" => a.toString.toDouble
      case "Float" => a.toString.toFloat
      case "Long" => a.toString.toLong
      case "Short" => a.toString.toShort
      case _ => Long.MaxValue
    }
  }

  private def newMetricInfo(
      info: String,
      dimensionKeys: util.LinkedList[String],
      dimensionValues: util.LinkedList[String]): MetricInfo = {
    val proInfo = info.replace("-", "_")
    val infos = proInfo.split("\\.")

    var metricName = infos.drop(1).map(str => {
      str + "_"
    }).mkString("")
    metricName = metricName.dropRight(1)
    val seatunnelMetricName = "seatunnel_" + metricName

    // dimensionKeys.add("sourceName")
    // dimensionValues.add(infos.apply(2))

    val helpString = infos.apply(2) + "(scope:" + metricName + ")"

    new MetricInfo(seatunnelMetricName, helpString, dimensionKeys, dimensionValues)
  }

}
