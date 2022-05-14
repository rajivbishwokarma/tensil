/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import java.io._
import scala.collection.mutable
import tensil.tools.{
  CompilerException,
  TraceContext,
  TracepointCondition,
  TracepointsMap
}
import tensil.{ArchitectureDataType, InstructionLayout}

class BackendSegment(
    val key: BackendSegmentKey,
    layout: InstructionLayout,
    stats: Option[Stats],
    tracepointConditions: Seq[TracepointCondition],
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None
) extends LIR {
  val file = File.createTempFile("segment_", ".tprog")

  private val fileStream = new FileOutputStream(file)

  private val lirTracepointCollector = new LIRTracepointCollector(
    tracepointConditions,
    resolveRefToObject
  )

  private val lirBroadcast = new LIRBroadcast(
    Seq(
      new LIRGen(layout, fileStream),
      lirTracepointCollector
    ) ++ (if (stats.isDefined) Seq(new LIREstimator(layout, stats.get))
          else
            Nil)
  )

  def instructionsCount = lirTracepointCollector.instructionsCount
  def instructionTracepointsMaps =
    lirTracepointCollector.instructionTracepointsMaps

  def close(): Unit = fileStream.close()

  def emitNoOp(): Unit = lirBroadcast.emitNoOp()

  def emitWait(tidToWait: Int): Unit = lirBroadcast.emitWait(tidToWait)

  def emitMatMul(
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      accumulatorStride: Int,
      accumulatorAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit =
    lirBroadcast.emitMatMul(
      accumulate,
      localStride,
      localAddress,
      accumulatorStride,
      accumulatorAddress,
      size
    )

  def emitSIMD(
      accumulate: Boolean,
      simdOp: Int,
      simdSourceLeft: Int,
      simdSourceRight: Int,
      simdDestination: Int,
      writeAccumulatorAddress: MemoryAddress,
      readAccumulatorAddress: MemoryAddress
  ): Unit =
    lirBroadcast.emitSIMD(
      accumulate,
      simdOp,
      simdSourceLeft,
      simdSourceRight,
      simdDestination,
      writeAccumulatorAddress,
      readAccumulatorAddress
    )

  def emitDataMove(
      toLocal: Boolean,
      accumulate: Boolean,
      localStride: Int,
      localAddress: MemoryAddress,
      stride: Int,
      address: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit =
    lirBroadcast.emitDataMove(
      toLocal,
      accumulate,
      localStride,
      localAddress,
      stride,
      address,
      size
    )

  def emitLoadWeights(
      localStride: Int,
      localAddress: MemoryAddress,
      size: MemoryAddressRaw
  ): Unit = lirBroadcast.emitLoadWeights(localStride, localAddress, size)
}

object BackendSegmentKey {
  val Init    = 0
  val Load    = 1
  val Compute = 2
  val Save    = 3

  def apply(
      layer: Int,
      stage: Int,
      partition: Int,
      kind: Int
  ): BackendSegmentKey = (layer, stage, partition, kind)
}

class Backend(
    layout: InstructionLayout,
    tracepointConditions: Seq[TracepointCondition] = Nil,
    resolveRefToObject: (MemoryRef) => Option[MemoryObject] = (ref) => None,
    traceContext: TraceContext = TraceContext.empty,
) {
  private val segments =
    mutable.SortedMap.empty[BackendSegmentKey, BackendSegment]

  def mkSegment(
      key: BackendSegmentKey,
      stats: Option[Stats] = None
  ): BackendSegment =
    new BackendSegment(
      key = key,
      layout = layout,
      stats = stats,
      tracepointConditions = tracepointConditions,
      resolveRefToObject = resolveRefToObject
    )

  def finalizeSegment(segment: BackendSegment): Unit = {
    segment.close()
    segments(segment.key) = segment
  }

  def writeSegments(
      programStream: OutputStream,
      printProgramStream: Option[DataOutputStream] = None,
      stats: Option[Stats] = None
  ): Unit = {
    var instructionOffset: Long = 0
    val lir = new LIRBroadcast(
      Seq(new LIRGen(layout, programStream)) ++ (if (
                                                   printProgramStream.isDefined
                                                 )
                                                   Seq(
                                                     new LIRPrinter(
                                                       printProgramStream.get
                                                     )
                                                   )
                                                 else
                                                   Nil) ++ (if (stats.isDefined)
                                                              Seq(
                                                                new LIREstimator(
                                                                  layout,
                                                                  stats.get
                                                                )
                                                              )
                                                            else Nil)
    )

    val tileWindowSize = layout.arch.numberOfThreads match {
      case 1 => 1
      case 2 => 3
      case _ =>
        throw new CompilerException(
          s"${layout.arch.numberOfThreads} threads are not supported"
        )
    }

    var nextTid = 0

    case class Tile(
        init: Option[BackendSegment] = None,
        load: Option[BackendSegment] = None,
        compute: Option[BackendSegment] = None,
        save: Option[BackendSegment] = None,
    ) {
      val tid = nextTid

      nextTid = (nextTid + 1) % layout.arch.numberOfThreads
    }

    var curInit: Option[BackendSegment] = None
    val tiles = Seq.fill(tileWindowSize - 1)(Tile()) ++
      (for (
        layerSegments <-
          segments.groupBy(_._1.layer).toSeq.sortBy(_._1).map(_._2)
      )
        yield (for (
          tileSegments <-
            layerSegments
              .groupBy(p => (p._1.stage, p._1.partition))
              .toSeq
              .sortBy(_._1)
              .map(_._2)
        ) yield {
          val kindSegments = tileSegments.map {
            case (key, segment) => (key.kind, segment)
          }

          val init = if (tileSegments.head._1.partition == 0) {
            curInit = kindSegments.get(BackendSegmentKey.Init)
            curInit
          } else if (
            tileSegments.head._1.partition < layout.arch.numberOfThreads
          ) {
            curInit
          } else None

          Tile(
            init = init,
            load = kindSegments.get(BackendSegmentKey.Load),
            compute = kindSegments.get(BackendSegmentKey.Compute),
            save = kindSegments.get(BackendSegmentKey.Save)
          )
        }) ++ Seq.fill(tileWindowSize - 1)(Tile())).reduceLeft(_ ++ _)

    def overlayTiles(tiles: Seq[Tile]) = {
      require(tiles.size == 1 || tiles.size == 3)

      val streamsByTid =
        (if (tiles.size == 3)
           Seq(
             (tiles(0).tid, tiles(0).save),
             (tiles(2).tid, tiles(2).init),
             (tiles(2).tid, tiles(2).load),
             (tiles(1).tid, tiles(1).compute)
           )
         else if (tiles.size == 1)
           Seq(
             (tiles(0).tid, tiles(0).init),
             (tiles(0).tid, tiles(0).load),
             (tiles(0).tid, tiles(0).compute),
             (tiles(0).tid, tiles(0).save)
           )
         else Nil)
          .filter(_._2.isDefined)
          .map {
            case (tid, tile) =>
              if (printProgramStream.isDefined)
                printProgramStream.get.writeBytes(
                  s"; TID $tid: ${BackendSegmentKeyHelper(tile.get.key)}\r\n"
                )

              (tid, new FileInputStream(tile.get.file))
          }

      val parsersByTid =
        streamsByTid
          .groupBy(_._1)
          .map {
            case (tid, g) =>
              (tid -> LIRParser.combine(
                g.map(p => new LIRStreamParser(layout.arch, p._2)): _*
              ))
          }

      val threads = parsersByTid.keys
        .map(parserTid =>
          new LIR {
            // TODO: Thread will set desired TID

            val tid       = parserTid
            val estimator = new Estimator(layout)
            var curCycles = 0L

            private def adjustLocalAddress(address: MemoryAddress) =
              if (address.tag == MemoryTag.Local) {

                MemoryAddress(
                  MemoryTag.Local,
                  address.ref,
                  address.raw + layout.arch.threadLocalDepth * tid
                )
              } else address

            override def emitNoOp(): Unit = {
              curCycles += estimator.estimateCyclesAndEnergy(Opcode.Wait).cycles
              lir.emitNoOp()
            }

            override def emitWait(tidToWait: Int): Unit = {
              curCycles += estimator.estimateCyclesAndEnergy(Opcode.Wait).cycles
              lir.emitWait(tidToWait)
            }

            override def emitMatMul(
                accumulate: Boolean,
                localStride: Int,
                localAddress: MemoryAddress,
                accumulatorStride: Int,
                accumulatorAddress: MemoryAddress,
                size: MemoryAddressRaw
            ): Unit = {
              curCycles += estimator
                .estimateCyclesAndEnergy(Opcode.MatMul, Some(size))
                .cycles
              lir.emitMatMul(
                accumulate,
                localStride,
                adjustLocalAddress(localAddress),
                accumulatorStride,
                accumulatorAddress,
                size
              )
            }

            override def emitSIMD(
                accumulate: Boolean,
                simdOp: Int,
                simdSourceLeft: Int,
                simdSourceRight: Int,
                simdDestination: Int,
                writeAccumulatorAddress: MemoryAddress,
                readAccumulatorAddress: MemoryAddress
            ): Unit = {
              curCycles += estimator.estimateCyclesAndEnergy(Opcode.SIMD).cycles
              lir.emitSIMD(
                accumulate,
                simdOp,
                simdSourceLeft,
                simdSourceRight,
                simdDestination,
                writeAccumulatorAddress,
                readAccumulatorAddress
              )
            }

            override def emitDataMove(
                toLocal: Boolean,
                accumulate: Boolean,
                localStride: Int,
                localAddress: MemoryAddress,
                stride: Int,
                address: MemoryAddress,
                size: MemoryAddressRaw
            ): Unit = {
              curCycles += estimator
                .estimateCyclesAndEnergy(
                  Opcode.DataMove,
                  Some(size),
                  LIRGen.mkDataMoveFlags(toLocal, accumulate, address.tag)
                )
                .cycles
              lir.emitDataMove(
                toLocal,
                accumulate,
                localStride,
                adjustLocalAddress(localAddress),
                stride,
                address,
                size
              )
            }

            override def emitLoadWeights(
                localStride: Int,
                localAddress: MemoryAddress,
                size: MemoryAddressRaw
            ): Unit = {
              curCycles += estimator
                .estimateCyclesAndEnergy(Opcode.LoadWeights, Some(size))
                .cycles
              lir.emitLoadWeights(
                localStride,
                adjustLocalAddress(localAddress),
                size
              )
            }

          }
        )
        .toSeq

      /* Find a thread with non-empty parsers and least cycles */
      def nextThread =
        threads
          .filter(m => parsersByTid.filter(_._2.hasNext).contains(m.tid))
          .sortBy(_.curCycles)
          .headOption
      var curThread = nextThread

      while (curThread.isDefined) {
        val curParser = parsersByTid(curThread.get.tid)

        curParser.parseNext(curThread.get)
        curThread = nextThread
      }

      /**
        * Pad threads with NoOps until maximum cycles.
        * This in the future will be replaced with mutual WAITs.
        */
      if (!threads.isEmpty) {
        val maxCycles = threads.map(_.curCycles).max

        while (threads.map(_.curCycles).min < maxCycles)
          for (thread <- threads)
            if (thread.curCycles < maxCycles)
              thread.emitNoOp()
      }

      streamsByTid.foreach(_._2.close())
    }

    for (i <- 0 until tiles.size - (tileWindowSize - 1)) {
      overlayTiles(tiles.slice(i, i + tileWindowSize))
    }

    /*for ((key, segment) <- segments) {
      if (printProgramStream.isDefined)
        printProgramStream.get.writeBytes(
          s";\r\n; ${BackendSegmentKeyHelper(key)}\r\n;\r\n"
        )

      decodeSegment(segment, lir)
      segment.file.delete()

      for (
        (offset, instructionTracepointsMap) <-
          segment.instructionTracepointsMaps
      ) {
        traceContext.emitTracepoints(
          instructionOffset + offset,
          instructionTracepointsMap
        )
      }

      instructionOffset += segment.instructionsCount
    }*/
  }

  def instructionsCount = segments.values.map(_.instructionsCount).sum
}
