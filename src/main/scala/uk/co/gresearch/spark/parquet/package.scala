/*
 * Copyright 2023 G-Research
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.gresearch.spark

// hadoop and parquet dependencies provided by Spark
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.metadata.BlockMetaData
import org.apache.parquet.hadoop.{Footer, ParquetFileReader}
import org.apache.spark.sql.execution.datasources.FilePartition
import org.apache.spark.sql.functions.spark_partition_id
import org.apache.spark.sql.{DataFrame, DataFrameReader, Encoder, Encoders}

import scala.collection.JavaConverters.collectionAsScalaIterableConverter

package object parquet {
  private implicit val intEncoder: Encoder[Int] = Encoders.scalaInt

  /**
   * Implicit class to extend a Spark DataFrameReader.
   *
   * @param reader data frame reader
   */
  implicit class ExtendedDataFrameReader(reader: DataFrameReader) {
    /**
     * Read the metadata of Parquet files into a Dataframe.
     *
     * This provides the following per-file information:
     * - filename (string): The file name
     * - blocks (int): Number of blocks / RowGroups in the Parquet file
     * - totalSizeBytes (long): Number of uncompressed bytes of all blocks
     * - compressedSizeBytes (long): Number of compressed bytes of all blocks
     * - totalRowCount (long): Number of rows of all blocks
     * - createdBy (string): The createdBy string of the Parquet file, e.g. library used to write the file
     * - schema (string): The schema
     *
     * @param paths one or more paths to Parquet files or directories
     * @return dataframe with Parquet metadata
     */
    @scala.annotation.varargs
    def parquetMetadata(paths: String*): DataFrame = {
      val df = reader.parquet(paths: _*)
      val files = df.rdd.partitions.flatMap(_.asInstanceOf[FilePartition].files.map(file => SplitFile(file).filePath)).toSeq.distinct

      val spark = df.sparkSession
      import spark.implicits._

      spark.createDataset(files).flatMap { file =>
        val conf = new Configuration()
        val inputPath = new Path(file)
        val inputFileStatus = inputPath.getFileSystem(conf).getFileStatus(inputPath)
        val footers = ParquetFileReader.readFooters(conf, inputFileStatus, false)
        footers.asScala.map { footer =>
          (
            footer.getFile.toString,
            footer.getParquetMetadata.getBlocks.size(),
            footer.getParquetMetadata.getBlocks.asScala.map(_.getTotalByteSize).sum,
            footer.getParquetMetadata.getBlocks.asScala.map(_.getCompressedSize).sum,
            footer.getParquetMetadata.getBlocks.asScala.map(_.getRowCount).sum,
            footer.getParquetMetadata.getFileMetaData.getCreatedBy,
            footer.getParquetMetadata.getFileMetaData.getSchema.toString,
          )
        }
      }.toDF("filename", "blocks", "totalSizeBytes", "compressedSizeBytes", "totalRowCount", "createdBy", "schema")
    }

    /**
     * Read the metadata of Parquet blocks into a Dataframe.
     *
     * This provides the following per-block information:
     * - filename (string): The file name
     * - block (int): Block number starting at 1
     * - startPos (long): Start position of block in Parquet file
     * - totalSizeBytes (long): Number of uncompressed bytes in block
     * - compressedSizeBytes (long): Number of compressed bytes in block
     * - totalRowCount (long): Number of rows in block
     *
     * @param paths one or more paths to Parquet files or directories
     * @return dataframe with Parquet block metadata
     */
    @scala.annotation.varargs
    def parquetBlocks(paths: String*): DataFrame = {
      val df = reader.parquet(paths: _*)
      val files = df.rdd.partitions.flatMap(_.asInstanceOf[FilePartition].files.map(file => SplitFile(file).filePath)).toSeq.distinct

      val spark = df.sparkSession
      import spark.implicits._

      spark.createDataset(files).flatMap { file =>
        val conf = new Configuration()
        val inputPath = new Path(file)
        val inputFileStatus = inputPath.getFileSystem(conf).getFileStatus(inputPath)
        val footers = ParquetFileReader.readFooters(conf, inputFileStatus, false)
        footers.asScala.flatMap { footer =>
          footer.getParquetMetadata.getBlocks.asScala.zipWithIndex.map { case (block, idx) =>
            (
              footer.getFile.toString,
              idx + 1,
              block.getStartingPos,
              block.getTotalByteSize,
              block.getCompressedSize,
              block.getRowCount,
            )
          }
        }
      }.toDF("filename", "block", "startPos", "totalSizeBytes", "compressedSizeBytes", "totalRowCount")
    }

    /**
     * Read the metadata of Parquet block columns into a Dataframe.
     *
     * This provides the following per-block-column information:
     * - filename (string): The file name
     * - block (int): Block number starting at 1
     * - column (string): Block column name
     * - codec (string): The coded used to compress the block column values
     * - type (string): The data type of the block column
     * - encodings (string): Encodings of the block column
     * - minValue (string): Minimum value of this column in this block
     * - maxValue (string): Maximum value of this column in this block
     * - startPos (long): Start position of block column in Parquet file
     * - sizeBytes (long): Number of bytes of this block column
     * - compressedSizeBytes (long): Number of compressed bytes of this block column
     * - valueCount (long): Number of values in this block column
     *
     * @param paths one or more paths to Parquet files or directories
     * @return dataframe with Parquet block metadata
     */
    @scala.annotation.varargs
    def parquetBlockColumns(paths: String*): DataFrame = {
      val df = reader.parquet(paths: _*)
      val files = df.rdd.partitions.flatMap(_.asInstanceOf[FilePartition].files.map(file => SplitFile(file).filePath)).toSeq.distinct

      val spark = df.sparkSession
      import spark.implicits._

      spark.createDataset(files).flatMap { file =>
        val conf = new Configuration()
        val inputPath = new Path(file)
        val inputFileStatus = inputPath.getFileSystem(conf).getFileStatus(inputPath)
        val footers = ParquetFileReader.readFooters(conf, inputFileStatus, false)
        footers.asScala.flatMap { footer =>
          footer.getParquetMetadata.getBlocks.asScala.zipWithIndex.flatMap { case (block, idx) =>
            block.getColumns.asScala.map { column =>
              (
                footer.getFile.toString,
                idx + 1,
                column.getPath.toString,
                column.getCodec.toString,
                column.getPrimitiveType.toString,
                "[" + column.getEncodings.asScala.toSeq.map(_.toString).sorted.mkString(", ") + "]",
                column.getStatistics.minAsString(),
                column.getStatistics.maxAsString(),
                column.getStartingPos,
                column.getTotalUncompressedSize,
                column.getTotalSize,
                column.getValueCount,
              )
            }
          }
        }
      }.toDF("filename", "block", "column", "codec", "type", "encodings", "minValue", "maxValue", "startPos", "sizeBytes", "compressedSizeBytes", "valueCount")
    }

    /**
     * Read the metadata of how Spark partitions Parquet files into a Dataframe.
     *
     * This provides the following per-partition information:
     * - partition (int): The Spark partition id
     * - filename (string): The Parquet file name
     * - start (long): The start position of the partition
     * - end (long): The end position of the partition
     * - partitionLength (long): The length of the partition
     * - fileLength (long): The length of the Parquet file
     * - rows (long): The number of rows of the Parquet file that belong to this partition
     *
     * @param paths one or more paths to Parquet files or directories
     * @return dataframe with Spark Parquet partition metadata
     */
    @scala.annotation.varargs
    def parquetPartitions(paths: String*): DataFrame = {
      val df = reader.parquet(paths: _*)
      val files = df.rdd.partitions.flatMap(part => part.asInstanceOf[FilePartition].files.map(file => (part.index, SplitFile(file)))).toSeq

      val spark = df.sparkSession
      import spark.implicits._

      spark.createDataset(files).flatMap { case (part, file) =>
        val conf = new Configuration()
        val inputPath = new Path(file.filePath)
        val inputFileStatus = inputPath.getFileSystem(conf).getFileStatus(inputPath)
        val footers = ParquetFileReader.readFooters(conf, inputFileStatus, false)
        footers.asScala.map { footer => (
          part,
          footer.getFile.toString,
          file.start,
          file.start + file.length,
          file.length,
          file.fileSize,
          getBlocks(footer, file.start, file.length).map(_.getRowCount).sum
        )}
      }.toDF("partition", "filename", "start", "end", "partitionLength", "fileLength", "rows")
    }
  }

  private def getBlocks(footer: Footer, start: Long, length: Long): Seq[BlockMetaData] = {
    footer.getParquetMetadata.getBlocks.asScala
      .map(block => (block, block.getStartingPos + block.getCompressedSize / 2))
      .filter { case (_, midBlock) => start <= midBlock && midBlock < start + length }
      .map(_._1)
      .toSeq
  }

}
