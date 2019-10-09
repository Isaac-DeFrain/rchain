package coop.rchain.rspace

import java.lang
import java.nio.file.{Files, Path}

import scala.collection.SortedSet
import scala.concurrent.ExecutionContext
import scala.util.Random
import cats.effect._
import cats.implicits._
import cats.temp.par.Par
import coop.rchain.catscontrib._
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.metrics.Metrics.Source
import coop.rchain.metrics.implicits._
import coop.rchain.rspace.history.{Branch, HistoryRepository}
import coop.rchain.rspace.internal.{DataCandidate, _}
import coop.rchain.rspace.trace._
import coop.rchain.shared.{Cell, Log, Serialize}
import coop.rchain.shared.SyncVarOps._
import com.typesafe.scalalogging.Logger
import monix.execution.atomic.AtomicAny
import scodec.Codec

class RSpace[F[_], C, P, A, K](
    historyRepository: HistoryRepository[F, C, P, A, K],
    storeAtom: AtomicAny[HotStore[F, C, P, A, K]],
    branch: Branch
)(
    implicit
    serializeC: Serialize[C],
    serializeP: Serialize[P],
    serializeA: Serialize[A],
    serializeK: Serialize[K],
    val m: Match[F, P, A],
    val concurrent: Concurrent[F],
    logF: Log[F],
    contextShift: ContextShift[F],
    scheduler: ExecutionContext,
    metricsF: Metrics[F],
    val spanF: Span[F]
) extends RSpaceOps[F, C, P, A, K](historyRepository, storeAtom, branch)
    with ISpace[F, C, P, A, K] {

  def store: HotStore[F, C, P, A, K] = storeAtom.get()

  protected[this] override val logger: Logger = Logger[this.type]

  implicit protected[this] lazy val MetricsSource: Source = RSpaceMetricsSource
  private[this] val consumeCommLabel                      = "comm.consume"
  private[this] val consumeTimeCommLabel                  = "comm.consume-time"
  private[this] val produceCommLabel                      = "comm.produce"
  private[this] val produceTimeCommLabel                  = "comm.produce-time"

  override def consume(
      channels: Seq[C],
      patterns: Seq[P],
      continuation: K,
      persist: Boolean,
      peeks: SortedSet[Int] = SortedSet.empty
  ): F[MaybeActionResult] =
    contextShift.evalOn(scheduler) {
      if (channels.isEmpty) {
        val msg = "channels can't be empty"
        logF.error(msg) >> syncF.raiseError(new IllegalArgumentException(msg))
      } else if (channels.length =!= patterns.length) {
        val msg = "channels.length must equal patterns.length"
        logF.error(msg) >> syncF.raiseError(new IllegalArgumentException(msg))
      } else
        (for {
          consumeRef <- Consume.createF(channels, patterns, continuation, persist)
          result <- consumeLockF(channels) {
                     lockedConsume(
                       channels,
                       patterns,
                       continuation,
                       persist,
                       peeks,
                       consumeRef
                     )
                   }
        } yield result).timer(consumeTimeCommLabel)
    }

  private[this] def lockedConsume(
      channels: Seq[C],
      patterns: Seq[P],
      continuation: K,
      persist: Boolean,
      peeks: SortedSet[Int],
      consumeRef: Consume
  ): F[MaybeActionResult] =
    for {
      _ <- logF.debug(
            s"consume: searching for data matching <patterns: $patterns> at <channels: $channels>"
          )
      channelToIndexedData <- fetchChannelToIndexData(channels)
      _                    <- logConsume(consumeRef)
      options <- extractDataCandidates(
                  channels.zip(patterns),
                  channelToIndexedData,
                  Nil
                ).map(_.sequence)
      result <- options.fold(
                 storeWaitingContinuation(
                   channels,
                   WaitingContinuation(
                     patterns,
                     continuation,
                     persist,
                     peeks,
                     consumeRef
                   )
                 )
               )(
                 dataCandidates =>
                   for {
                     _               <- metricsF.incrementCounter(consumeCommLabel)
                     _               <- logComm(consumeRef, peeks, dataCandidates)
                     channelsToIndex = channels.zipWithIndex.toMap
                     _               <- storePersistentData(dataCandidates, peeks, channelsToIndex)
                     _ <- logF.debug(
                           s"consume: data found for <patterns: $patterns> at <channels: $channels>"
                         )
                   } yield wrapResult(
                     channels,
                     patterns,
                     continuation,
                     persist,
                     peeks,
                     consumeRef,
                     dataCandidates
                   )
               )
    } yield result

  /*
   * Here, we create a cache of the data at each channel as `channelToIndexedData`
   * which is used for finding matches.  When a speculative match is found, we can
   * remove the matching datum from the remaining data candidates in the cache.
   *
   * Put another way, this allows us to speculatively remove matching data without
   * affecting the actual store contents.
   */
  private[this] def fetchChannelToIndexData(channels: Seq[C]): F[Map[C, Seq[(Datum[A], Int)]]] =
    channels
      .traverse { c: C =>
        store.getData(c).shuffleWithIndex.map(c -> _)
      }
      .map(_.toMap)

  private[this] def storePersistentData(
      dataCandidates: Seq[DataCandidate[C, A]],
      peeks: SortedSet[Int],
      channelsToIndex: Map[C, Int]
  ): F[List[Unit]] =
    dataCandidates.toList
      .sortBy(_.datumIndex)(Ordering[Int].reverse)
      .traverse {
        case DataCandidate(
            candidateChannel,
            Datum(_, persistData, _),
            _,
            dataIndex
            ) =>
          store.removeDatum(candidateChannel, dataIndex).unlessA(persistData)
      }

  override def produce(
      channel: C,
      data: A,
      persist: Boolean
  ): F[MaybeActionResult] =
    contextShift.evalOn(scheduler) {
      (for {
        produceRef <- Produce.createF(channel, data, persist)
        result <- produceLockF(channel)(
                   lockedProduce(channel, data, persist, produceRef)
                 )
      } yield result).timer(produceTimeCommLabel)
    }

  private[this] def lockedProduce(
      channel: C,
      data: A,
      persist: Boolean,
      produceRef: Produce
  ): F[MaybeActionResult] =
    for {
      //TODO fix double join fetch
      groupedChannels <- store.getJoins(channel)
      _ <- logF.debug(
            s"produce: searching for matching continuations at <groupedChannels: $groupedChannels>"
          )
      _ <- logProduce(produceRef, persist)
      extracted <- extractProduceCandidate(
                    groupedChannels,
                    channel,
                    Datum(data, persist, produceRef)
                  )
      r <- extracted.fold(storeData(channel, data, persist, produceRef))(processMatchFound)
    } yield r

  /*
   * Find produce candidate
   */

  type CandidateChannels = Seq[C]

  private[this] def extractProduceCandidate(
      groupedChannels: Seq[CandidateChannels],
      batChannel: C,
      data: Datum[A]
  ): F[MaybeProduceCandidate] = {

    def go(
        acc: Seq[CandidateChannels]
    ): F[Either[Seq[CandidateChannels], MaybeProduceCandidate]] =
      acc match {
        case Nil =>
          none[ProduceCandidate[C, P, A, K]].asRight[Seq[CandidateChannels]].pure[F]
        case channels :: remaining =>
          for {
            matchCandidates <- store
                                .getContinuations(channels)
                                .shuffleWithIndex
            /*
             * Here, we create a cache of the data at each channel as `channelToIndexedData`
             * which is used for finding matches.  When a speculative match is found, we can
             * remove the matching datum from the remaining data candidates in the cache.
             *
             * Put another way, this allows us to speculatively remove matching data without
             * affecting the actual store contents.
             *
             * In this version, we also add the produced data directly to this cache.
             */
            channelToIndexedDataList <- channels.traverse { c: C =>
                                         for {
                                           d  <- store.getData(c).shuffleWithIndex
                                           sp = if (c == batChannel) (data, -1) +: d else d
                                         } yield (c -> sp)
                                       }
            firstMatch <- extractFirstMatch(
                           channels,
                           matchCandidates,
                           channelToIndexedDataList.toMap
                         )
          } yield firstMatch match {
            case None             => remaining.asLeft[MaybeProduceCandidate]
            case produceCandidate => produceCandidate.asRight[Seq[CandidateChannels]]
          }
      }
    groupedChannels.tailRecM(go)
  }

  private[this] def processMatchFound(
      pc: ProduceCandidate[C, P, A, K]
  ): F[MaybeActionResult] = {
    val ProduceCandidate(
      channels,
      WaitingContinuation(
        patterns,
        continuation,
        persistK,
        peeks,
        consumeRef
      ),
      continuationIndex,
      dataCandidates
    ) = pc

    def removeMatchedDatumAndJoin(channelsToIndex: Map[C, Int]): F[Seq[Unit]] =
      dataCandidates
        .sortBy(_.datumIndex)(Ordering[Int].reverse)
        .traverse {
          case DataCandidate(
              candidateChannel,
              Datum(_, persistData, _),
              _,
              dataIndex
              ) => {
            store
              .removeDatum(candidateChannel, dataIndex)
              .whenA((dataIndex >= 0 && !persistData)) >>
              store.removeJoin(candidateChannel, channels)
          }
        }

    def constructResult: MaybeActionResult =
      Some(
        (
          ContResult[C, P, K](
            continuation,
            persistK,
            channels,
            patterns,
            peeks.nonEmpty
          ),
          dataCandidates.map(
            dc => Result(dc.channel, dc.datum.a, dc.removedDatum, dc.datum.persist)
          )
        )
      )

    for {
      _               <- metricsF.incrementCounter(produceCommLabel)
      _               <- logComm(consumeRef, peeks, dataCandidates)
      _               <- store.removeContinuation(channels, continuationIndex).unlessA(persistK)
      indexedChannels = channels.zipWithIndex.toMap
      _               <- removeMatchedDatumAndJoin(indexedChannels)
      _               <- logF.debug(s"produce: matching continuation found at <channels: $channels>")
    } yield constructResult
  }

  private[this] def logComm(
      consumeRef: Consume,
      peeks: SortedSet[Int],
      dataCandidates: Seq[DataCandidate[C, A]]
  ) = syncF.delay {
    val produceRefs = dataCandidates.map(_.datum.source)
    eventLog
      .update(
        COMM(consumeRef, produceRefs, peeks, produceCounters(produceRefs)) +: _
      )
  }

  private[this] def logProduce(produceRef: Produce, persist: Boolean): F[Unit] = syncF.delay {
    eventLog.update(produceRef +: _)
    if (!persist)
      produceCounter.update(_.putAndIncrementCounter(produceRef))
  }

  private[this] def logConsume(consumeRef: Consume): F[Unit] = syncF.delay {
    eventLog.update(consumeRef +: _)
  }

  override def createCheckpoint(): F[Checkpoint] =
    for {
      changes     <- storeAtom.get().changes()
      nextHistory <- historyRepositoryAtom.get().checkpoint(changes.toList)
      _           = historyRepositoryAtom.set(nextHistory)
      _           <- createNewHotStore(nextHistory)(serializeK.toCodec)
      log         = eventLog.take()
      _           = eventLog.put(Seq.empty)
      _           = produceCounter.take()
      _           = produceCounter.put(Map.empty.withDefaultValue(0))
      _           <- restoreInstalls()
    } yield Checkpoint(nextHistory.history.root, log)
}

object RSpace {
  val parallelism = lang.Runtime.getRuntime.availableProcessors() * 2

  def create[F[_], C, P, A, K](
      historyRepository: HistoryRepository[F, C, P, A, K],
      store: HotStore[F, C, P, A, K],
      branch: Branch
  )(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      m: Match[F, P, A],
      concurrent: Concurrent[F],
      logF: Log[F],
      contextShift: ContextShift[F],
      scheduler: ExecutionContext,
      metricsF: Metrics[F],
      spanF: Span[F]
  ): F[ISpace[F, C, P, A, K]] = {
    val space: ISpace[F, C, P, A, K] =
      new RSpace[F, C, P, A, K](historyRepository, AtomicAny(store), branch)

    space.pure[F]

  }

  def createWithReplay[F[_], C, P, A, K](dataDir: Path, mapSize: Long)(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      m: Match[F, P, A],
      concurrent: Concurrent[F],
      logF: Log[F],
      contextShift: ContextShift[F],
      scheduler: ExecutionContext,
      metricsF: Metrics[F],
      spanF: Span[F],
      par: Par[F]
  ): F[(ISpace[F, C, P, A, K], IReplaySpace[F, C, P, A, K], HistoryRepository[F, C, P, A, K])] = {
    val v2Dir = dataDir.resolve("v2")
    for {
      setup                  <- setUp[F, C, P, A, K](v2Dir, mapSize, Branch.MASTER)
      (historyReader, store) = setup
      space                  = new RSpace[F, C, P, A, K](historyReader, AtomicAny(store), Branch.MASTER)
      replayStore            <- HotStore.empty(historyReader)(sk.toCodec, concurrent)
      replay = new ReplayRSpace[F, C, P, A, K](
        historyReader,
        AtomicAny(replayStore),
        Branch.REPLAY
      )
    } yield (space, replay, historyReader)
  }

  def create[F[_], C, P, A, K](
      dataDir: Path,
      mapSize: Long,
      branch: Branch
  )(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      m: Match[F, P, A],
      concurrent: Concurrent[F],
      logF: Log[F],
      contextShift: ContextShift[F],
      scheduler: ExecutionContext,
      metricsF: Metrics[F],
      spanF: Span[F],
      par: Par[F]
  ): F[ISpace[F, C, P, A, K]] =
    setUp[F, C, P, A, K](dataDir, mapSize, branch).map {
      case (historyReader, store) =>
        new RSpace[F, C, P, A, K](historyReader, AtomicAny(store), branch)
    }

  def setUp[F[_], C, P, A, K](
      dataDir: Path,
      mapSize: Long,
      branch: Branch
  )(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      concurrent: Concurrent[F],
      par: Par[F]
  ): F[(HistoryRepository[F, C, P, A, K], HotStore[F, C, P, A, K])] = {

    import coop.rchain.rspace.history._
    implicit val cc = sc.toCodec
    implicit val cp = sp.toCodec
    implicit val ca = sa.toCodec
    implicit val ck = sk.toCodec

    val coldStore    = StoreConfig(dataDir.resolve("cold"), mapSize)
    val historyStore = StoreConfig(dataDir.resolve("history"), mapSize)
    val rootsStore   = StoreConfig(dataDir.resolve("roots"), mapSize)
    val config       = LMDBRSpaceStorageConfig(coldStore, historyStore, rootsStore)

    def checkCreateDir(dir: Path): F[Unit] =
      for {
        notexists <- Sync[F].delay(Files.notExists(dir))
        _         <- if (notexists) Sync[F].delay(Files.createDirectories(dir)) else ().pure[F]
      } yield ()

    for {
      _ <- checkCreateDir(coldStore.path)
      _ <- checkCreateDir(historyStore.path)
      _ <- checkCreateDir(rootsStore.path)
      historyReader <- HistoryRepositoryInstances
                        .lmdbRepository[F, C, P, A, K](config)
      store <- HotStore.empty(historyReader)
    } yield (historyReader, store)
  }
}
