/**
 * Copyright 2011,2012 National ICT Australia Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nicta.scoobi
package core

import org.apache.hadoop.mapreduce._
import org.apache.hadoop.io.compress.{GzipCodec, CompressionCodec}
import org.apache.hadoop.io.SequenceFile.CompressionType
import org.apache.hadoop.fs._
import org.apache.hadoop.conf.Configuration
import Data._
import org.apache.avro.mapreduce.AvroKeyRecordWriter

/**
 * An output store from a MapReduce job
 */
trait DataSink[K, V, B] extends Sink { outer =>
  lazy val id = ids.get
  lazy val stringId = id.toString

  def outputFormat(implicit sc: ScoobiConfiguration): Class[_ <: OutputFormat[K, V]]
  def outputKeyClass(implicit sc: ScoobiConfiguration): Class[K]
  def outputValueClass(implicit sc: ScoobiConfiguration): Class[V]
  def outputConverter: OutputConverter[K, V, B]
  def outputCheck(implicit sc: ScoobiConfiguration)
  def outputConfigure(job: Job)(implicit sc: ScoobiConfiguration)

  /** @return a compression object if this sink is compressed */
  def compression: Option[Compression]
  /** configure the compression for a given job */
  def configureCompression(configuration: Configuration) = {
    compression foreach  { case Compression(codec, compressionType) =>
      configuration.set("mapred.output.compress", "true")
      configuration.set("mapred.output.compression.type", compressionType.toString)
      configuration.setClass("mapred.output.compression.codec", codec.getClass, classOf[CompressionCodec])
    }
    this
  }

  def isCompressed = compression.isDefined

  def outputSetup(implicit configuration: Configuration) {}

  private [scoobi]
  def write(values: Traversable[_], recordWriter: RecordWriter[_,_])(implicit configuration: Configuration) {
    values foreach { value =>
      val (k, v) = outputConverter.toKeyValue(value.asInstanceOf[B])
      recordWriter.asInstanceOf[RecordWriter[K, V]].write(k, v)
    }
  }
}

/** store the compression parameters for sinks */
case class Compression(codec: CompressionCodec, compressionType: CompressionType = CompressionType.BLOCK)

/**
 * Internal untyped definition of a Sink to store result data
 */
private[scoobi]
trait Sink { outer =>
  /** unique id for this Sink */
  def id: Int
  /** unique id for this Sink, as a string. Can be used to create a file path */
  def stringId: String
  /** The OutputFormat specifying the type of output for this DataSink. */
  def outputFormat(implicit sc: ScoobiConfiguration): Class[_ <: OutputFormat[_, _]]
  /** The Class of the OutputFormat's key. */
  def outputKeyClass(implicit sc: ScoobiConfiguration): Class[_]
  /** The Class of the OutputFormat's value. */
  def outputValueClass(implicit sc: ScoobiConfiguration): Class[_]
  /** Maps the type consumed by this DataSink to the key-values of its OutputFormat. */
  def outputConverter: OutputConverter[_, _, _]
  /** Check the validity of the DataSink specification. */
  def outputCheck(implicit sc: ScoobiConfiguration)
  /** Configure the DataSink. */
  def outputConfigure(job: Job)(implicit sc: ScoobiConfiguration)
  /** @return the path for this Sink. */
  def outputPath(implicit sc: ScoobiConfiguration): Option[Path]
  /** This method is called just before writing data to the sink */
  def outputSetup(implicit configuration: Configuration)

  /** configure the compression for a given job */
  def configureCompression(configuration: Configuration): Sink

  /** @return a new sink with Gzip compression enabled */
  def compress = compressWith(new GzipCodec)
  /** @return a new sink with compression enabled */
  def compressWith(codec: CompressionCodec, compressionType: CompressionType = CompressionType.BLOCK): Sink

  /** @return true if this Sink is compressed */
  private[scoobi]
  def isCompressed: Boolean

  /** write values to this sink, using a specific record writer */
  private [scoobi]
  def write(values: Traversable[_], recordWriter: RecordWriter[_,_])(implicit configuration: Configuration)

}

/**
 * This is a Sink which can also be used as a Source
 */
trait SinkSource extends Sink {
  def toSource: Source

  /** @return the checkpoint parameters if this sink is a Checkpoint */
  def checkpoint: Option[Checkpoint]

  /** @return true if this sink is a checkpoint */
  def isCheckpoint: Boolean = checkpoint.isDefined

  /** @return true if this Sink is a checkpoint and has been filled with data */
  def checkpointExists(implicit sc: ScoobiConfiguration): Boolean =
    isCheckpoint && outputPath.exists(p => sc.fileSystem.exists(p) && Option(sc.fileSystem.listStatus(p)).map(_.nonEmpty).getOrElse(false))

  /** @return the name of the checkpoint */
  def checkpointName: Option[String] = checkpoint.map(_.name)
}

object Data {
  object ids extends UniqueInt
}
/**
 * specify an object on which it is possible to add sinks and to compress them
 */
trait DataSinks {
  type T
  def addSink(sink: Sink): T
  def updateSinks(f: Seq[Sink] => Seq[Sink]): T
  def compressWith(codec: CompressionCodec, compressionType: CompressionType = CompressionType.BLOCK): T
  def compress: T = compressWith(new GzipCodec)
}

/**
 * A Bridge is both a Source and a Sink.
 *
 * It has a bridgeStoreId which is a UUID string.
 *
 * The content of the Bridge can be read as an Iterable to retrieve materialised values
 */
private[scoobi]
trait Bridge extends Source with Sink with SinkSource {
  def bridgeStoreId: String
  def stringId = bridgeStoreId
  def readAsIterable(implicit sc: ScoobiConfiguration): Iterable[_]
  def readAsValue(implicit sc: ScoobiConfiguration) = readAsIterable.iterator.next
}

object Bridge {
  def create(source: Source, sink: SinkSource, bridgeId: String): Bridge = new Bridge with SinkSource {
    def bridgeStoreId = bridgeId
    override def id = sink.id

    def checkpoint = sink.checkpoint
    def inputFormat = source.inputFormat
    def inputCheck(implicit sc: ScoobiConfiguration) { source.inputCheck }
    def inputConfigure(job: Job)(implicit sc: ScoobiConfiguration) { source.inputConfigure(job) }
    def inputSize(implicit sc: ScoobiConfiguration) = source.inputSize
    def fromKeyValueConverter = source.fromKeyValueConverter
    private[scoobi] def read(reader: RecordReader[_,_], mapContext: InputOutputContext, read: Any => Unit) { source.read(reader, mapContext, read) }

    def outputFormat(implicit sc: ScoobiConfiguration) = sink.outputFormat
    def outputKeyClass(implicit sc: ScoobiConfiguration) = sink.outputKeyClass
    def outputValueClass(implicit sc: ScoobiConfiguration) = sink.outputValueClass
    def outputConverter = sink.outputConverter
    def outputCheck(implicit sc: ScoobiConfiguration) { sink.outputCheck }
    def outputConfigure(job: Job)(implicit sc: ScoobiConfiguration) { sink.outputConfigure(job) }
    def outputSetup(implicit configuration: Configuration) { sink.outputSetup }
    def outputPath(implicit sc: ScoobiConfiguration) = sink.outputPath
    def compressWith(codec: CompressionCodec, compressionType: CompressionType = CompressionType.BLOCK) = sink.compressWith(codec, compressionType)
    def configureCompression(configuration: Configuration) = sink.configureCompression(configuration)
    private[scoobi] def isCompressed = sink.isCompressed
    private [scoobi] def write(values: Traversable[_], recordWriter: RecordWriter[_,_])(implicit configuration: Configuration) { sink.write(values, recordWriter) }

    def toSource: Source = source

    def readAsIterable(implicit sc: ScoobiConfiguration) =
      new Iterable[Any] { def iterator = Source.read(source).iterator }
  }
}



