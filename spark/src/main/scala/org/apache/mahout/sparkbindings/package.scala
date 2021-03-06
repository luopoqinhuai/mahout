/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout

import org.apache.spark.rdd.RDD
import org.apache.mahout.math.{Matrix, Vector}
import scala.reflect.ClassTag
import org.apache.spark.{SparkConf, SparkContext}
import java.io._
import scala.collection.mutable.ArrayBuffer
import org.apache.mahout.common.IOUtils
import org.apache.log4j.Logger
import org.apache.mahout.sparkbindings.drm.DrmLike

/**
 * @author dmitriy
 */
package object sparkbindings {

  private[sparkbindings] val s_log = Logger.getLogger("org.apache.mahout.sparkbindings")

  /**
   * Create proper spark context that includes local Mahout jars
   * @param masterUrl
   * @param appName
   * @param customJars
   * @return
   */
  def mahoutSparkContext(masterUrl: String, appName: String,
      customJars: TraversableOnce[String] = Nil): SparkContext = {
    val closeables = new java.util.ArrayDeque[Closeable]()

    try {

      val conf = if (masterUrl != "local") {
        var mhome = System.getenv("MAHOUT_HOME")
        if (mhome == null) mhome = System.getProperty("mahout.home")

        if (mhome == null)
          throw new IllegalArgumentException("MAHOUT_HOME is required to spawn mahout-based spark jobs.")

        // Figure Mahout classpath using $MAHOUT_HOME/mahout classpath command.

        val fmhome = new File(mhome)
        val bin = new File(fmhome, "bin")
        val exec = new File(bin, "mahout")
        if (!exec.canExecute)
          throw new IllegalArgumentException("Cannot execute %s.".format(exec.getAbsolutePath))

        val p = Runtime.getRuntime.exec(Array(exec.getAbsolutePath, "classpath"))

        closeables.addFirst(new Closeable {
          def close() {
            p.destroy()
          }
        })

        val r = new BufferedReader(new InputStreamReader(p.getInputStream))
        closeables.addFirst(r)

        val w = new StringWriter()
        closeables.addFirst(w)

        var continue = true;
        val jars = new ArrayBuffer[String]()
        do {
          val cp = r.readLine()
          if (cp == null)
            throw new IllegalArgumentException("Unable to read output from \"mahout classpath\"")

          val j = cp.split(File.pathSeparatorChar)
          if (j.size > 10) {
            // assume this is a valid classpath line
            jars ++= j
            continue = false
          }
        } while (continue)

        //      if (s_log.isDebugEnabled) {
        //        s_log.debug("Mahout jars:")
        //        jars.foreach(j => s_log.debug(j))
        //      }

        // context specific jars
        val mcjars = jars.filter(j =>
          j.matches(".*mahout-math-.*\\.jar") ||
              j.matches(".*mahout-math-scala-.*\\.jar") ||
              j.matches(".*mahout-core-.*\\.jar") ||
              j.matches(".*mahout-spark-.*\\.jar")
        ).filter(!_.matches(".*-tests.jar")) ++
            SparkContext.jarOfClass(classOf[DrmLike[_]]) ++ customJars

        if (s_log.isDebugEnabled) {
          s_log.debug("Mahout jars:")
          mcjars.foreach(j => s_log.debug(j))
        }

        new SparkConf().setJars(jars = mcjars.toSeq)

      } else new SparkConf()

      conf.setAppName(appName).setMaster(masterUrl)
          .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
          .set("spark.kryo.registrator", "org.apache.mahout.sparkbindings.io.MahoutKryoRegistrator")

      new SparkContext(config = conf)

    } finally {
      IOUtils.close(closeables)
    }

  }


}
