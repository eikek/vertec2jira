#!/usr/bin/env amm

/*
 * Ammonite script that reads an Excel Export from vertec and parses
 * the messages for jira tickets. If it finds a corresponding jira
 * ticket, it adds a work log (tempo) item to this jira ticket.
 */

import $ivy.`com.lihaoyi::requests:0.1.6`
import $ivy.`com.lihaoyi::ujson:0.7.1`
import $ivy.`org.apache.poi:poi:4.0.1`
import $ivy.`org.apache.poi:poi-ooxml:4.0.1`
import $ivy.`co.fs2::fs2-core:1.0.2`
import $ivy.`com.github.alexarchambault::case-app:2.0.0-M5`

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import caseapp._
import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import scala.util.Try
import fs2._
import cats.effect.{IO, Sync}
import cats.implicits._
import scala.collection.JavaConverters._
import java.nio.file.{Path => JPath, _}
import java.time._
import java.time.format._
import org.apache.poi.ss.usermodel._
import org.apache.poi.ss.format._
import org.apache.poi.xssf.usermodel._

// Some settings

/** The version of this script.
  */
val version = "2018-12-12"

/** The pattern to look for jira ticket keys in the description of a
  * vertec item.
  */
val issuePattern = "[A-Z]+\\-[0-9]+".r

/** The time zone used for local dates.
  */
val zone = ZoneId.of("Europe/Zurich")

/** Jira really wants exactly this format. Any standards, like iso,
  * don't work.
  */
val jiraDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")


// Main code

@main
def main(args: String*): Unit = {
  App.main(args.toArray)
}

@AppName("Vertec2Jira")
@AppVersion(version)
@ProgName("vertec2jira")
case class Options(
  @ValueDescription("file")@HelpMessage("The Excel file to process.")
  xls: Path,

  @ValueDescription("sheet-number")@HelpMessage("The sheet number in the excel file. Default 0.")
  sheet: Int = 0,

  @HelpMessage("Do not really modify a jira ticket. Default: false.")
  dryRun: Boolean = false,

  @ValueDescription("regex")@HelpMessage("Filter jira issues by a regular expression. Default '.*'")
  issueFilter: String = ".*",

  @HelpMessage("The column in the excel export that denotes the user comment. Default 5.")@ValueDescription("index")
  vertecCommentIndex: Int = 5,

  @HelpMessage("The column in the excel export that denotes the effort (in hours). Default 6.")@ValueDescription("index")
  vertecEffortIndex: Int = 6,

  @HelpMessage("The column in the excel export that denotes the date. Default 3.")@ValueDescription("index")
  vertecDateIndex: Int = 3,

  @HelpMessage("The version of JIRA's rest api to use. Default: 2")@ValueDescription("version")
  jiraRestVersion: String = "2",

  @HelpMessage("The base url to the JIRA installation.")@ValueDescription("url")
  jiraUrl: String = "",

  @HelpMessage("The user to log into JIRA.")
  jiraUser: String = "",

  @HelpMessage("The password used to log into JIRA.")
  jiraPassword: String = "",

  @HelpMessage("A system command that returns the JIRA password as first line.")
  jiraPassCmd: String = "",

  @HelpMessage("If you use the `pass` password manager, specify an entry to use.")
  jiraPassEntry: String = ""
) {

  def jiraLoginExists: Boolean =
    jiraUrl.nonEmpty && jiraUser.nonEmpty && Set(jiraPassCmd, jiraPassEntry, jiraPassword).exists(_.nonEmpty)

  val jiraCred: JiraCred = {
    if (jiraPassEntry.nonEmpty) {
      val (pass :: _) = %%("pass", jiraPassEntry).out.lines.toList
      JiraCred(jiraUrl, jiraUser, pass, jiraRestVersion)
    } else if (jiraPassCmd.nonEmpty) {
      val (pass :: _) = %%(jiraPassCmd.split("\\s+").toSeq).out.lines.toList
      JiraCred(jiraUrl, jiraUser, pass, jiraRestVersion)
    } else {
      JiraCred(jiraUrl, jiraUser, jiraPassword, jiraRestVersion)
    }
  }
}

object App extends CaseApp[Options] {
  def run(opts: Options, remain: RemainingArgs): Unit = {
    if (stat(opts.xls).isDir || !exists(opts.xls)) {
      Console.err.println("Input must be an existing Excel file")
      System.exit(1)
    }
    if (!opts.jiraLoginExists) {
      Console.err.println("A jira login and url must be provided!")
      System.exit(1)
    }

    val program = Stream.emit(opts.xls).
      through(processInput[IO](opts)).
      evalMap(applyWorkItem[IO](opts.dryRun, opts.jiraCred)).
      to(printStdout[IO](opts))

    println(Colors.blue("Running vertec-to-jira …"))
    program.compile.drain.unsafeRunSync
    println(Colors.blue("Done."))
  }

  def processInput[F[_]: Sync](opts: Options): Pipe[F, Path, WorkItem] =
    _.flatMap(readXls[F]).
      map(_.getSheetAt(opts.sheet)).
      flatMap(readRows[F]).
      map(xlsToRow(opts)).
      flatMap({
        case Some(r) => Stream.emit(r)
        case None => Stream.empty
      }).
      evalMap(rowToWorkItem[F](opts.jiraCred.user)).
      flatMap(Stream.emits).
      filter(wi => wi.key.matches(opts.issueFilter))

  def printStdout[F[_]](opts: Options): Sink[F, (WorkItem, ApplyResult)] =
    _.map({ case (input, result) =>
      println(s"Adding work on ${Colors.white(input.key)}, effort ${input.timeSpent} at ${input.started}")
      result match {
        case ApplyResult.Success =>
          if (opts.dryRun) {
            println(Colors.grey(s"- Would add ${input.timeSeconds}s (${input.timeSpent}) to issue ${input.key}, but it's a dry-run"))
          }
          println(Colors.green("- ok"))
        case ApplyResult.TicketNotFound(key) =>
          println(Colors.lightRed(s"- The jira ticket was not found ($key)" ))
        case ApplyResult.WorkItemPresent(pi) =>
          println(Colors.yellow(s"- There is already a work item for this day."))
        case ApplyResult.UnknownError(ex) =>
          println(Colors.boldRed(s"- There was an unknown error: ${ex.getMessage}"))
      }
    })
}

case class JiraCred(url: String, user: String, pass: String, restVersion: String) {
  val basic = new requests.RequestAuth.Basic(user, pass)
}
case class Ticket(key: String, id: Int, summary: String)
case class WorkItem(key: String, started: LocalDate, timeSeconds: Int, comment: String, user: String) {
  def startedString: String = jiraDateFormat.format(started.atTime(LocalTime.NOON).atZone(zone))
  def withHours(h: Double): WorkItem =
    copy(timeSeconds = (h * 60 * 60).toInt)

  def timeSpent: String =
    Duration.ofSeconds(timeSeconds).toString.drop(2).toLowerCase
}
case class RowInput(row: List[XlsValue], tickets: List[String], date: LocalDate, hours: Double, entry: String)

sealed trait XlsValue
object XlsValue {
  case class Str(value: String) extends XlsValue
  case class Bool(value: Boolean) extends XlsValue
  case class Num(value: Double, formatted: Option[String]) extends XlsValue
  case class DateTime(value: Instant) extends XlsValue
  case class Date(value: LocalDate) extends XlsValue
  case class Time(value: LocalTime) extends XlsValue
  case class Formula(value: String) extends XlsValue
}

sealed trait ApplyResult { def isSuccess: Boolean }
object ApplyResult {
  case class TicketNotFound(key: String) extends ApplyResult {
    val isSuccess = false
  }
  case class WorkItemPresent(item: WorkItem) extends ApplyResult {
    val isSuccess = false
  }
  case class UnknownError(ex: Throwable) extends ApplyResult {
    val isSuccess = false
  }
  case object Success extends ApplyResult {
    val isSuccess = true
  }
}

def extractIssues(s: String): List[String] =
  issuePattern.findAllIn(s).toList

def readXls[F[_]: Sync](xls: Path): Stream[F, XSSFWorkbook] =
  Stream.bracket(Sync[F].delay(read.inputStream(xls)))(in => Sync[F].delay(in.close)).
    map(in => new XSSFWorkbook(in))

def readRows[F[_]: Sync](sheet: XSSFSheet): Stream[F, List[XlsValue]] =
  Stream.fromIterator(sheet.iterator.asScala).
    map({ row =>
      row.cellIterator.asScala.
        map({ cell =>
          cell.getCellType match {
            case CellType.STRING =>
              XlsValue.Str(cell.getStringCellValue)

            case CellType.NUMERIC =>
              getNumericValue(cell)

            case CellType.BLANK =>
              XlsValue.Str("")

            case CellType.BOOLEAN =>
              XlsValue.Bool(cell.getBooleanCellValue)

            case CellType.FORMULA =>
              XlsValue.Formula(cell.getCellFormula)

            case ct =>
              Console.err.println(s"Unknown cell type: $ct")
              XlsValue.Str("")
          }
        }).toList
    })

def getNumericValue(cell: Cell): XlsValue= {
  val fmt = Option(cell.getCellStyle).map(_.getDataFormatString).filter(_.trim.nonEmpty)
  fmt match {
    case None =>
      XlsValue.Num(cell.getNumericCellValue, None)

    case Some(pattern) =>
      if (looksLikeDateTimePattern(pattern)) {
        // looks like date pattern?
        XlsValue.DateTime(Instant.ofEpochMilli(cell.getDateCellValue.getTime))
      } else if (looksLikeDatePattern(pattern)) {
        val dt = Instant.ofEpochMilli(cell.getDateCellValue.getTime)
        XlsValue.Date(LocalDate.from(dt.atZone(zone)))
      } else if (looksLikeTimePattern(pattern)) {
        val dt = Instant.ofEpochMilli(cell.getDateCellValue.getTime)
        XlsValue.Time(LocalTime.from(dt.atZone(zone)))
      } else {
        // use number formatter
        val n = cell.getNumericCellValue
        val formatter = new CellNumberFormatter(pattern)
        val sb = new StringBuffer()
        formatter.formatValue(sb, n)
        XlsValue.Num(n, Some(sb.toString))
      }
  }
}

def looksLikeDateTimePattern(s: String): Boolean =
  looksLikeDatePattern(s) && looksLikeTimePattern(s)

def looksLikeDatePattern(s: String): Boolean = {
  val lc = s.toLowerCase
  lc.contains("y") || s.contains("m") || lc.contains("d")
}

def looksLikeTimePattern(s: String): Boolean = {
  val lc = s.toLowerCase
  lc.contains("h") || s.contains("M") || lc.contains("s")
}

def findTicket[F[_]: Sync](jiraCred: JiraCred)(key: String): F[Option[Ticket]] =
  Sync[F].delay {
    val r = requests.get(jiraCred.url+s"/rest/api/${jiraCred.restVersion}/issue/$key", auth = jiraCred.basic, verifySslCerts = false)
    if (r.is2xx) {
      val i = ujson.read(r.text)
      Some(Ticket(i("key").str, i("id").str.toInt, i("fields")("summary").str))
    } else {
      None
    }
  }

def findWorklog[F[_]: Sync](jiraCred: JiraCred)(key: String, date: LocalDate): F[Option[(String, WorkItem)]] =
  Sync[F].delay {
    val r = requests.get(jiraCred.url + s"/rest/api/${jiraCred.restVersion}/issue/${key}/worklog", auth = jiraCred.basic, verifySslCerts = false)
    val json = ujson.read(r.text)
    if (json.obj.contains("worklogs") && json("worklogs").arr.nonEmpty) {
      val logs = json("worklogs").arr
      logs.arr.find(j => {
        Try(j("author")("key").str) == Try(jiraCred.user) &&
        Try(j("started").str).flatMap(parseJiraDate) == Try(date)
        //Try(j("timeSpentSeconds").num.toInt) == Try(item.timeSeconds)
      }).map(json => (json("id").str, WorkItem(key, date, json("timeSpentSeconds").num.toInt, Try(json("comment").str).getOrElse(""), jiraCred.user)))
    } else {
      None
    }
  }

def addWorklog[F[_]: Sync](jiraCred: JiraCred)(item: WorkItem): F[Unit] =
  Sync[F].delay {
    val r = requests.post(jiraCred.url + s"/rest/api/${jiraCred.restVersion}/issue/${item.key}/worklog",
      data = ujson.write(Map("started" -> item.startedString, "timeSpentSeconds" -> item.timeSeconds.toString, "comment" -> item.comment)),
      headers = Map("Content-Type" -> "application/json"),
      verifySslCerts = false,
      auth = jiraCred.basic)

    if (r.is2xx) ()
    else sys.error(s"Cannot add worklog. Http returned: ${r.statusCode} - ${r.text}")
  }

def deleteWorklog[F[_]: Sync](jiraCred: JiraCred)(key: String, id: String): F[Unit] =
  Sync[F].delay {
    val r = requests.delete(jiraCred.url + s"/rest/api/${jiraCred.restVersion}/issue/$key/worklog/$id", auth = jiraCred.basic, verifySslCerts = false)
    if (r.is2xx) ()
    else sys.error(s"Cannot delete worklog item for issue $key (worklog=$id). Http returned: ${r.statusCode}")
  }

def parseJiraDate(s: String): Try[LocalDate] =
  Try(ZonedDateTime.parse(s, jiraDateFormat)).map(_.toLocalDate)

def xlsToRow(opts: Options)(row: List[XlsValue]): Option[RowInput] = {
  val comment = Try(row(opts.vertecCommentIndex)).toOption
  val effort = Try(row(opts.vertecEffortIndex)).toOption
  val date = Try(row(opts.vertecDateIndex)).toOption
  (comment, date, effort) match {
    case (Some(XlsValue.Str(value)), Some(XlsValue.DateTime(dt)), Some(XlsValue.Num(n, _))) =>
      Some(RowInput(row, extractIssues(value), dt.atZone(zone).toLocalDate, n, value))
    case (Some(XlsValue.Str(value)), Some(XlsValue.Date(d)), Some(XlsValue.Num(n, _))) =>
      Some(RowInput(row, extractIssues(value), d, n, value))
    case _ =>
      None
  }
}

def rowToWorkItem[F[_]: Sync](jiraUser: String)(row: RowInput): F[List[WorkItem]] = row.tickets match {
  case Nil =>
    Sync[F].pure(Nil)
  case t :: Nil =>
    Sync[F].pure(List(WorkItem(t, row.date, 0, row.entry, jiraUser).withHours(row.hours)))
  case all =>
    Sync[F].delay {
      println(Colors.yellow(s"There are multiple jira tickets: ${all.mkString(", ")}"))
      println("Vertec entry is:")
      println("")
      println(Colors.boldWhite(s"    ${row.date}/${row.hours}h: ${row.entry}"))
      println("")
      println(s"Type `e` to distribute the effort ${row.hours}h evenly to all tickets or type the")
      println( "tickets (comma separated) you want to add work log items. Type `s` to skip.")
      var result: Option[List[WorkItem]] = None
      while (result.isEmpty) {
        scala.io.StdIn.readLine("Choice: ") match {
          case "s" =>
            result = Some(Nil)
          case "e" =>
            val effort = row.hours / all.size
            result = Some(all.map(jt => WorkItem(jt, row.date, 0, row.entry, jiraUser).withHours(effort)))
          case in if in != null && in.trim.nonEmpty =>
            val selection = in.split(",").map(_.trim).toSet
            if (selection.diff(all.toSet).nonEmpty) {
              println("Some tickets are not in the provided list!")
            } else {
              val effort = row.hours / selection.size
              result = Some(selection.map(jt => WorkItem(jt, row.date, 0, row.entry, jiraUser).withHours(effort)).toList)
            }
          case _ =>
            println("Invalid input")
        }
      }
      result.get
    }
}

def applyWorkItem[F[_]: Sync](dry: Boolean, jiraCred: JiraCred)(item: WorkItem): F[(WorkItem, ApplyResult)] = {
  val checkTicket: F[(WorkItem, ApplyResult)] = findTicket[F](jiraCred)(item.key).
    flatMap({
      case None => Sync[F].pure((item, ApplyResult.TicketNotFound(item.key)))
      case Some(_) => findWorklog(jiraCred)(item.key, item.started).map {
        case None => (item, ApplyResult.Success)
        case Some((_, pi)) => (item, ApplyResult.WorkItemPresent(pi))
      }
    })
  val add = for {
    result <- checkTicket
    _      <- if (result._2.isSuccess && !dry) addWorklog(jiraCred)(item) else Sync[F].pure(())
  } yield result
  add.attempt.map(_.fold(ex => (item, ApplyResult.UnknownError(ex)), identity))
}


implicit def pathArgsParser(implicit cwd: Path): ArgParser[Path] =
  SimpleArgParser.from[Path]("path") { s =>
    Try(Path(s, cwd)).toEither.
      left.map(ex => caseapp.core.Error.MalformedValue("Path", ex.getMessage))
  }

object Colors {

  def blue(s: String): String =
    s"${Console.BLUE}${s}${Console.RESET}"

  def yellow(s: String): String =
    s"${Console.YELLOW}${s}${Console.RESET}"

  def grey(s: String): String =
    s"\u001b[39;2m${s}${Console.RESET}"

  def white(s: String): String =
    s"${Console.WHITE}${s}${Console.RESET}"

  def boldWhite(s: String): String =
    s"\u001b[37;1m${s}${Console.RESET}"

  def red(s: String): String =
    s"${Console.RED}${s}${Console.RESET}"

  def lightRed(s: String): String =
    s"\u001b[31;2m${s}${Console.RESET}"

  def boldRed(s: String): String =
    s"\u001b[31;1m${s}${Console.RESET}"

  def green(s: String): String =
    s"${Console.GREEN}${s}${Console.RESET}"
}
