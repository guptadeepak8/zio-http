/*
 * Copyright 2021 - 2023 Sporta Technologies PVT LTD & the ZIO HTTP contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http

import java.nio.charset.Charset

import scala.annotation.tailrec

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import zio.stream.{Take, ZChannel, ZStream}

import zio.http.StreamingForm.{Buffer, ZStreamOps}
import zio.http.internal.{FormAST, FormState}

final case class StreamingForm(source: ZStream[Any, Throwable, Byte], boundary: Boundary, bufferSize: Int = 8192) {
  def charset: Charset = boundary.charset

  /**
   * Runs the streaming form and collects all parts in memory, returning a Form
   */
  def collectAll(implicit trace: Trace): ZIO[Any, Throwable, Form] =
    fields.mapZIO {
      case sb: FormField.StreamingBinary =>
        sb.collect
      case other: FormField              =>
        ZIO.succeed(other)
    }.runCollect.map { formData =>
      Form(formData)
    }

  def fields(implicit trace: Trace): ZStream[Any, Throwable, FormField] =
    ZStream.unwrapScoped {
      implicit val unsafe: Unsafe = Unsafe.unsafe

      for {
        runtime    <- ZIO.runtime[Any]
        buffer     <- ZIO.succeed(new Buffer(bufferSize))
        abort      <- Promise.make[Nothing, Unit]
        fieldQueue <- Queue.bounded[Take[Throwable, FormField]](4)
        reader =
          source
            .mapAccumImmediate(initialState) { (state, byte) =>
              def handleBoundary(ast: Chunk[FormAST]): (StreamingForm.State, Option[FormField]) =
                if (state.inNonStreamingPart) {
                  FormField.fromFormAST(ast, charset) match {
                    case Right(formData) =>
                      buffer.reset()
                      (state.reset, Some(formData))
                    case Left(e)         => throw e.asException
                  }
                } else {
                  buffer.reset()
                  (state.reset, None)
                }

              state.formState match {
                case formState: FormState.FormStateBuffer =>
                  val nextFormState = formState.append(byte)
                  state.currentQueue match {
                    case Some(queue) =>
                      val takes = buffer.addByte(crlfBoundary, byte)
                      if (takes.nonEmpty) {
                        runtime.unsafe.run(queue.offerAll(takes).raceFirst(abort.await)).getOrThrowFiberFailure()
                      }
                    case None        =>
                  }
                  nextFormState match {
                    case newFormState: FormState.FormStateBuffer =>
                      if (
                        state.currentQueue.isEmpty &&
                        (newFormState.phase eq FormState.Phase.Part2) &&
                        !state.inNonStreamingPart
                      ) {
                        val contentType = FormField.getContentType(newFormState.tree)
                        if (contentType.binary) {
                          runtime.unsafe.run {
                            for {
                              newQueue <- Queue.bounded[Take[Nothing, Byte]](3)
                              _ <- newQueue.offer(Take.chunk(newFormState.tree.collect { case FormAST.Content(bytes) =>
                                bytes
                              }.flatten))
                              streamingFormData <- FormField
                                .incomingStreamingBinary(newFormState.tree, newQueue)
                                .mapError(_.asException)
                              nextState = state.withCurrentQueue(newQueue)
                            } yield (nextState, Some(streamingFormData))
                          }.getOrThrowFiberFailure()
                        } else {
                          val nextState = state.withInNonStreamingPart(true)
                          (nextState, None)
                        }
                      } else {
                        (state, None)
                      }
                    case FormState.BoundaryEncapsulated(ast)     =>
                      handleBoundary(ast)
                    case FormState.BoundaryClosed(ast)           =>
                      handleBoundary(ast)
                  }
                case _                                    =>
                  (state, None)
              }
            }
            .mapZIO { field =>
              fieldQueue.offer(Take.single(field))
            }
        // FIXME: .blocking here is temporary until we figure out a better way to avoid running effects within mapAccumImmediate
        _ <- ZIO
          .blocking(reader.runDrain)
          .catchAllCause(cause => fieldQueue.offer(Take.failCause(cause)))
          .ensuring(fieldQueue.offer(Take.end))
          .forkScoped
          .interruptible
        _ <- Scope.addFinalizerExit { exit =>
          // If the fieldStream fails, we need to make sure the reader stream can be interrupted, as it may be blocked
          // in the unsafe.run(queue.offer) call (interruption does not propagate into the unsafe.run). This is implemented
          // by setting the abort promise which is raced within the unsafe run when offering the element to the queue.
          abort.succeed(()).when(exit.isFailure)
        }
        fieldStream = ZStream.fromQueue(fieldQueue).flattenTake
      } yield fieldStream
    }

  private def initialState: StreamingForm.State =
    StreamingForm.initialState(boundary)

  private val crlfBoundary: Chunk[Byte] = Chunk[Byte](13, 10) ++ boundary.encapsulationBoundaryBytes
}

object StreamingForm {
  private final class State(
    val formState: FormState,
    private var _currentQueue: Option[Queue[Take[Nothing, Byte]]],
    private var _inNonStreamingPart: Boolean,
  ) {
    def currentQueue: Option[Queue[Take[Nothing, Byte]]] = _currentQueue
    def inNonStreamingPart: Boolean                      = _inNonStreamingPart

    def withCurrentQueue(queue: Queue[Take[Nothing, Byte]]): State = {
      _currentQueue = Some(queue)
      this
    }

    def withInNonStreamingPart(value: Boolean): State = {
      _inNonStreamingPart = value
      this
    }

    def reset: State = {
      _currentQueue = None
      _inNonStreamingPart = false
      formState.reset()
      this
    }
  }

  private def initialState(boundary: Boundary): State = {
    new State(FormState.fromBoundary(boundary), None, _inNonStreamingPart = false)
  }

  private final class Buffer(initialSize: Int) {
    private var buffer: Array[Byte] = new Array[Byte](initialSize)
    private var length: Int         = 0

    private def ensureHasCapacity(requiredCapacity: Int): Unit = {
      @tailrec
      def calculateNewCapacity(existing: Int, required: Int): Int = {
        val newCap = existing * 2
        if (newCap < required) calculateNewCapacity(newCap, required)
        else newCap
      }

      val l = buffer.length
      if (l <= requiredCapacity) {
        val newArray = Array.ofDim[Byte](calculateNewCapacity(l, requiredCapacity))
        java.lang.System.arraycopy(buffer, 0, newArray, 0, l)
        buffer = newArray
      } else ()
    }

    def addByte(
      crlfBoundary: Chunk[Byte],
      byte: Byte,
    ): Chunk[Take[Nothing, Byte]] = {
      ensureHasCapacity(length + crlfBoundary.length)
      buffer(length) = byte
      if (length < (crlfBoundary.length - 1)) {
        // Not enough bytes to check if we have the boundary
        length += 1
        Chunk.empty
      } else {
        var foundBoundary = true
        var i             = 0
        while (i < crlfBoundary.length && foundBoundary) {
          if (buffer(length - i) != crlfBoundary(crlfBoundary.length - 1 - i)) {
            foundBoundary = false
          }
          i += 1
        }

        if (foundBoundary) {
          // We have found the boundary
          val preBoundary =
            Chunk.fromArray(Chunk.fromArray(buffer).take(length + 1 - crlfBoundary.length).toArray[Byte])
          length = 0
          Chunk(Take.chunk(preBoundary), Take.end)
        } else {
          // We don't have the boundary
          if (length < (buffer.length - 2)) {
            length += 1
            Chunk.empty
          } else {
            val preBoundary =
              Chunk.fromArray(Chunk.fromArray(buffer).take(length + 1 - crlfBoundary.length).toArray[Byte])
            for (i <- crlfBoundary.indices) {
              buffer(i) = buffer(length + 1 - crlfBoundary.length + i)
            }
            length = crlfBoundary.length
            Chunk(Take.chunk(preBoundary))
          }
        }
      }
    }

    def reset(): Unit = {
      length = 0
    }
  }

  implicit class ZStreamOps[R, E, A](self: ZStream[R, E, A]) {

    private def mapAccumImmediate[S1, B](
      self: Chunk[A],
    )(s1: S1)(f1: (S1, A) => (S1, Option[B])): (S1, Option[(B, Chunk[A])]) = {
      val iterator          = self.chunkIterator
      var index             = 0
      var s                 = s1
      var result: Option[B] = None
      while (iterator.hasNextAt(index) && result.isEmpty) {
        val a     = iterator.nextAt(index)
        index += 1
        val tuple = f1(s, a)
        s = tuple._1
        result = tuple._2
      }
      (s, result.map(b => (b, self.drop(index))))
    }

    /**
     * Statefully maps over the elements of this stream to sometimes produce new
     * elements. Each new element gets immediately emitted regardless of the
     * upstream chunk size.
     */
    def mapAccumImmediate[S, A1](s: => S)(f: (S, A) => (S, Option[A1]))(implicit trace: Trace): ZStream[R, E, A1] =
      ZStream.succeed(s).flatMap { s =>
        def chunkAccumulator(currS: S, in: Chunk[A]): ZChannel[Any, E, Chunk[A], Any, E, Chunk[A1], Unit] =
          mapAccumImmediate(in)(currS)(f) match {
            case (nextS, Some((a1, remaining))) =>
              ZChannel.write(Chunk.single(a1)) *>
                accumulator(nextS, remaining)
            case (nextS, None)                  =>
              accumulator(nextS, Chunk.empty)
          }

        def accumulator(currS: S, leftovers: Chunk[A]): ZChannel[Any, E, Chunk[A], Any, E, Chunk[A1], Unit] =
          if (leftovers.isEmpty) {
            ZChannel.readWithCause(
              (in: Chunk[A]) => {
                chunkAccumulator(currS, in)
              },
              (err: Cause[E]) => ZChannel.refailCause(err),
              (_: Any) => ZChannel.unit,
            )
          } else {
            chunkAccumulator(currS, leftovers)
          }

        ZStream.fromChannel(self.channel >>> accumulator(s, Chunk.empty))
      }
  }
}
