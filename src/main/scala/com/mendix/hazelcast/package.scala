package com.mendix

import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionStage
import scala.concurrent.{ Future, Promise }

package object hazelcast {
  implicit class CompletionStageOps[A](val completion: CompletionStage[A]) extends AnyVal {
    def toFuture: Future[A] = {
      val promise = Promise[A]()
      completion.whenComplete(complete(promise))
      promise.future
    }

    private def complete(promise: Promise[A])(result: A, exception: Throwable): Unit =
      Option(exception) match {
        case Some(e) => promise.failure(e)
        case None => promise.success(result)
      }
  }

  val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

  def locationString(local: Boolean): String = if (local) "*" else "-"
}
