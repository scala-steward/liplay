/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.api.libs

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.util.ByteStringBuilder
import play.api.libs.json.JsValue
import play.api.libs.json.Json

/**
 * Helper function to produce a Comet using <a
 * href="http://doc.akka.io/docs/akka/2.6/scala/stream/index.html">Akka Streams</a>.
 *
 * Please see <a
 * href="https://en.wikipedia.org/wiki/Comet_(programming)">https://en.wikipedia.org/wiki/Comet_(programming)</a>
 * for details of Comet.
 *
 * Example:
 *
 * {{{
 *   def streamClock() = Action {
 *     val df: DateTimeFormatter = DateTimeFormatter.ofPattern("HH mm ss")
 *     val tickSource = Source.tick(0 millis, 100 millis, "TICK")
 *     val source = tickSource.map((tick) => df.format(ZonedDateTime.now()))
 *     Ok.chunked(source via Comet.flow("parent.clockChanged"))
 *   }
 * }}}
 */
object Comet:
  val initialByteString = ByteString.fromString(Array.fill[Char](5 * 1024)(' ').mkString + "<html><body>")

  /**
   * Produces a Flow of escaped ByteString from a series of String elements. Calls out to Comet.flow
   * internally.
   *
   * @param callbackName
   *   the javascript callback method.
   * @return
   *   a flow of ByteString elements.
   */
  def string(callbackName: String): Flow[String, ByteString, NotUsed] =
    Flow[String]
      .map(str => ByteString.fromString("'" + escapeEcmaScript(str) + "'"))
      .via(flow(callbackName))

  /**
   * Produces a flow of ByteString using `Json.fromJson(_).get` from a Flow of JsValue. Calls out to
   * Comet.flow internally.
   *
   * @param callbackName
   *   the javascript callback method.
   * @return
   *   a flow of ByteString elements.
   */
  def json(callbackName: String): Flow[JsValue, ByteString, NotUsed] =
    Flow[JsValue]
      .map { msg => ByteString.fromString(Json.asciiStringify(msg)) }
      .via(flow(callbackName))

  /**
   * Creates a flow of ByteString. Useful when you have objects that are not JSON or String where you may have
   * to do your own conversion.
   *
   * Usage example:
   *
   * {{{
   *   val htmlStream: Source[ByteString, ByteString, NotUsed] = Flow[Html].map { html =>
   *     ByteString.fromString(html.toString())
   *   }
   *   ...
   *   Ok.chunked(htmlStream via Comet.flow("parent.clockChanged"))
   * }}}
   */
  def flow(
      callbackName: String,
      initialChunk: ByteString = initialByteString
  ): Flow[ByteString, ByteString, NotUsed] =
    val cb: ByteString = ByteString.fromString(callbackName)
    Flow.apply[ByteString].map(msg => formatted(cb, msg)).prepend(Source.single(initialChunk))

  private def formatted(callbackName: ByteString, javascriptMessage: ByteString): ByteString =
    val b: ByteStringBuilder = new ByteStringBuilder
    b.append(ByteString.fromString("""<script>"""))
    b.append(callbackName)
    b.append(ByteString.fromString("("))
    b.append(javascriptMessage)
    b.append(ByteString.fromString(");</script>"))
    b.result()

  /**
   * From
   * https://github.com/playframework/twirl/blob/adde5d93e1598ce2665d7c35ab792260c3422f7d/api/shared/src/main/scala/play/twirl/api/utils/StringEscapeUtils.scala#L8-L31
   * Inlined to avoid pulling Twirl into play core.
   */
  private def escapeEcmaScript(input: String): String =
    val s = new StringBuilder()
    val len = input.length
    var pos = 0
    while pos < len do
      input.charAt(pos) match
        // Standard Lookup
        case '\'' => s.append("\\'")
        case '\"' => s.append("\\\"")
        case '\\' => s.append("\\\\")
        case '/'  => s.append("\\/")
        // JAVA_CTRL_CHARS
        case '\b' => s.append("\\b")
        case '\n' => s.append("\\n")
        case '\t' => s.append("\\t")
        case '\f' => s.append("\\f")
        case '\r' => s.append("\\r")
        // Ignore any character below ' '
        case c if c < ' ' =>
        // if it not matches any characters above, just append it
        case c => s.append(c)
      pos += 1

    s.toString()
