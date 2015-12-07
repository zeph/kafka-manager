/**
 * Copyright 2015 Yahoo Inc. Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

package kafka.manager

import java.util.Properties
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, ThreadPoolExecutor}

import akka.actor.{ActorPath, ActorSystem, Props}
import akka.util.Timeout
import com.typesafe.config.{ConfigFactory, Config}
import controllers.Topic
import kafka.manager.ActorModel._
import org.slf4j.{LoggerFactory, Logger}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Success, Failure, Try}

/**
 * @author hiral
 */
case class TopicListExtended(list: IndexedSeq[(String, Option[TopicIdentity])], deleteSet: Set[String], underReassignments: IndexedSeq[String])
case class BrokerListExtended(list: IndexedSeq[BrokerIdentity], metrics: Map[Int,BrokerMetrics], combinedMetric: Option[BrokerMetrics], clusterConfig: ClusterConfig)
case class ApiError(msg: String)
object ApiError {
  private[this] val log : Logger = LoggerFactory.getLogger(classOf[ApiError])

  implicit def fromThrowable(t: Throwable) : ApiError = {
    log.error(s"error : ${t.getMessage}", t)
    ApiError(t.getMessage)
  }

  implicit def from(actorError: ActorErrorResponse): ApiError = {
    actorError.throwableOption.foreach { t =>
      log.error(s"Actor error : ${actorError.msg}", t)
    }
    ApiError(actorError.msg)
  }
}

object KafkaManager {

  val BaseZkPath = "kafka-manager.base-zk-path"
  val PinnedDispatchName = "kafka-manager.pinned-dispatcher-name"
  val ZkHosts = "kafka-manager.zkhosts"
  val BrokerViewUpdateSeconds = "kafka-manager.broker-view-update-seconds"
  val KafkaManagerUpdateSeconds = "kafka-manager.kafka-manager-update-seconds"
  val DeleteClusterUpdateSeconds = "kafka-manager.delete-cluster-update-seconds"
  val DeletionBatchSize = "kafka-manager.deletion-batch-size"
  val MaxQueueSize = "kafka-manager.max-queue-size"
  val ThreadPoolSize = "kafka-manager.thread-pool-size"
  val MutexTimeoutMillis = "kafka-manager.mutex-timeout-millis"
  val StartDelayMillis = "kafka-manager.start-delay-millis"
  val ApiTimeoutMillis = "kafka-manager.api-timeout-millis"

  val DefaultConfig: Config = {
    val defaults: Map[String, _ <: AnyRef] = Map(
      BaseZkPath -> KafkaManagerActor.ZkRoot,
      PinnedDispatchName -> "pinned-dispatcher",
      BrokerViewUpdateSeconds -> "10",
      KafkaManagerUpdateSeconds -> "10",
      DeleteClusterUpdateSeconds -> "10",
      DeletionBatchSize -> "2",
      MaxQueueSize -> "100",
      ThreadPoolSize -> "2",
      MutexTimeoutMillis -> "4000",
      StartDelayMillis -> "1000",
      ApiTimeoutMillis -> "5000"
    )
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(defaults.asJava)
  }
}

import KafkaManager._
import akka.pattern._
import scalaz.{-\/, \/, \/-}
class KafkaManager(akkaConfig: Config)
{
  private[this] val system = ActorSystem("kafka-manager-system", akkaConfig)

  private[this] val configWithDefaults = akkaConfig.withFallback(DefaultConfig)
  private[this] val kafkaManagerConfig = {
    val curatorConfig = CuratorConfig(configWithDefaults.getString(ZkHosts))
    KafkaManagerActorConfig(
      curatorConfig = curatorConfig,
      baseZkPath = configWithDefaults.getString(BaseZkPath),
      pinnedDispatcherName = configWithDefaults.getString(PinnedDispatchName),
      brokerViewUpdatePeriod = FiniteDuration(configWithDefaults.getInt(BrokerViewUpdateSeconds), SECONDS),
      startDelayMillis = configWithDefaults.getLong(StartDelayMillis),
      threadPoolSize = configWithDefaults.getInt(ThreadPoolSize),
      mutexTimeoutMillis = configWithDefaults.getInt(MutexTimeoutMillis),
      maxQueueSize = configWithDefaults.getInt(MaxQueueSize),
      kafkaManagerUpdatePeriod = FiniteDuration(configWithDefaults.getInt(KafkaManagerUpdateSeconds), SECONDS),
      deleteClusterUpdatePeriod = FiniteDuration(configWithDefaults.getInt(DeleteClusterUpdateSeconds), SECONDS),
      deletionBatchSize = configWithDefaults.getInt(DeletionBatchSize)
    )
  }

  private[this] val props = Props(classOf[KafkaManagerActor], kafkaManagerConfig)

  private[this] val kafkaManagerActor: ActorPath = system.actorOf(props, "kafka-manager").path

  private[this] val apiExecutor = new ThreadPoolExecutor(
    kafkaManagerConfig.threadPoolSize,
    kafkaManagerConfig.threadPoolSize,
    0L,
    TimeUnit.MILLISECONDS,
    new LinkedBlockingQueue[Runnable](kafkaManagerConfig.maxQueueSize)
  )

  private[this] val apiExecutionContext = ExecutionContext.fromExecutor(apiExecutor)

  private[this] implicit val apiTimeout: Timeout = FiniteDuration(
    configWithDefaults.getInt(ApiTimeoutMillis),
    MILLISECONDS
  )

  private[this] def tryWithKafkaManagerActor[Input, Output, FOutput](msg: Input)
    (fn: Output => FOutput)
    (implicit tag: ClassTag[Output]): Future[ApiError \/ FOutput] =
  {
    implicit val ec = apiExecutionContext
    system.actorSelection(kafkaManagerActor).ask(msg).map {
      case err: ActorErrorResponse => -\/(ApiError.from(err))
      case o: Output =>
        Try {
          fn(o)
        } match {
          case Failure(t) => -\/(ApiError.fromThrowable(t))
          case Success(foutput) => \/-(foutput)
        }
    }.recover { case t: Throwable =>
      -\/(ApiError.fromThrowable(t))
    }
  }

  private[this] def withKafkaManagerActor[Input, Output, FOutput](msg: Input)
    (fn: Output => Future[ApiError \/ FOutput])
    (implicit tag: ClassTag[Output]): Future[ApiError \/ FOutput] =
  {
    implicit val ec = apiExecutionContext
    system.actorSelection(kafkaManagerActor).ask(msg).flatMap {
      case err: ActorErrorResponse => Future.successful(-\/(ApiError.from(err)))
      case o: Output =>
        fn(o)
    }.recover { case t: Throwable =>
      -\/(ApiError.fromThrowable(t))
    }
  }

  private[this] def toDisjunction[T](t: Try[T]): ApiError \/ T = {
    t match {
      case Failure(th) =>
        -\/(th)
      case Success(tInst) =>
        \/-(tInst)
    }
  }

  def shutdown(): Unit = {
    implicit val ec = apiExecutionContext
    system.actorSelection(kafkaManagerActor).tell(KMShutdown, system.deadLetters)
    system.shutdown()
    apiExecutor.shutdown()
  }

  //--------------------Commands--------------------------
  def addCluster(clusterName: String, version: String, zkHosts: String, jmxEnabled: Boolean, jmxUser: Option[String] = Some(""), jmxPass: Option[String] = Some("")): Future[ApiError \/
    Unit] =
  {
    val cc = ClusterConfig(clusterName, version, zkHosts, jmxEnabled = jmxEnabled, jmxUser = jmxUser, jmxPass = jmxPass)
    tryWithKafkaManagerActor(KMAddCluster(cc)) { result: KMCommandResult =>
      result.result.get
    }
  }

  def updateCluster(clusterName: String, version: String, zkHosts: String, jmxEnabled: Boolean, jmxUser: Option[String] = Some(""), jmxPass: Option[String] = Some("")): Future[ApiError \/
    Unit] =
  {
    val cc = ClusterConfig(clusterName, version, zkHosts, jmxEnabled = jmxEnabled, jmxUser = jmxUser, jmxPass = jmxPass)
    tryWithKafkaManagerActor(KMUpdateCluster(cc)) { result: KMCommandResult =>
      result.result.get
    }
  }

  def disableCluster(clusterName: String): Future[ApiError \/ Unit] = {
    tryWithKafkaManagerActor(KMDisableCluster(clusterName)) { result: KMCommandResult =>
      result.result.get
    }
  }

  def enableCluster(clusterName: String): Future[ApiError \/ Unit] = {
    tryWithKafkaManagerActor(KMEnableCluster(clusterName)) { result: KMCommandResult =>
      result.result.get
    }
  }

  def deleteCluster(clusterName: String): Future[ApiError \/ Unit] = {
    tryWithKafkaManagerActor(KMDeleteCluster(clusterName)) { result: KMCommandResult =>
      result.result.get
    }
  }

  def runPreferredLeaderElection(clusterName: String, topics: Set[String]): Future[ApiError \/ Unit] = {
    implicit val ec = apiExecutionContext
    withKafkaManagerActor(
      KMClusterCommandRequest(
        clusterName,
        CMRunPreferredLeaderElection(topics)
      )
    ) { result: Future[CMCommandResult] =>
      result.map(cmr => toDisjunction(cmr.result))
    }
  }

  def manualPartitionAssignments( clusterName: String,
                                  assignments: List[(String, List[(Int, List[Int])])]) = {
    implicit val ec = apiExecutionContext
    val results = tryWithKafkaManagerActor(
      KMClusterCommandRequest (
        clusterName,
        CMManualPartitionAssignments(assignments)
      )
    ) { result: CMCommandResults =>
      val errors = result.result.collect { case Failure(t) => ApiError(t.getMessage)}
      if (errors.isEmpty)
        \/-({})
      else
        -\/(errors)
    }

    results.map {
      case -\/(e) => -\/(IndexedSeq(e))
      case \/-(lst) => lst
    }
  }

  def generatePartitionAssignments(
                                    clusterName: String,
                                    topics: Set[String],
                                    brokers: Seq[Int]
                                    ): Future[IndexedSeq[ApiError] \/ Unit] =
  {
    val results = tryWithKafkaManagerActor(
      KMClusterCommandRequest(
        clusterName,
        CMGeneratePartitionAssignments(topics, brokers)
      )
    ) { result: CMCommandResults =>
      val errors = result.result.collect { case Failure(t) => ApiError(t.getMessage)}
      if (errors.isEmpty)
        \/-({})
      else
        -\/(errors)
    }
    implicit val ec = apiExecutionContext
    results.map {
      case -\/(e) => -\/(IndexedSeq(e))
      case \/-(lst) => lst
    }
  }

  def runReassignPartitions(clusterName: String, topics: Set[String]): Future[IndexedSeq[ApiError] \/ Unit] = {
    implicit val ec = apiExecutionContext
    val results = tryWithKafkaManagerActor(KMClusterCommandRequest(clusterName, CMRunReassignPartition(topics))) {
      resultFuture: Future[CMCommandResults] =>
        resultFuture map { result =>
          val errors = result.result.collect { case Failure(t) => ApiError(t.getMessage)}
          if (errors.isEmpty)
            \/-({})
          else
            -\/(errors)
        }
    }
    results.flatMap {
      case \/-(lst) => lst
      case -\/(e) => Future.successful(-\/(IndexedSeq(e)))
    }
  }

  def createTopic(
                   clusterName: String,
                   topic: String,
                   partitions: Int,
                   replication: Int,
                   config: Properties = new Properties
                   ): Future[ApiError \/ Unit] =
  {
    implicit val ec = apiExecutionContext
    withKafkaManagerActor(KMClusterCommandRequest(clusterName, CMCreateTopic(topic, partitions, replication, config))) {
      result: Future[CMCommandResult] =>
        result.map(cmr => toDisjunction(cmr.result))
    }
  }

  def addTopicPartitions(
                          clusterName: String,
                          topic: String,
                          brokers: Seq[Int],
                          partitions: Int,
                          readVersion: Int
                          ): Future[ApiError \/ Unit] =
  {
    implicit val ec = apiExecutionContext
    getTopicIdentity(clusterName, topic).flatMap { topicIdentityOrError =>
      topicIdentityOrError.fold(
      e => Future.successful(-\/(e)), { ti =>
        val partitionReplicaList: Map[Int, Seq[Int]] = ti.partitionsIdentity.mapValues(_.replicas)
        withKafkaManagerActor(
          KMClusterCommandRequest(
            clusterName,
            CMAddTopicPartitions(topic, brokers, partitions, partitionReplicaList, readVersion)
          )
        ) {
          result: Future[CMCommandResult] =>
            result.map(cmr => toDisjunction(cmr.result))
        }
      }
      )
    }
  }

  def addMultipleTopicsPartitions(
                              clusterName: String,
                              topics: Seq[String],
                              brokers: Seq[Int],
                              partitions: Int,
                              readVersions: Map[String, Int]
                              ): Future[ApiError \/ Unit] =
  {
    implicit val ec = apiExecutionContext
    getTopicListExtended(clusterName).flatMap { tleOrError =>
      tleOrError.fold(
      e => Future.successful(-\/(e)), { tle =>
        // add partitions to only topics with topic identity
        val topicsAndReplicas = topicListSortedByNumPartitions(tle).filter(t => topics.contains(t._1) && t._2.nonEmpty).map{ case (t,i) => (t, i.get.partitionsIdentity.mapValues(_.replicas)) }
        withKafkaManagerActor(
          KMClusterCommandRequest(
            clusterName,
            CMAddMultipleTopicsPartitions(topicsAndReplicas, brokers, partitions, readVersions)
          )
        ) {
          result: Future[CMCommandResult] =>
            result.map(cmr => toDisjunction(cmr.result))
        }
      }
      )
    }
  }

  def updateTopicConfig(
                         clusterName: String,
                         topic: String,
                         config: Properties,
                         readVersion: Int
                         ): Future[ApiError \/ Unit] =
  {
    implicit val ec = apiExecutionContext
    withKafkaManagerActor(
      KMClusterCommandRequest(
        clusterName,
        CMUpdateTopicConfig(topic, config, readVersion)
      )
    ) {
      result: Future[CMCommandResult] =>
        result.map(cmr => toDisjunction(cmr.result))
    }
  }

  def deleteTopic(
                   clusterName: String,
                   topic: String
                   ): Future[ApiError \/ Unit] =
  {
    implicit val ec = apiExecutionContext
    withKafkaManagerActor(KMClusterCommandRequest(clusterName, CMDeleteTopic(topic))) {
      result: Future[CMCommandResult] =>
        result.map(cmr => toDisjunction(cmr.result))
    }
  }

  //--------------------Queries--------------------------
  def getClusterConfig(clusterName: String): Future[ApiError \/ ClusterConfig] = {
    tryWithKafkaManagerActor(KMGetClusterConfig(clusterName)) { result: KMClusterConfigResult =>
      result.result.get
    }
  }

  def getClusterList: Future[ApiError \/ KMClusterList] = {
    tryWithKafkaManagerActor(KMGetAllClusters)(identity[KMClusterList])
  }

  def getClusterView(clusterName: String): Future[ApiError \/ CMView] = {
    tryWithKafkaManagerActor(KMClusterQueryRequest(clusterName, CMGetView))(identity[CMView])
  }

  def getTopicList(clusterName: String): Future[ApiError \/ TopicList] = {
    tryWithKafkaManagerActor(KMClusterQueryRequest(clusterName, KSGetTopics))(identity[TopicList])
  }

  def getTopicListExtended(clusterName: String): Future[ApiError \/ TopicListExtended] = {
    val futureTopicIdentities = tryWithKafkaManagerActor(KMClusterQueryRequest(clusterName, BVGetTopicIdentities))(identity[Map[String, TopicIdentity]])
    val futureTopicList = tryWithKafkaManagerActor(KMClusterQueryRequest(clusterName, KSGetTopics))(identity[TopicList])
    val futureTopicsReasgn = getTopicsUnderReassignment(clusterName)
    implicit val ec = apiExecutionContext
    for {
      errOrTi <- futureTopicIdentities
      errOrTl <- futureTopicList
      errOrRap <- futureTopicsReasgn
    } yield {
      for {
        ti <- errOrTi
        tl <- errOrTl
        rap <- errOrRap
      } yield {
        TopicListExtended(tl.list.map(t => (t, ti.get(t))).sortBy(_._1), tl.deleteSet, rap)
      }
    }
  }

  def getTopicsUnderReassignment(clusterName: String): Future[ApiError \/ IndexedSeq[String]] = {
    val futureReassignments = getReassignPartitions(clusterName)
    implicit val ec = apiExecutionContext
    futureReassignments.map {
      case -\/(e) => -\/(e)
      case \/-(rap) =>
        \/-(rap.map { asgn =>
          asgn.endTime.map(_ => IndexedSeq()).getOrElse{
            asgn.partitionsToBeReassigned.map { case (t, s) => t.topic}.toSet.toIndexedSeq
          }
        }.getOrElse{IndexedSeq()})
    }
  }

  def getBrokerList(clusterName: String): Future[ApiError \/ BrokerListExtended] = {
    val futureBrokerList= tryWithKafkaManagerActor(KMClusterQueryRequest(clusterName, KSGetBrokers))(identity[BrokerList])
    val futureBrokerMetrics = tryWithKafkaManagerActor(KMClusterQueryRequest(clusterName, BVGetBrokerMetrics))(identity[Map[Int,BrokerMetrics]])
    implicit val ec = apiExecutionContext
    futureBrokerList.flatMap[ApiError \/ BrokerListExtended] { errOrBrokerList =>
      errOrBrokerList.fold ({
        err: ApiError => Future.successful(-\/(err))
      }, { bl =>
        for {
          errOrbm <- futureBrokerMetrics.recover[ApiError \/ Map[Int,BrokerMetrics]] { case t =>
            \/-(Map.empty)
          }
        } yield {
          val bm = errOrbm.toOption.getOrElse(Map.empty)
          \/-(
            BrokerListExtended(
              bl.list, 
              bm, 
              if(bm.isEmpty) None else Option(bm.values.foldLeft(BrokerMetrics.DEFAULT)((acc, m) => acc + m)),
              bl.clusterConfig
            ))
        }
      })
    }
  }

  def getBrokersView(clusterName: String): Future[\/[ApiError, Seq[BVView]]] = {
    implicit val ec = apiExecutionContext

    tryWithKafkaManagerActor(
      KMClusterQueryRequest(
        clusterName,
        BVGetViews
      )
    )(identity[Seq[BVView]])
  }

  def getBrokerView(clusterName: String, brokerId: Int): Future[ApiError \/ BVView] = {
    val futureView = tryWithKafkaManagerActor(
      KMClusterQueryRequest(
        clusterName,
        BVGetView(brokerId)
      )
    )(identity[Option[BVView]])
    implicit val ec = apiExecutionContext
    futureView.flatMap[ApiError \/ BVView] { errOrView =>
      errOrView.fold(
      { err: ApiError =>
        Future.successful(-\/[ApiError](err))
      }, { viewOption: Option[BVView] =>
        viewOption.fold {
          Future.successful[ApiError \/ BVView](-\/(ApiError(s"Broker not found $brokerId for cluster $clusterName")))
        } { view =>
          Future.successful(\/-(view))
        }
      }
      )
    }
  }

  def getTopicIdentity(clusterName: String, topic: String): Future[ApiError \/ TopicIdentity] = {
    val futureCMTopicIdentity = tryWithKafkaManagerActor(KMClusterQueryRequest(clusterName, CMGetTopicIdentity(topic)))(
      identity[Option[CMTopicIdentity]]
    )
    implicit val ec = apiExecutionContext
    futureCMTopicIdentity.map[ApiError \/ TopicIdentity] { errOrTD =>
      errOrTD.fold[ApiError \/ TopicIdentity](
      { err: ApiError =>
        -\/[ApiError](err)
      }, { tiOption: Option[CMTopicIdentity] =>
        tiOption.fold[ApiError \/ TopicIdentity] {
          -\/(ApiError(s"Topic not found $topic for cluster $clusterName"))
        } { cmTopicIdentity =>
          cmTopicIdentity.topicIdentity match {
            case scala.util.Failure(t) =>
              -\/[ApiError](t)
            case scala.util.Success(ti) =>
              \/-(ti)
          }
        }
      }
      )
    }
  }

  def getTopicMetrics(clusterName: String, topic: String): Future[ApiError \/ Option[BrokerMetrics]] = {
    tryWithKafkaManagerActor(
      KMClusterQueryRequest(
        clusterName,
        BVGetTopicMetrics(topic)
      )
    ) { brokerMetrics: Option[BrokerMetrics] =>
      brokerMetrics
    }
  }

  def getPreferredLeaderElection(clusterName: String): Future[ApiError \/ Option[PreferredReplicaElection]] = {
    tryWithKafkaManagerActor(
      KMClusterQueryRequest(
        clusterName,
        KSGetPreferredLeaderElection
      )
    )(identity[Option[PreferredReplicaElection]])
  }

  def getReassignPartitions(clusterName: String): Future[ApiError \/ Option[ReassignPartitions]] = {
    tryWithKafkaManagerActor(
      KMClusterQueryRequest(
        clusterName,
        KSGetReassignPartition
      )
    )(identity[Option[ReassignPartitions]])
  }

  def topicListSortedByNumPartitions(tle: TopicListExtended): Seq[(String, Option[TopicIdentity])] = {
    def partition(tiOption: Option[TopicIdentity]): Int = {
      tiOption match {
        case Some(ti) => ti.partitions
        case None => 0
      }
    }
    val sortedByNumPartition = tle.list.sortWith{ (leftE, rightE) =>
      partition(leftE._2) > partition(rightE._2)
    }
    sortedByNumPartition
  }
}