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
package testing

import org.specs2.execute._
import org.specs2.specification._
import org.specs2.Specification
import org.specs2.execute.StandardResults._
import ResultLogicalCombinators._

import core._
import application._
import impl.time.SimpleTimer
import impl.ScoobiConfiguration
import core.ScoobiConfiguration

/**
 * This trait provides an Around context to be used in a Specification
 *
 * Subclasses can override the context method:
 *
 *  - def context = inMemory         // execute the code in memory with Scala collections
 *  - def context = local            // execute the code locally
 *  - def context = cluster          // execute the code on the cluster
 *
 * They also need to implement the Cluster trait to specify the location of the remote nodes
 *
 */
trait HadoopExamples extends Hadoop with CommandLineScoobiUserArgs with Cluster { outer =>

  /** make the context available implicitly as an Fixture[ScoobiConfiguration] so that examples taking that context as a parameter can be declared */
  implicit protected def fixtureContext: Fixture[ScoobiConfiguration] = context

  /** define the context to use: local, cluster, localThenCluster */
  def context: Fixture[ScoobiConfiguration] = chain(contexts)

  /**
   * the execution time will not be displayed with this function, but by adding more information to the execution Result
   */
  override def displayTime(prefix: String) = (timer: SimpleTimer) => ()

  /** tests are always in memory by default, unless !inmemory is passed */
  override def isInMemory                = !is("!inmemory")
  /** tests are always local by default, unless !local is passed */
  override def isLocal                   = !is("!local")

  /** context for in memory execution */
  def inMemory: HadoopContext = new InMemoryHadoopContext
  /** context for local execution */
  def local: HadoopContext = new LocalHadoopContext
  /** context for cluster execution */
  def cluster: HadoopContext = new ClusterHadoopContext
  /** context for showing a skipped execution */
  def skippedContext(name: String): HadoopContext = new SkippedHadoopContext(name)
  /** all contexts to run */
  def contexts = Seq(if (isInMemory) inMemory else skippedContext("in memory"),
                     if (isLocal)    local    else skippedContext("local"),
                     if (isCluster)  cluster  else skippedContext("cluster"))

  /** @return a context chaining a sequence of contexts */
  def chain(contexts: Seq[HadoopContext]) = new HadoopContext {
    val configuration = configureForInMemory(ScoobiConfiguration())

    def apply[R : AsResult](r: ScoobiConfiguration => R) =
      changeSeparator {
        // the evaluation of results need to be done by need do avoid evaluating a result if a previous
        // context failed
        contexts.map(c => (() => c(r))).reduceLeftOption[() => Result] { (r1, r2) =>
          () => { r1() and r2() }
        }.getOrElse(() => success: Result)()
      }
  }
  /** execute an example body on the cluster */
  def remotely[R : AsResult](r: =>R) = showResultTime("Cluster execution time", runOnCluster(r))

  /** execute an example body locally */
  def locally[R : AsResult](r: =>R) = showResultTime("Local execution time", runOnLocal(r))

  /** execute an example body locally */
  def inMemory[R : AsResult](r: =>R) = showResultTime("In memory execution time", runInMemory(r))

  /**
   * Context for showing that an execution is skipped
   */
  class SkippedHadoopContext(name: String) extends HadoopContext {
    val configuration = configureForInMemory(ScoobiConfiguration())
    def apply[R : AsResult](r: ScoobiConfiguration => R) =
      Skipped("excluded", "No "+name+" execution"+time_?)
  }
  /**
   * Context for running examples in memory
   */
  class InMemoryHadoopContext extends HadoopContext {
    val configuration = configureForInMemory(ScoobiConfiguration())
    def apply[R : AsResult](r: ScoobiConfiguration => R) = AsResult {
      try     inMemory(r(configuration))
      finally cleanup(configuration)
    }
  }
  /**
   * Context for running examples locally
   */
  class LocalHadoopContext extends HadoopContext {
    val configuration = configureForLocal(ScoobiConfiguration())
    def apply[R : AsResult](r: ScoobiConfiguration => R) = AsResult {
      try     locally(r(configuration))
      finally cleanup(configuration)
    }
  }

  /**
   * Context for running examples on the cluster
   */
  class ClusterHadoopContext extends HadoopContext {
    val configuration = configureForCluster(ScoobiConfiguration())
    def apply[R : AsResult](r: ScoobiConfiguration => R) = AsResult {
      try     remotely(r(configuration))
      finally cleanup(configuration)
    }
  }

  /** cleanup temporary files after job execution */
  def cleanup(c: ScoobiConfiguration) {
    // the 2 actions are isolated. In case the first one fails, the second one has a chance to succeed.
    try     c.deleteWorkingDirectory
    finally TestFiles.deleteFiles(c)
  }

  /** change the separator of a Result */
  private def changeSeparator(r: Result) = r.mapExpected((_:String).replace("; ", "\n"))
  /**
   * trait for creating contexts having ScoobiConfigurations
   *
   * the isLocalOnly method provides a hint to speed-up the execution (because there's no need to upload jars if a run
   * is local)
   */
  trait HadoopContext extends Fixture[ScoobiConfiguration] {
    def time_? = if (outer.showTimes) " time" else ""

    def configuration: ScoobiConfiguration
    override def equals(a: Any) = {
      this.getClass == a.getClass
    }
  }

  /**
   * @return an executed Result updated with its execution time
   */
  private def showResultTime[T : AsResult](prefix: String, t: =>T): Result = {
    if (showTimes) {
      val (result, timer) = withTimer(ResultExecution.execute(t)(AsResult(_)))
      result.updateExpected(prefix+": "+timer.time)
    } else AsResult(t)
  }

}

/**
 * You can use this abstract class to create your own specification class, specifying:
 *
 *  - the type of Specification: mutable or not
 *  - the cluster
 *  - additional variables
 *
 *      class MyHadoopSpec(args: Arguments) extends HadoopSpecificationStructure(args) with
 *        MyCluster with
 *        mutable.Specification
 */
trait HadoopSpecificationStructure extends
  Cluster with
  HadoopExamples with
  UploadedLibJars with
  HadoopLogFactorySetup with
  CommandLineHadoopLogFactory {
}

trait HadoopLogFactorySetup extends LocalHadoop with SpecificationStructure {
  override def map(fs: =>Fragments) = super.map(fs).insert(Step(setLogFactory()))
}

trait CommandLineHadoopLogFactory extends HadoopLogFactorySetup with CommandLineScoobiUserArgs {
  /** for testing, the output must be quiet by default, unless verbose is specified */
  override def quiet = !isVerbose
}

