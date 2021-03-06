package arrows.stdlib

import java.util.Arrays
import scala.util.Try
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.concurrent.ExecutionContext

private[arrows] final object ArrowRun {

  import ArrowImpl._

  final val MaxDepth = 512

  sealed abstract class Result[+T] {
    def simplify: Result[T]
    def cont[B >: T, U](a: Transform[_, B, U], depth: Int)(implicit ec: ExecutionContext): Result[U]

    final def as[U] = this.asInstanceOf[Result[U]]

    final def simplifyGraph: Result[T] = {
      var c = this.simplify
      while (c.isInstanceOf[Defer[_, _]])
        c = c.simplify
      c
    }

    def toFuture: Future[T]
  }

  final class Sync[+T](
    private[this] final var _success: Boolean,
    private[this] final var curr:     Any
  )
    extends Result[T] {

    final def success = _success

    final def unit: Sync[Unit] =
      success(())

    final def success[U](v: U): Sync[U] = {
      _success = true
      curr = v
      this.asInstanceOf[Sync[U]]
    }

    final def failure[U](ex: Throwable): Sync[U] = {
      _success = false
      curr = ex
      this.asInstanceOf[Sync[U]]
    }

    final def value = curr.asInstanceOf[T]

    final def exception = curr.asInstanceOf[Throwable]

    override final def toFuture =
      if (_success) Future.successful(value)
      else Future.failed(exception)

    final def toTry: Try[T] =
      if (success)
        Success(value)
      else
        Failure(exception)

    override final def simplify = this

    override final def cont[B >: T, U](a: Transform[_, B, U], depth: Int)(implicit ec: ExecutionContext) =
      a.runCont(this, depth)
  }

  final class Async[T](
    private[this] final var fut: Future[T]
  )(implicit ec: ExecutionContext)
    extends Result[T] with (Try[T] => Future[T]) {

    private[this] final var stack = new Array[Transform[Any, Any, Any]](10)
    private[this] final var pos = 0

    override final def toFuture = {
      val r = simplify
      if (r eq this)
        fut
      else
        r.toFuture
    }

    private[this] final def runCont(t: Try[T]) = {
      var res: Result[_] =
        t match {
          case t: Success[_] => new Sync(true, t.value)
          case t: Failure[_] => new Sync(false, t.exception)
        }
      var i = 0
      while (i < pos) {
        res = res.cont(stack(i), 0)
        i += 1
      }
      res.as[T]
    }

    override final def apply(t: Try[T]) =
      runCont(t).toFuture

    override final def simplify =
      fut.value match {
        case r: Some[_] =>
          runCont(r.get)
        case _ =>
          fut = fut.transformWith(this)
          this
      }

    override final def cont[B >: T, U](a: Transform[_, B, U], depth: Int)(implicit ec: ExecutionContext) = {
      if (pos == stack.length)
        stack = Arrays.copyOf(stack, pos + pos / 2)
      stack(pos) = a.asInstanceOf[Transform[Any, Any, Any]]
      pos += 1
      this.as[U]
    }
  }

  final object Async {
    final def apply[T](owner: Result[_], fut: Future[T])(implicit ec: ExecutionContext): Result[T] =
      fut.value match {
        case r: Some[_] =>
          owner match {
            case owner: Sync[_] =>
              r.get match {
                case r: Failure[_] => owner.failure(r.exception)
                case r: Success[_] => owner.success(r.value)
              }
            case _ =>
              r.get match {
                case r: Failure[_] => new Sync(false, r.exception)
                case r: Success[_] => new Sync(true, r.value)
              }
          }
        case _ =>
          new Async(fut)
      }
  }

  final class Defer[T, U](r: Sync[T], a: Arrow[T, U])(implicit ec: ExecutionContext) extends Result[U] {
    private final var stacks = Array(new Array[Transform[Any, Any, Any]](MaxDepth + 1))
    private[this] final var pos = 0

    override final def toFuture =
      simplifyGraph.toFuture

    override final def simplify =
      a.runSync(r, 0) match {
        case d: Defer[_, _] =>
          val l = d.stacks.length
          d.stacks = Arrays.copyOf(d.stacks, l + stacks.length)
          System.arraycopy(stacks, 0, d.stacks, l, stacks.length)
          d
        case other =>
          var res: Result[Any] = other
          var i = 0
          while (i < stacks.length) {
            val a = stacks(i)
            var j = 0
            while (j < a.length) {
              val c = a(j)
              if (c != null)
                res = res.cont(c, 0)
              j += 1
            }
            i += 1
          }
          res.asInstanceOf[Result[U]]
      }

    override final def cont[B >: U, V](a: Transform[_, B, V], depth: Int)(implicit ec: ExecutionContext) = {
      stacks(stacks.length - 1)(pos) = a.asInstanceOf[Transform[Any, Any, Any]]
      pos += 1
      this.as[V]
    }
  }

  final def apply[T, U](value: T, a: Arrow[T, U])(implicit ec: ExecutionContext): Future[U] =
    a.runSync(new Sync(true, value), 0).toFuture
}
