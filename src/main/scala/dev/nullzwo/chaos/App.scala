package dev.nullzwo.chaos

import cats.data.OptionT
import cats.effect.*
import cats.effect.kernel.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.prometheus.client.CollectorRegistry
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.metrics.prometheus.{Prometheus, PrometheusExportService}
import org.http4s.server.Router
import org.http4s.server.middleware.Throttle.*
import org.http4s.server.middleware.*
import org.http4s.*
import pureconfig.*
import pureconfig.generic.derivation.default.*

import java.time.Instant
import scala.concurrent.duration.*

implicit val runtime: cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global

case class ServerConf(
    requestsPerSecond: Int,
    failureRate: Double,
    maxDelay: Int,
    port: Int
) derives ConfigReader

object App extends IOApp {

  val config = ConfigSource.default.loadOrThrow[ServerConf]
  import config.*

  def callService(name: String): IO[String] =
    std.Random.scalaUtilRandom[IO].flatMap(_.nextDouble).flatMap { v =>
      if (v < failureRate) IO.raiseError(new RuntimeException) else IO(s"Hello $name!")
    }

  override def run(args: List[String]): IO[ExitCode] =
    for {
      rnd <- std.Random.scalaUtilRandom[IO]
      _ <- {
        val metricStuff = for {
          metricsSvc <- PrometheusExportService.build[IO]
          metrics    <- Prometheus.metricsOps[IO](metricsSvc.collectorRegistry, "server")
        } yield (metricsSvc, metrics)

        metricStuff.flatMap { case (metricsSvc, metrics) =>
          val routes = HttpRoutes.of[IO] { case _ =>
            for {
              delay <- rnd.nextDouble
              d = delay * delay * delay
              msg <- callService("Hans").delayBy(
                //if (delay > (1 - failureRate)) 99.minutes else (d * maxDelay).millisecond
                (d * maxDelay).millisecond
              )
              r <- Ok(msg)
            } yield r
          }

          def throttler[F[_]: Functor](reqPerSecond: cats.effect.Ref[F, Long])(http: Http[F, IO]): F[Http[F, IO]] = {
            val refillFrequency =  reqPerSecond.map( 1.second / _)
            val createBucket = refLocal(requestsPerSecond, refillFrequency)
            createBucket.map(bucket => apply(bucket, defaultResponse[G] _)(http))
          }

          Resource.eval(
          (for {
            api <- throttler[[x] =>> OptionT[IO, x]](routes)
          } yield
            Router(
              "/" -> metricsSvc.routes,
              "/api" ->Metrics[IO](metrics)(api)
            ).orNotFound).value.map(_.get))
        }
      }.use{httpApp =>
        EmberServerBuilder
          .default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(Port.fromInt(port).get)
          .withHttpApp(Logger.httpApp(false, false, logAction = Some(IO.println))(httpApp))
          .build
          .use(_ => IO.never)
      }
    } yield ExitCode.Success
}


def refLocal[F[_]: cats.Monad](capacity: Int, refillEveryRef: Ref[F, FiniteDuration])(implicit
                                                            F: Temporal[F]
): F[TokenBucket[F]] = {
  def getTime = F.monotonic.map(_.toNanos)
  val bucket = getTime.flatMap(time => F.ref((capacity.toDouble, time)))

  bucket.map { (counter: Ref[F, (Double, Long)]) =>
    new TokenBucket[F] {
      override def takeToken: F[TokenAvailability] = {
        val attemptUpdate = for {
          t <- counter.access
          refillEvery <- refillEveryRef.get
        } yield {
           val ((previousTokens, previousTime), setter) = t
            getTime.flatMap { currentTime =>
              val timeDifference = currentTime - previousTime
              val tokensToAdd = timeDifference.toDouble / refillEvery.toNanos.toDouble
              val newTokenTotal = Math.min(previousTokens + tokensToAdd, capacity.toDouble)

              val attemptSet: F[Option[TokenAvailability]] =
                if (newTokenTotal >= 1)
                  setter((newTokenTotal - 1, currentTime))
                    .map(_.guard[Option].as(TokenAvailable))
                else {
                  val timeToNextToken = refillEvery.toNanos - timeDifference
                  val successResponse = TokenUnavailable(timeToNextToken.nanos.some)
                  setter((newTokenTotal, currentTime)).map(_.guard[Option].as(successResponse))
                }

              attemptSet
            }
        }

        def loop: F[TokenAvailability] =
          attemptUpdate.flatMap { attempt =>
            attempt.fold(loop)(token => token.pure[F])
          }
        loop
      }
    }
  }
}
