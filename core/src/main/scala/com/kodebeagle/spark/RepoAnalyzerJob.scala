/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kodebeagle.spark

import java.io.{File, PrintWriter}

import com.kodebeagle.configuration.KodeBeagleConfig
import com.kodebeagle.indexer.SourceFile
import com.kodebeagle.logging.Logger
import com.kodebeagle.model.GithubRepo.GithubRepoInfo
import com.kodebeagle.model.{GithubRepo, JavaRepo}
import com.kodebeagle.util.SparkIndexJobHelper._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.{SerializableWritable, SparkConf}

import scala.util.Try

object RepoAnalyzerJob extends Logger {

  private val language = "Java"

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setMaster(KodeBeagleConfig.sparkMaster)
      .setAppName("RepoAnalyzerJob")
    val sc = createSparkContext(conf)
    sc.hadoopConfiguration.set("dfs.replication", "1")
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)

    val metaDataLoc = Try {
      val arg = args(0)
      if (arg.contains(",")) {
        arg.split(",").map(s => s"${KodeBeagleConfig.repoMetaDataHdfsPath}/${s.trim}")
      } else {
        Array(arg)
      }
    }.toOption.getOrElse(Array(KodeBeagleConfig.repoMetaDataHdfsPath))

    val df = sqlContext.read.json(metaDataLoc: _*)

    val repoInfos = df.select("id", "name", "full_name", "owner.login",
      "private", "fork", "size", "stargazers_count", "watchers_count",
      "forks_count", "subscribers_count", "default_branch", "language").rdd.map {

      case Row(id: Long, name: String, fullName: String, login: String,
      isPrivate: Boolean, isFork: Boolean, size: Long, stars: Long,
      watchers: Long, forks: Long, subscribers: Long,
      defaultBranch: String, language: String) => GithubRepoInfo(
        id, login, name, fullName, isPrivate, isFork, size, watchers,
        language, forks, subscribers, defaultBranch, stars)
      // This is done to handle the case if the language is null
      case Row(id: Long, name: String, fullName: String, login: String,
      isPrivate: Boolean, isFork: Boolean, size: Long, stars: Long,
      watchers: Long, forks: Long, subscribers: Long,
      defaultBranch: String, _) => GithubRepoInfo(
        id, login, name, fullName, isPrivate, isFork, size, watchers,
        "", forks, subscribers, defaultBranch, stars)
    }

    val confBroadcast = sc.broadcast(new SerializableWritable(sc.hadoopConfiguration))

    // filter: size < 1 GB, and stars > 5 and language in ('Scala', 'Java')
    val javafilesRDD = repoInfos.filter(filterRepo)
      .flatMap(rInfo => GithubRepo(confBroadcast.value.value, rInfo))
      .map(repo => new JavaRepo(repo))
    handleJavaIndices(confBroadcast, javafilesRDD)
  }

  // **** Helper methods for the job ******// 
  private def filterRepo(ri: GithubRepoInfo): Boolean = {
    (ri.size / 1000 < 1000) && ri.stargazersCount > 5 && Option(ri.language).isDefined &&
      Seq("Java", "Scala").exists(_.equalsIgnoreCase(ri.language))
  }

  private def handleJavaIndices(confBroadcast: Broadcast[SerializableWritable[Configuration]],
                                javafileIndicesRDD: RDD[JavaRepo]) = {

    javafileIndicesRDD.foreachPartition((repos: Iterator[JavaRepo]) => {
      val fs = FileSystem.get(confBroadcast.value.value)
      repos.foreach(handleJavaRepos(fs))
    })
  }

  private def handleJavaRepos(fs: FileSystem)(javarepo: JavaRepo) = {
    import scala.sys.process._
    val login = javarepo.baseRepo.repoInfo.get.login
    val repoName = javarepo.baseRepo.repoInfo.get.name

    val srchRefFileName = s"/tmp/kodebeagle-srch-$login~$repoName"
    val srcFileName = s"/tmp/kodebeagle-src-$login~$repoName"
    val metaFileName = s"/tmp/kodebeagle-meta-$login~$repoName"

    val srchrefWriter = new PrintWriter(new File(srchRefFileName))
    val srcWriter = new PrintWriter(new File(srcFileName))
    val metaWriter = new PrintWriter(new File(metaFileName))


    try {
      if (javarepo.files.isEmpty) {
        log.info(s"Repo $login/$repoName does not seem to contain anything java.")
      }
      javarepo.files.foreach(file => {
        val srchRefEntry = toIndexTypeJson("java", "typereference", file.searchableRefs,
          isToken = false)
        val metaDataEntry = toIndexTypeJson("java", "filemetadata", file.fileMetaData,
          isToken = false)
        val sourceEntry = toIndexTypeJson("java", "sourcefile", SourceFile(file.repoId,
          file.repoFileLocation, file.fileContent), isToken = false)

        srchrefWriter.write(srchRefEntry + "\n")
        metaWriter.write(metaDataEntry + "\n")
        srcWriter.write(sourceEntry + "\n")
      })
    } finally {
      Seq(srchrefWriter, srcWriter, metaWriter).foreach(_.close())
    }

    val moveIndex: (String, String) => Unit = moveFromLocal(login, repoName, fs)

    moveIndex(srcFileName, "sources")
    moveIndex(srchRefFileName, "tokens")
    moveIndex(metaFileName, "meta")

    val repoCleanCmd = s"rm -rf /tmp/kodebeagle/$login/$repoName"
    log.info(s"Executing command: $repoCleanCmd")
    repoCleanCmd.!!
    s"rm -f $srcFileName $srchRefFileName $metaFileName".!!
  }

  private def moveFromLocal(login: String, repoName: String, fs: FileSystem)
                           (indxFileName: String, indxName: String) = {
    fs.moveFromLocalFile(new Path(indxFileName),
      new Path(s"${KodeBeagleConfig.repoIndicesHdfsPath}$language/$indxName/$login~$repoName"))
  }
}
