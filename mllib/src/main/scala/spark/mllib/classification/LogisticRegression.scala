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

package spark.mllib.classification

import spark.{Logging, RDD, SparkContext}
import spark.mllib.optimization._
import spark.mllib.util.MLUtils

import scala.math.round

import org.jblas.DoubleMatrix

/**
 * Logistic Regression using Stochastic Gradient Descent.
 * Based on Matlab code written by John Duchi.
 */
class LogisticRegressionModel(
  val weights: Array[Double],
  val intercept: Double,
  val stochasticLosses: Array[Double]) extends ClassificationModel {

  // Create a column vector that can be used for predictions
  private val weightsMatrix = new DoubleMatrix(weights.length, 1, weights:_*)

  override def predict(testData: spark.RDD[Array[Double]]): RDD[Int] = {
    // A small optimization to avoid serializing the entire model. Only the weightsMatrix
    // and intercept is needed.
    val localWeights = weightsMatrix
    val localIntercept = intercept
    testData.map { x =>
      val margin = new DoubleMatrix(1, x.length, x:_*).mmul(localWeights).get(0) + localIntercept
      round(1.0/ (1.0 + math.exp(margin * -1))).toInt
    }
  }

  override def predict(testData: Array[Double]): Int = {
    val dataMat = new DoubleMatrix(1, testData.length, testData:_*)
    val margin = dataMat.mmul(weightsMatrix).get(0) + this.intercept
    round(1.0/ (1.0 + math.exp(margin * -1))).toInt
  }
}

class LogisticRegression(val opts: GradientDescentOpts) extends Logging {

  /**
   * Construct a LogisticRegression object with default parameters
   */
  def this() = this(new GradientDescentOpts())

  def train(input: RDD[(Int, Array[Double])]): LogisticRegressionModel = {
    val nfeatures: Int = input.take(1)(0)._2.length
    val initialWeights = Array.fill(nfeatures)(1.0)
    train(input, initialWeights)
  }

  def train(
    input: RDD[(Int, Array[Double])],
    initialWeights: Array[Double]): LogisticRegressionModel = {

    // Add a extra variable consisting of all 1.0's for the intercept.
    val data = input.map { case (y, features) =>
      (y.toDouble, Array(1.0, features:_*))
    }

    val initalWeightsWithIntercept = Array(1.0, initialWeights:_*)

    val (weights, stochasticLosses) = GradientDescent.runMiniBatchSGD(
      data,
      new LogisticGradient(),
      new SimpleUpdater(),
      opts,
      initalWeightsWithIntercept)

    val intercept = weights(0)
    val weightsScaled = weights.tail

    val model = new LogisticRegressionModel(weightsScaled, intercept, stochasticLosses)

    logInfo("Final model weights " + model.weights.mkString(","))
    logInfo("Final model intercept " + model.intercept)
    logInfo("Last 10 stochastic losses " + model.stochasticLosses.takeRight(10).mkString(", "))
    model
  }
}

/**
 * Top-level methods for calling Logistic Regression.
 * NOTE(shivaram): We use multiple train methods instead of default arguments to support 
 *                 Java programs.
 */
object LogisticRegression {

  /**
   * Train a logistic regression model given an RDD of (label, features) pairs. We run a fixed
   * number of iterations of gradient descent using the specified step size. Each iteration uses
   * `miniBatchFraction` fraction of the data to calculate the gradient. The weights used in
   * gradient descent are initialized using the initial weights provided.
   *
   * @param input RDD of (label, array of features) pairs.
   * @param numIterations Number of iterations of gradient descent to run.
   * @param stepSize Step size to be used for each iteration of gradient descent.
   * @param miniBatchFraction Fraction of data to be used per iteration.
   * @param initialWeights Initial set of weights to be used. Array should be equal in size to 
   *        the number of features in the data.
   */
  def train(
      input: RDD[(Int, Array[Double])],
      numIterations: Int,
      stepSize: Double,
      miniBatchFraction: Double,
      initialWeights: Array[Double])
    : LogisticRegressionModel =
  {
    val sgdOpts = GradientDescentOpts(stepSize, numIterations, 0.0, miniBatchFraction)
    new LogisticRegression(sgdOpts).train(input, initialWeights)
  }

  /**
   * Train a logistic regression model given an RDD of (label, features) pairs. We run a fixed
   * number of iterations of gradient descent using the specified step size. Each iteration uses
   * `miniBatchFraction` fraction of the data to calculate the gradient.
   *
   * @param input RDD of (label, array of features) pairs.
   * @param numIterations Number of iterations of gradient descent to run.
   * @param stepSize Step size to be used for each iteration of gradient descent.

   * @param miniBatchFraction Fraction of data to be used per iteration.
   */
  def train(
      input: RDD[(Int, Array[Double])],
      numIterations: Int,
      stepSize: Double,
      miniBatchFraction: Double)
    : LogisticRegressionModel =
  {
    val sgdOpts = GradientDescentOpts(stepSize, numIterations, 0.0, miniBatchFraction)
    new LogisticRegression(sgdOpts).train(input)
  }

  /**
   * Train a logistic regression model given an RDD of (label, features) pairs. We run a fixed
   * number of iterations of gradient descent using the specified step size. We use the entire data
   * set to update the gradient in each iteration.
   *
   * @param input RDD of (label, array of features) pairs.
   * @param stepSize Step size to be used for each iteration of Gradient Descent.

   * @param numIterations Number of iterations of gradient descent to run.
   * @return a LogisticRegressionModel which has the weights and offset from training.
   */
  def train(
      input: RDD[(Int, Array[Double])],
      numIterations: Int,
      stepSize: Double)
    : LogisticRegressionModel =
  {
    train(input, numIterations, stepSize, 1.0)
  }

  /**
   * Train a logistic regression model given an RDD of (label, features) pairs. We run a fixed
   * number of iterations of gradient descent using a step size of 1.0. We use the entire data set
   * to update the gradient in each iteration.
   *
   * @param input RDD of (label, array of features) pairs.
   * @param numIterations Number of iterations of gradient descent to run.
   * @return a LogisticRegressionModel which has the weights and offset from training.
   */
  def train(
      input: RDD[(Int, Array[Double])],
      numIterations: Int)
    : LogisticRegressionModel =
  {
    train(input, numIterations, 1.0, 1.0)
  }

  def main(args: Array[String]) {
    if (args.length != 5) {
      println("Usage: LogisticRegression <master> <input_dir> <step_size> " +
        "<regularization_parameter> <niters>")
      System.exit(1)
    }
    val sc = new SparkContext(args(0), "LogisticRegression")
    val data = MLUtils.loadLabeledData(sc, args(1)).map(yx => (yx._1.toInt, yx._2))
    val model = LogisticRegression.train(
      data, args(4).toInt, args(2).toDouble, args(3).toDouble)

    sc.stop()
  }
}
