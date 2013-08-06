package smptk
package registration

import org.scalatest.FunSpec
import org.scalatest.matchers.ShouldMatchers
import breeze.linalg.DenseMatrix
import smptk.image.DiscreteImageDomain1D
import smptk.image.ContinuousScalarImage1D
import geometry._
import geometry.implicits._
import com.sun.org.apache.xpath.internal.operations.Plus
import image.Image._
import smptk.image.DiscreteScalarImage1D
import smptk.image.Interpolation
import breeze.linalg.DenseVector
import smptk.image.Utils
import breeze.plot.Figure
import breeze.plot._
import smptk.io.ImageIO
import java.io.File
import smptk.image.DiscreteImageDomain2D
import smptk.numerics.RandomSVD
import smptk.image.DiscreteImageDomain
import smptk.numerics.GradientDescentOptimizer
import smptk.numerics.GradientDescentConfiguration
import smptk.numerics.LBFGSOptimizer
import smptk.numerics.BreezeStochGradOptimizer
import smptk.numerics.BreezeStochGradOptimizerConfiguration
import smptk.numerics.LBFGSOptimizerConfiguration
import numerics.Integrator
import numerics.IntegratorConfiguration
import numerics.UniformSampler1D
import numerics.UniformSampler2D
import numerics.UniformDistributionRandomSampler1D
import numerics.UniformDistributionRandomSampler2D
import breeze.stats.distributions.Uniform
import smptk.image.ContinuousScalarImage2D
import breeze.stats.distributions.Uniform
import smptk.numerics.{ UniformSampler1D, UniformSampler3D }
import smptk.image.DiscreteImageDomain3D
import smptk.statisticalmodel.{LowRankGaussianProcessConfiguration}
import smptk.statisticalmodel.GaussianProcess._
import smptk.kernels._
import smptk.common.BoxedDomain1D
import smptk.common.BoxedDomain
import smptk.common.BoxedDomain2D
import smptk.image.ContinuousScalarImage1D

class KernelTransformationTests extends FunSpec with ShouldMatchers {

  // TODO add a  test for testing the posterior kernel

  describe("The Nystroem approximation of a Kernel matrix") {
    it("Is close enough to a scalar valued kernel matrix") {
      val kernel = GaussianKernel1D(20)
      val domain = BoxedDomain1D(-5.0, 195.0)

      val sampler = UniformSampler1D(domain)

      val eigPairs = Kernel.computeNystromApproximation(kernel, domain, 100, 500, sampler)

      def approxKernel(x: Point[OneD], y: Point[OneD]) = {
        (0 until eigPairs.size).foldLeft(0.)((sum, i) => {
          val (lambda_i, phi_i) = eigPairs(i)
          sum + lambda_i * phi_i(x)(0) * phi_i(y)(0)
        })
      }

      for ((x, _) <- sampler.sample(10); (y,_) <- sampler.sample(10)) {
        val v1 = kernel(x, y)(0, 0)
        val v2 = approxKernel(x, y)
        (v2 should be(v1 plusOrMinus 0.001f))

      }
    }


    it("Its eigenvalues are close enough to the real eigenvalues for 1D") {
      val kernelDim = 1
      val scalarKernel = GaussianKernel1D(10)
      val domain = BoxedDomain1D(0.,10.)
      val sampler = UniformSampler1D(domain)
      val numPoints = 500
      val (points, _) = sampler.sample(numPoints).unzip
      val eigPairsApprox = Kernel.computeNystromApproximation(scalarKernel, domain, 10, numPoints, sampler)
      val approxLambdas = eigPairsApprox.map(_._1)
      
      val realKernelMatrix = DenseMatrix.zeros[Double](numPoints * kernelDim, numPoints * kernelDim)

      for (i <- 0 until numPoints; j <- 0 until numPoints; di <- 0 until kernelDim; dj <- 0 until kernelDim) {
        realKernelMatrix(i * kernelDim + di, j * kernelDim + dj) = scalarKernel(points(i), points(j))(di, dj)
      }

      //val (_,realrealLambdas,_) = breeze.linalg.svd(realKernelMatrix)
      val (_, realLambdas, _) = RandomSVD.computeSVD(realKernelMatrix * (domain.volume / numPoints), eigPairsApprox.size)

      for (l <- approxLambdas.zipWithIndex)
        l._1 should be(realLambdas(l._2) plusOrMinus (0.1))

    }

    it("Its eigenvalues are close enough to the real eigenvalues in 2D") {

      val kernelDim = 2
      val scalarKernel = GaussianKernel2D(10)
      val ndKernel = UncorrelatedKernelND(scalarKernel, kernelDim)
      val domain = BoxedDomain2D((0., 0.),  (5.0, 5.0))
      val sampler = UniformSampler2D(domain)
      val (pts, _) = sampler.sample(500).unzip

      val eigPairsApprox = Kernel.computeNystromApproximation(ndKernel, domain, 10, 400, sampler)
      val approxLambdas = eigPairsApprox.map(_._1)
  
      
      val realKernelMatrix = DenseMatrix.zeros[Double](pts.size * kernelDim, pts.size * kernelDim)

      for (i <- 0 until pts.size; j <- 0 until pts.size; di <- 0 until kernelDim; dj <- 0 until kernelDim) {
        realKernelMatrix(i * kernelDim + di, j * kernelDim + dj) = ndKernel(pts(i), pts(j))(di, dj)
      }

 
      val (_, realLambdas, _) = RandomSVD.computeSVD(realKernelMatrix * (domain.volume / pts.size), eigPairsApprox.size)
      println("approx lambdas " +approxLambdas)
      println("real lambdas " +realLambdas)

      for (l <- approxLambdas.zipWithIndex)
        l._1 should be(realLambdas(l._2) plusOrMinus (0.1))

    }

    it("It leads to orthogonal basis functions on the domain (-5, 5)") {
      val kernel = GaussianKernel1D(1.0)
      val domain = BoxedDomain1D(-5.0,5.0)
      val sampler = UniformSampler1D(domain)

      val eigPairs = Kernel.computeNystromApproximation(kernel, domain, 100, 500, sampler)

      val integrator = Integrator(IntegratorConfiguration(sampler, 500))

      for (i <- 0 until 20) {

    	val (lambda_i, phi_i) = eigPairs(i)
        val phiImg = new ContinuousScalarImage1D(domain, (x: Point[OneD]) => phi_i(x)(0) * phi_i(x)(0), Some(Point1D => DenseVector[Double](0.)))

        val v = integrator.integrateScalar(phiImg)
        v should be(1. plusOrMinus 0.1)
      }
    }
  }
}