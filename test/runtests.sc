#!/usr/bin/env amm

/*
 * Run some tests.
 *
 * The jira tests only work, if it is available. Defaults to the
 * values as configured in the container in shell.nix. There must be a
 * project "TEST" with one ticket "TEST-1", but it can be changed
 * using the corresponding options..
 */

import $exec.^.vertec2jira
import cats.implicits._

import TestApi._

case class JiraOpts(
  url: String = "http://10.250.0.2:9090",
  user: String = "admin",
  password: String = "admin",
  version: String = "2",
  ticket: String = "TEST-1"
) {

  def creds: JiraCred = JiraCred(url, user, password, version)

  private val jiraAvailableFlag = new java.util.concurrent.atomic.AtomicReference[Boolean]()

  def jiraAvailable: IO[Boolean] = {
    IO {
      Option(jiraAvailableFlag.get) match {
        case Some(f) => f
        case None =>
          val r = Try(requests.get(url, readTimeout = 1500, connectTimeout = 1500, auth = creds.basic))
          val f = r.toOption.exists(_.is2xx)
          jiraAvailableFlag.set(f)
          f
      }
    }
  }
}

@main
def main(args: String*): Unit =
  Tests.main(args.toArray)

object Tests extends CaseApp[JiraOpts] {
  def run(opts: JiraOpts, remain: RemainingArgs): Unit = {
    val jiraCred = opts.creds

    testIO("print some version") {
      assert("version is not a date formatted string") {
        version.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
      }
    }

    testIO("find jira ticket names in strings") {
      assert("TEST-23 not found") {
        extractIssues("TEST-23; did some more work on x") == List("TEST-23")
      }
      assert("XYZ-24 not found") {
        extractIssues("did some more work on x, XYZ-24") == List("XYZ-24")
      }
      assert("TEST-25 not found") {
        extractIssues("did some more work (TEST-25) on x") == List("TEST-25")
      }
      assert("TEST-26, XYZ-27 not found") {
        extractIssues("did some more work (TEST-25) on x and (XYZ-27) on y") == List("TEST-25", "XYZ-27")
      }
    }

    testWhen(opts.jiraAvailable)("find jira ticket by key") {
      findTicket[IO](jiraCred)(opts.ticket).map { t =>
        assert("ticket not found")(t.isDefined)
      }
    }

    testWhen(opts.jiraAvailable)("add and find worklog") {
      val date = LocalDate.now
      val item = WorkItem(opts.ticket, date, 0, "a comment", jiraCred.user).withHours(0.75)
      addWorklog[IO](jiraCred)(item).
        flatMap(debug(s"tests: Successfully added work log $item")).
        flatMap(_ => findWorklog[IO](jiraCred)(opts.ticket, date)).
        map(t => { assert("added worklog not found")(t.map(_._2) == Some(item)); t }).
        flatMap({
          case Some((id, _)) =>
            deleteWorklog[IO](jiraCred)(opts.ticket, id)
          case None =>
            IO.pure(())
        })
    }

    testIO("use jira supported date time pattern") {
      val item = WorkItem(opts.ticket, LocalDate.of(2018, 12, 9), 0, "a comment", jiraCred.user).withHours(0.75)
      assert(s"invalid date format: ${item.startedString}") {
        item.startedString.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}\\+[0-9]{4}")
      }
    }

    test("read xls file") {
      val opts = Options(pwd/"test-timereport.xlsx", jiraUser = jiraCred.user)
      Stream.emit(opts.xls).
        through(App.processInput[IO](opts)).
        compile.toVector.
        map(v => {
          assert(s"size mismatch: ${v.size} is not 29")(v.size == 29)
          assert(s"seconds mismatch: ${v(0).timeSeconds} not 12600")(v(0).timeSeconds == 12600)
          assert(s"key mismatch: ${v(0).key} not TEST-3284")(v(0).key == "TEST-3284")
          assert(s"date mismatch: ${v(0).started} not 2018-10-23")(v(0).started == LocalDate.of(2018,10,23))

          assert(s"seconds mismatch: ${v(1).timeSeconds} not 5100")(v(1).timeSeconds == 5100)
          assert(s"key mismatch: ${v(1).key} not TEST-84")(v(1).key == "TEST-84")
          assert(s"date mismatch: ${v(1).started} not 2018-10-24")(v(1).started == LocalDate.of(2018,10,24))

          assert(s"seconds mismatch ${v(25).timeSeconds} not 25200")(v(25).timeSeconds == 25200)
          assert(s"key mismatch: ${v(25).key} not TEST-86")(v(25).key == "TEST-86")
          assert(s"date mismatch: ${v(25).started} not 2018-11-29")(v(25).started == LocalDate.of(2018,11,29))
        })
    }

    // run all tests
    TestApi.runAll
  }
}

// --------------------------------------------------------------------------------
// test tool
object TestApi {
  var tests: List[IO[(Int, Int, Int)]] = Nil

  private def wrapTest(name: String, code: IO[Any]): IO[(Int, Int, Int)] =
    IO(println(Colors.white(s"- should $name"))).
      flatMap(_ => code.
        map(_ => println(Colors.green(s"  *** ok ***"))).
        map(_ => (1, 0, 0)).
        handleError(ex => {
          println(Colors.red(s"  *** failed\n  ${ex.getMessage}"))
          (0, 1, 0)
        }))

  private def wrapIgnore(name: String): IO[(Int, Int, Int)] =
    IO(println(Colors.yellow(s"- $name: ignored!"))).map(_ => (0, 0, 1))

  def test(name: String)(code: => IO[Any]): Unit =
    tests = wrapTest(name, code) :: tests

  def testIO(name: String)(code: => Any): Unit =
    test(name)(IO(code))

  def ignore(name: String)(code: => IO[Any]): Unit =
    tests = wrapIgnore(name) :: tests

  def ignoreIO(name: String)(code: => Any): Unit =
    ignore(name)(IO(code))

  def testWhen(flag: IO[Boolean])(name: String)(code: => IO[Any]): Unit =
    tests = (flag.flatMap {
      case true => wrapTest(name, code)
      case false => wrapIgnore(name + " (jira not available)")
    }) :: tests

  def assert(msg: => String)(result: Boolean): Unit =
    if (result) ()
    else sys.error(msg)

  def debug[A](msg: => String): A => IO[A] =
    a => IO(println(Colors.grey(s"  $msg"))).map(_ => a)

  def runAll = {
    val (succ, fail, ign) =
      tests.map(_.unsafeRunSync).
        foldLeft((0,0,0))(_ |+| _)

    print(Console.WHITE)
    println(s"Success: $succ")
    println(s"Failed: $fail")
    println(s"Ignored: $ign")
    print(Console.RESET)
    if (fail > 0) System.exit(1)
    else System.exit(0)
  }
}
