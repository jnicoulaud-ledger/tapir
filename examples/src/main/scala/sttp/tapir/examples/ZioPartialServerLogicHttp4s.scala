package sttp.tapir.examples

import java.util.concurrent.TimeUnit

import org.http4s._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import sttp.client3._
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.tapir.examples.UserAuthenticationLayer._
import sttp.tapir.server.http4s.ztapir._
import sttp.tapir.ztapir._
import zio._
import zio.console._
import zio.interop.catz._

import scala.concurrent.duration.FiniteDuration

object ZioPartialServerLogicHttp4s extends App {

  def greet(input: (User, String)): ZIO[Console, Nothing, String] =
    input match {
      case (user, salutation) =>
        val greeting = s"$salutation, ${user.name}!"

        putStrLn(greeting).as(greeting)
    }

  // 1st approach: define a base endpoint, which has the authentication logic built-in
  val secureEndpoint: ZPartialServerEndpoint[UserService, User, Unit, Int, Unit] = endpoint
    .in(header[String]("X-AUTH-TOKEN"))
    .errorOut(plainBody[Int])
    .zServerLogicForCurrent(UserService.auth)

  // extend the base endpoint to define (potentially multiple) proper endpoints, define the rest of the server logic
  val secureHelloWorld1WithLogic = secureEndpoint.get
    .in("hello1")
    .in(query[String]("salutation"))
    .out(stringBody)
    .serverLogic(greet)

  // ---

  // 2nd approach: define the endpoint entirely first
  val secureHelloWorld2: ZEndpoint[(String, String), Int, String] = endpoint
    .in(header[String]("X-AUTH-TOKEN"))
    .errorOut(plainBody[Int])
    .get
    .in("hello2")
    .in(query[String]("salutation"))
    .out(stringBody)

  // then, provide the server logic in parts
  val secureHelloWorld2WithLogic = secureHelloWorld2
    .zServerLogicPart(UserService.auth)
    .andThen(greet)

  // ---

  // interpreting as routes
  val helloWorldRoutes: HttpRoutes[RIO[UserService with Console, *]] =
    List(secureHelloWorld1WithLogic, secureHelloWorld2WithLogic).toRoutes

  // testing
  val test = AsyncHttpClientZioBackend.managed().use { backend =>
    def testWith(path: String, salutation: String, token: String): Task[String] =
      backend
        .send(
          basicRequest
            .response(asStringAlways)
            .get(uri"http://localhost:8080/$path?salutation=$salutation")
            .header("X-AUTH-TOKEN", token)
        )
        .map(_.body)
        .tap { result => Task(println(s"For path: $path, salutation: $salutation, token: $token, got result: $result")) }

    def assertEquals(at: Task[String], b: String): Task[Unit] =
      at.flatMap { a =>
        if (a == b) Task.succeed(()) else Task.fail(new IllegalArgumentException(s"$a was not equal to $b"))
      }

    assertEquals(testWith("hello1", "Hello", "secret"), "Hello, Spock!") *>
      assertEquals(testWith("hello2", "Hello", "secret"), "Hello, Spock!") *>
      assertEquals(testWith("hello1", "Cześć", "secret"), "Cześć, Spock!") *>
      assertEquals(testWith("hello2", "Cześć", "secret"), "Cześć, Spock!") *>
      assertEquals(testWith("hello1", "Hello", "1234"), AuthenticationErrorCode.toString) *>
      assertEquals(testWith("hello2", "Hello", "1234"), AuthenticationErrorCode.toString)
  }

  // Additional implicits
  implicit def zioTimer[R](implicit r: Runtime[zio.clock.Clock]): cats.effect.Timer[RIO[R, *]] = new cats.effect.Timer[RIO[R, *]] {
    override final def clock: cats.effect.Clock[RIO[R, *]] = zioCatsClock
    override final def sleep(duration: FiniteDuration): RIO[R, Unit] =
      zio.clock.sleep(zio.duration.Duration.fromNanos(duration.toNanos)).provideLayer(ZLayer.succeedMany(r.environment))
  }

  def zioCatsClock[R](implicit r: Runtime[zio.clock.Clock]): cats.effect.Clock[RIO[R, *]] = new cats.effect.Clock[RIO[R, *]] {
    override final def monotonic(unit: TimeUnit): RIO[R, Long] =
      zio.clock.nanoTime.map(unit.convert(_, TimeUnit.NANOSECONDS)).provide(r.environment)
    override final def realTime(unit: TimeUnit): RIO[R, Long] =
      zio.clock.currentTime(unit).provide(r.environment)
  }

  //

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    ZIO.runtime
      .flatMap { implicit runtime: Runtime[ZEnv with UserService with Console] =>
        BlazeServerBuilder[RIO[UserService with Console, *]](runtime.platform.executor.asEC)
          .bindHttp(8080, "localhost")
          .withHttpApp(Router("/" -> helloWorldRoutes).orNotFound)
          .resource
          .use { _ =>
            test
          }
      // starting
      }
      .provideCustomLayer(UserService.live)
      .exitCode
}

object UserAuthenticationLayer {
  type UserService = Has[UserService.Service]

  case class User(name: String)
  val AuthenticationErrorCode = 1001

  object UserService {
    trait Service {
      def auth(token: String): IO[Int, User]
    }

    val live: ZLayer[Any, Nothing, Has[Service]] = ZLayer.succeed(new Service {
      def auth(token: String): IO[Int, User] = {
        if (token == "secret") IO.succeed(User("Spock"))
        else IO.fail(AuthenticationErrorCode)
      }
    })

    def auth(token: String): ZIO[UserService, Int, User] = ZIO.accessM(_.get.auth(token))
  }

}
