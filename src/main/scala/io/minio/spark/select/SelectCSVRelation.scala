/*
 * Copyright 2018 Minio, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.minio.spark.select

import java.io.InputStreamReader
import java.io.BufferedReader

// Import all utilities
import io.minio.spark.select.util._

// Apache commons
import org.apache.commons.csv.{CSVFormat, QuoteMode}

// For AmazonS3 client
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder

// For BasicAWSCredentials
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration

// Select API
import com.amazonaws.services.s3.AmazonS3URI
import com.amazonaws.services.s3.model.CSVInput
import com.amazonaws.services.s3.model.CSVOutput
import com.amazonaws.services.s3.model.CompressionType
import com.amazonaws.services.s3.model.ExpressionType
import com.amazonaws.services.s3.model.InputSerialization
import com.amazonaws.services.s3.model.OutputSerialization
import com.amazonaws.services.s3.model.SelectObjectContentRequest
import com.amazonaws.services.s3.model.SelectObjectContentResult
import com.amazonaws.services.s3.model.SelectObjectContentEvent
import com.amazonaws.services.s3.model.SelectObjectContentEvent.RecordsEvent
import com.amazonaws.services.s3.model.FileHeaderInfo

import org.apache.commons.csv.{CSVParser, CSVFormat, CSVRecord, QuoteMode}

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql.sources._
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

import scala.collection.mutable.{ListBuffer, ArrayBuffer}

/**
  * Abstract relation class to download data from S3 compatible storage
  */
case class SelectCSVRelation protected[spark] (
  location: Option[String],
  params: Map[String, String],
  userSchema: StructType = null)(@transient val sqlContext: SQLContext)
    extends BaseRelation
    with TableScan
    with PrunedScan
    with PrunedFilteredScan {

  private val pathStyleAccess = params.getOrElse(s"path_style_access", "false") == "true"
  private val endpoint = params.getOrElse(s"endpoint", {
    throw new RuntimeException(s"Endpoint missing from configuration")
  })
  private val region = params.getOrElse(s"region", "us-east-1")
  private val s3Client =
    AmazonS3ClientBuilder.standard()
      .withCredentials(loadFromParams(params))
      .withPathStyleAccessEnabled(pathStyleAccess)
      .withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
      .build()

  override lazy val schema: StructType = Option(userSchema).getOrElse({
      // With no schema we return error.
      throw new RuntimeException(s"Schema cannot be empty")
  })

  private def staticCredentialsProvider(credentials: AWSCredentials): AWSCredentialsProvider = {
    new AWSCredentialsProvider {
      override def getCredentials: AWSCredentials = credentials
      override def refresh(): Unit = {}
    }
  }

  private def loadFromParams(params: Map[String, String]): AWSCredentialsProvider = {
    val accessKey = params.getOrElse(s"access_key", null)
    val secretKey = params.getOrElse(s"secret_key", null)
    if (accessKey != null && secretKey != null) {
      Some(staticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
    } else {
      None
    }
  }.getOrElse {
    // Finally, fall back on the instance profile provider
    new DefaultAWSCredentialsProviderChain()
  }

  private def compressionType(params: Map[String, String]): CompressionType = {
    params.getOrElse("compression", "none") match {
      case "none" => CompressionType.NONE
      case "gzip" => CompressionType.GZIP
      case "bzip2" => CompressionType.BZIP2
    }
  }

  private def headerInfo(params: Map[String, String]): FileHeaderInfo = {
    params.getOrElse("header", "true") match {
      case "false" => FileHeaderInfo.NONE
      case "true" => FileHeaderInfo.USE
    }
  }

  private def selectRequest(location: Option[String], params: Map[String, String],
    schema: StructType, filters: Array[Filter]): SelectObjectContentRequest = {
    val s3URI = new AmazonS3URI(location.getOrElse(""))

    new SelectObjectContentRequest() { request =>
      request.setBucketName(s3URI.getBucket())
      request.setKey(s3URI.getKey())
      request.setExpression(FilterPushdown.queryFromSchema(schema, filters))
      request.setExpressionType(ExpressionType.SQL)

      val inputSerialization = new InputSerialization()
      val csvInput = new CSVInput()
      csvInput.withFileHeaderInfo(headerInfo(params))
      csvInput.withRecordDelimiter('\n')
      csvInput.withFieldDelimiter(params.getOrElse("delimiter", ","))
      inputSerialization.setCsv(csvInput)
      inputSerialization.setCompressionType(compressionType(params))
      request.setInputSerialization(inputSerialization)

      val outputSerialization = new OutputSerialization()
      val csvOutput = new CSVOutput()
      csvOutput.withRecordDelimiter('\n')
      csvOutput.withFieldDelimiter(params.getOrElse("delimiter", ","))
      outputSerialization.setCsv(csvOutput)
      request.setOutputSerialization(outputSerialization)
    }
  }

  private def getRows(schema: StructType, filters: Array[Filter]): List[Array[String]] = {
    var records = new ListBuffer[Array[String]]()
    val br = new BufferedReader(new InputStreamReader(
      s3Client.selectObjectContent(
        selectRequest(
          location,
          params,
          schema,
          filters)
      ).getPayload().getRecordsInputStream()))
    var line : String = null
    while ( {line = br.readLine(); line != null}) {
      records += line.split(",")
    }
    br.close()
    records.toList
  }

  override def toString: String = s"SelectCSVRelation()"

  private def tokenRDD(schema: StructType, filters: Array[Filter]): RDD[Row] = {
    sqlContext.sparkContext.makeRDD(getRows(schema, filters)).mapPartitions{ iter =>
      iter.map { m =>
        var index = 0
        val rowArray = new Array[Any](schema.fields.length)
        while (index < schema.fields.length) {
          val field = schema.fields(index)
          rowArray(index) = TypeCast.castTo(m(index), field.dataType, field.nullable)
          index += 1
        }
        Row.fromSeq(rowArray)
      }
    }
  }

  override def buildScan(): RDD[Row] = {
    tokenRDD(schema, null)
  }

  override def buildScan(columns: Array[String]): RDD[Row] = {
    tokenRDD(pruneSchema(schema, columns), null)
  }

  override def buildScan(columns: Array[String], filters: Array[Filter]): RDD[Row] = {
    tokenRDD(pruneSchema(schema, columns), filters)
  }

  private def pruneSchema(schema: StructType, columns: Array[String]): StructType = {
    val fieldMap = Map(schema.fields.map(x => x.name -> x): _*)
    new StructType(columns.map(name => fieldMap(name)))
  }
}
