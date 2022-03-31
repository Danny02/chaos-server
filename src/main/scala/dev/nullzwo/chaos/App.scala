package dev.nullzwo.chaos

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.*

import java.time.Instant
import scala.concurrent.duration.*
import pureconfig._
import pureconfig.generic.derivation.default._

implicit val runtime: cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global

case class ServerConf(
    requestsPerSecond: Int,
    failureRate: Double,
    maxDelay: Int,
    port: Int
) derives ConfigReader

object App extends IOApp {

  val config = ConfigSource.default.loadOrThrow[ServerConf]
  import config._

  def callService(name: String): IO[String] =
    std.Random.scalaUtilRandom[IO].flatMap(_.nextDouble).flatMap { v =>
      if (v < failureRate) IO.raiseError(new RuntimeException) else IO(s"Hello $name!")
    }

  def routing(rnd: std.Random[IO]) = Router("/" -> HttpRoutes.of[IO] { case _ =>
    for {
      delay <- rnd.nextDouble
      d = delay * delay * delay
      msg <- callService("Hans").delayBy(if (delay > (1 - failureRate)) 99.minutes else (d * maxDelay).millisecond)
      r   <- Ok(msg)
    } yield r
  }).orNotFound

  override def run(args: List[String]): IO[ExitCode] =
    for {
      rnd     <- std.Random.scalaUtilRandom[IO]
      httpApp <- Throttle(requestsPerSecond, 1.second)(routing(rnd))
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(Port.fromInt(port).get)
        .withHttpApp(httpApp)
        .build
        .use(_ => IO.never)
    } yield ExitCode.Success
}
