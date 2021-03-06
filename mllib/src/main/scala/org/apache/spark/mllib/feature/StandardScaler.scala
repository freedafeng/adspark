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

package org.apache.spark.mllib.feature

import breeze.linalg.{DenseVector => BDV, SparseVector => BSV}

import org.apache.spark.Logging
import org.apache.spark.annotation.Experimental
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.rdd.RDDFunctions._
import org.apache.spark.mllib.stat.MultivariateOnlineSummarizer
import org.apache.spark.rdd.RDD

/**
 * :: Experimental ::
 * Standardizes features by removing the mean and scaling to unit variance using column summary
 * statistics on the samples in the training set.
 *
 * @param withMean False by default. Centers the data with mean before scaling. It will build a
 *                 dense output, so this does not work on sparse input and will raise an exception.
 * @param withStd True by default. Scales the data to unit standard deviation.
 */
@Experimental
class StandardScaler(withMean: Boolean, withStd: Boolean) extends Logging {

  def this() = this(false, true)

  if (!(withMean || withStd)) {
    logWarning("Both withMean and withStd are false. The model does nothing.")
  }

  /**
   * Computes the mean and variance and stores as a model to be used for later scaling.
   *
   * @param data The data used to compute the mean and variance to build the transformation model.
   * @return a StandardScalarModel
   */
  def fit(data: RDD[Vector]): StandardScalerModel = {
    // TODO: skip computation if both withMean and withStd are false
    val summary = data.treeAggregate(new MultivariateOnlineSummarizer)(
      (aggregator, data) => aggregator.add(data),
      (aggregator1, aggregator2) => aggregator1.merge(aggregator2))
    new StandardScalerModel(withMean, withStd, summary.mean, summary.variance)
  }
}

/**
 * :: Experimental ::
 * Represents a StandardScaler model that can transform vectors.
 *
 * @param withMean whether to center the data before scaling
 * @param withStd whether to scale the data to have unit standard deviation
 * @param mean column mean values
 * @param variance column variance values
 */
@Experimental
class StandardScalerModel private[mllib] (
    val withMean: Boolean,
    val withStd: Boolean,
    val mean: Vector,
    val variance: Vector) extends VectorTransformer {

  require(mean.size == variance.size)

  private lazy val factor: BDV[Double] = {
    val f = BDV.zeros[Double](variance.size)
    var i = 0
    while (i < f.size) {
      f(i) = if (variance(i) != 0.0) 1.0 / math.sqrt(variance(i)) else 0.0
      i += 1
    }
    f
  }

  /**
   * Applies standardization transformation on a vector.
   *
   * @param vector Vector to be standardized.
   * @return Standardized vector. If the variance of a column is zero, it will return default `0.0`
   *         for the column with zero variance.
   */
  override def transform(vector: Vector): Vector = {
    require(mean.size == vector.size)
    if (withMean) {
      vector.toBreeze match {
        case dv: BDV[Double] =>
          val output = vector.toBreeze.copy
          var i = 0
          while (i < output.length) {
            output(i) = (output(i) - mean(i)) * (if (withStd) factor(i) else 1.0)
            i += 1
          }
          Vectors.fromBreeze(output)
        case v => throw new IllegalArgumentException("Do not support vector type " + v.getClass)
      }
    } else if (withStd) {
      vector.toBreeze match {
        case dv: BDV[Double] => Vectors.fromBreeze(dv :* factor)
        case sv: BSV[Double] =>
          // For sparse vector, the `index` array inside sparse vector object will not be changed,
          // so we can re-use it to save memory.
          val output = new BSV[Double](sv.index, sv.data.clone(), sv.length)
          var i = 0
          while (i < output.data.length) {
            output.data(i) *= factor(output.index(i))
            i += 1
          }
          Vectors.fromBreeze(output)
        case v => throw new IllegalArgumentException("Do not support vector type " + v.getClass)
      }
    } else {
      // Note that it's safe since we always assume that the data in RDD should be immutable.
      vector
    }
  }
}
