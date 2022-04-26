/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools

import java.io._
import scala.reflect.ClassTag
import tensil.tools.golden.{Processor, ExecutiveTraceContext}
import scala.collection.mutable
import tensil.ArchitectureDataType

object Reshape {
  def prepareInputStream(
      dataType: ArchitectureDataType,
      arraySize: Int,
      count: Int = 1
  ): InputStream =
    new ByteArrayInputStream(prepareInputBytes(dataType, arraySize, count))

  def prepareInputBytes(
      dataType: ArchitectureDataType,
      arraySize: Int,
      count: Int = 1
  ): Array[Byte] = {
    val inputPrep           = new ByteArrayOutputStream()
    val inputPrepDataStream = new DataOutputStream(inputPrep)

    val seq = (1 to 8).map(_.toFloat).toArray.grouped(arraySize)
    for (s <- seq)
      Util.writeArgs(dataType, inputPrepDataStream, arraySize, s: _*)

    inputPrep.toByteArray()
  }

  def assertOutput(
      dataType: ArchitectureDataType,
      arraySize: Int,
      bytes: Array[Byte],
      count: Int = 1
  ): Unit = {
    val rmse = new RMSE()

    val output =
      new DataInputStream(new ByteArrayInputStream(bytes))

    val result = Util
      .readResult(dataType, output, arraySize, arraySize * 4)
      .grouped(arraySize)
      .map(_.take(2))
      .flatten
      .toArray

    for (i <- 0 until 8)
      rmse.addSample(result(i), Golden(i))

    assert(rmse.compute < dataType.error)
  }

  private val Golden = Seq(
    1f, 5f, 2f, 6f, 3f, 7f, 4f, 8f
  )
}
