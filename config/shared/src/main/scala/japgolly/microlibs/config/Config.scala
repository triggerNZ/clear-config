package japgolly.microlibs.config

import scalaz._
import Scalaz._
import japgolly.microlibs.stdlib_ext.StdlibExt._
import ConfigInternals._

final case class Key(value: String) extends AnyVal {
  def map(f: String => String): Key =
    Key(f(value))

  def toUpperCase                                  : Key = map(_.toUpperCase)
  def toLowerCase                                  : Key = map(_.toLowerCase)
  def replace(from: Char, to: Char)                : Key = map(_.replace(from, to))
  def replace(from: CharSequence, to: CharSequence): Key = map(_.replace(from, to))
}

trait ConfigValidation[F[_], A] {
  def mapAttempt[B](f: A => String \/ B): F[B]

  def test(errorMsg: A => Option[String]): F[A] =
    mapAttempt(a => errorMsg(a).fold[String \/ A](\/-(a))(-\/.apply))

  def ensure(test: A => Boolean, errorMsg: => String): F[A] =
    this.test(a => if (test(a)) None else Some(errorMsg))

  def mapCatch[B](f: A => B, e: Throwable => String = _.toString): F[B] =
    mapAttempt(a => \/.fromTryCatchNonFatal(f(a)).leftMap(e))

  def mapOption[B](f: A => Option[B], errorMsg: => String = "Not a recognised value."): F[B] =
    mapAttempt(f(_).fold[String \/ B](-\/(errorMsg))(\/-.apply))
}

/**
  * Representation the desire to read `A` from some as-of-yet-unspecified config.
  */
abstract class Config[A] private[config]() extends ConfigValidation[Config, A] {

  private[config] def step[F[_]](implicit F: Applicative[F]): Step[F, StepResult[A]]

  final def run[F[_]](sources: Sources[F])(implicit F: Monad[F]): F[ConfigResult[A]] = {
    type OK = (SourceName, ConfigStore[F])
    type KO = (SourceName, String)

    val r1: Vector[F[KO \/ OK]] = sources.highToLowPri.map(s => F.map(s.prepare)(_.bimap(s.name -> _, s.name -> _)))
    val r2: F[Vector[KO \/ OK]] = r1.sequence
    val r3: F[KO \/ Vector[OK]] = F.map(r2)(_.sequenceU)

    F.bind(r3) {
      case \/-(stores) => step(F).run(R(stores), S.init)._2.map {
        case StepResult.Success(a, _) => ConfigResult.Success(a)
        case StepResult.Failure(x, y) => ConfigResult.QueryFailure(x, y.map(_.public), sources.highToLowPri.map(_.name))
      }
      case -\/((s, err)) => F.pure(ConfigResult.PreparationFailure(s, err))
    }
  }

  final def map[B](f: A => B): Config[B] =
    stepMap(ra => ra.flatMap(a => StepResult.Success(f(a), Set(Origin.Map))))

  override final def mapAttempt[B](f: A => String \/ B): Config[B] = {
    val self = this
    new Config[B] {
      private[config] override def step[F[_]](implicit F: Applicative[F]) =
        for {
          ra <- self.step(F)
          ks <- ConfigInternals.keysUsed.step(F)
        } yield
          ra.map {
            case StepResult.Success(a, os) =>
              f(a) match {
                case \/-(b) => StepResult.Success(b, os)
                case -\/(e) =>

                  var keyed = Map.empty[Key, Option[(SourceName, ConfigValue.Error)]]
                  var other = Set.empty[UnkeyedError]
                  os.iterator.take(2).toList match {
                    case (o: Origin.Read) :: Nil =>
                      val err = ConfigValue.Error(e, Some(o.sourceValue.value))
                      keyed += o.key -> Some ((o.sourceName, err))
                    case _ =>
                      other += UnkeyedError(e, os)
                  }
                  StepResult.Failure(keyed, other)
              }

            case f: StepResult.Failure => f
          }
    }
  }

  private[config] final def stepMap[B](f: StepResult[A] => StepResult[B]): Config[B] = {
    val self = this
    new Config[B] {
      private[config] override def step[F[_]](implicit F: Applicative[F]) =
        self.step(F).map(F.map(_)(f))
    }
  }

  final def withKeyMod(f: String => String): Config[A] =
    keyModCompose(f) *> this <* keyModPop

  final def withCaseInsensitiveKeys: Config[A] =
    withKeyMod(_.toLowerCase)

  final def withPrefix(prefix: String): Config[A] =
    withKeyMod(prefix + _)

  /**
    * Generate a report based on the usage _so far_.
    * This should be at the very end of your Config composition,
    * else the unused-keys portion of the report may be inaccurate.
    */
  final def withReport: Config[(A, ConfigReport)] =
    this tuple Config.reportSoFar
}

object Config {
  implicit val applicativeInstance: Applicative[Config] =
    new Applicative[Config] {
      override def point[A](a: => A) = new Config[A] {
        private[config] override def step[F[_]](implicit F: Applicative[F]) =
          RWS((r, s) => ((), F.point(StepResult.applicativeInstance point a), s))
      }
      override def map[A, B](fa: Config[A])(f: A => B) =
        fa map f
      override def ap[A, B](fa: => Config[A])(ff: => Config[A => B]) = new Config[B] {
        private[config] override def step[F[_]](implicit F: Applicative[F]) = {
          val ga = fa.step[F].getF[S[F], R[F]](idInstance)
          val gf = ff.step[F].getF[S[F], R[F]](idInstance)
          val FR = F.compose(StepResult.applicativeInstance)
          RWS { (r, s0) =>
            val (_, ff, s1) = gf(r, s0)
            val (_, fa, s2) = ga(r, s1)
            ((), FR.ap(fa)(ff), s2)
          }
        }
      }
    }

  def get[A: ConfigParser](key: String): Config[Option[A]] =
    baseGetA[A, Option[A]](key, ApiMethod.Get, (_, o) => o match {
      case Some((origin, a)) => StepResult.Success(Some(a), Set(origin))
      case None              => StepResult.Success(None, Set.empty)
    })

  def getOrUse[A: ConfigParser](key: String, default: => A): Config[A] =
    baseGetA[A, A](key, ApiMethod.GetOrUse(default.toString), (_, o) => o match {
      case Some((origin, a)) => StepResult.Success(a, Set(origin))
      case None              => StepResult.Success(default, Set.empty)
    })

  def need[A: ConfigParser](key: String): Config[A] =
    baseGetA[A, A](key, ApiMethod.Need, (k, o) => o match {
      case Some((origin, a)) => StepResult.Success(a, Set(origin))
      case None              => StepResult.Failure(Map(k -> None), Set.empty)
    })

  def consumerFn[B] = new ConsumerFn[B]
  final class ConsumerFn[B] extends {
    def get[A](k: String, f: B => A => Unit)(implicit r: ConfigParser[A]): Config[B => Unit] =
      Config.get(k)(r).map(oa => b => oa.fold(())(f(b)))

    def getOrUse[A](k: String, f: B => A => Unit)(default: => A)(implicit r: ConfigParser[A]): Config[B => Unit] =
      Config.get(k)(r).map(oa => f(_)(oa getOrElse default))

    def need[A](k: String, f: B => A => Unit)(implicit r: ConfigParser[A]): Config[B => Unit] =
      Config.need(k)(r).map(a => f(_)(a))

    def getC[A](k: String, f: (B, A) => Unit)(implicit r: ConfigParser[A]): Config[B => Unit] =
      get[A](k, f.curried)

    def getOrUseC[A](k: String, f: (B, A) => Unit)(default: => A)(implicit r: ConfigParser[A]): Config[B => Unit] =
      getOrUse[A](k, f.curried)(default)

    def needC[A](k: String, f: (B, A) => Unit)(implicit r: ConfigParser[A]): Config[B => Unit] =
      need[A](k, f.curried)

    def apply(cs: (ConsumerFn[B] => Config[B => Unit])*): Config[B => Unit] =
      mergeConsumerFns(cs.map(_ apply this): _*)
  }


  def mergeConsumerFns[A](cs: Config[A => Unit]*): Config[A => Unit] =
    if (cs.isEmpty)
      ((_: A) => ()).pure[Config]
    else
      cs.reduce(applicativeInstance.apply2(_, _)((f, g) => a => { f(a); g(a) }))

  private def generateReport[F[_]](r: R[F], s: S[F])(implicit F: Applicative[F]): F[ConfigReport] = {
    type M = Map[Key, Map[SourceName, ConfigValue]]
    def emptyM: M = Map.empty
    implicit def semigroupConfigValue: Semigroup[ConfigValue] =
      Semigroup.firstSemigroup // There will never be overlap

    val apiProps: Option[Map[Key, String]] = {
      val m: Map[Key, String] =
        s.apiData
          .mapOrRemoveValues(_.toList match {
            case Nil                         => None
            case ApiMethod.Get         :: Nil => None
            case ApiMethod.Need        :: Nil => None
            case ApiMethod.GetOrUse(u) :: Nil => Some(u)
            case xs@(_ :: _ :: _) => Some(xs.map {
              case ApiMethod.Get         => "<get>"
              case ApiMethod.Need        => "<need>"
              case ApiMethod.GetOrUse(u) => u
            }.sorted.mkString(" / "))
          })
      if (m.isEmpty) None else Some(m)
    }

    var srcs = r.highToLowPri.map(_._1)

    var fUsed: F[M] =
      s.queryCache
        .toVector
        .traverse { case (k, x) => x.highToLowPri.map(x => k -> x.toIterator.map(sv => sv.source -> sv.value).toMap) }
        .map(_.foldLeft(emptyM) { case (m, (k, vs)) => m.setOrModifyValue(k, vs, _ ++ vs) })

    // Add API column to used
    for (ap <- apiProps) {
      srcs :+= SourceName.api
      fUsed = fUsed.map { used =>
        ap.foldLeft(used){ case (q, (k, v)) =>
          q.initAndModifyValue(k, Map.empty, _.updated(SourceName.api, ConfigValue.Found(v)))
        }
      }
    }

    val usedKeys = s.queryCache.keySet

    val fUnused: F[M] =
      r.highToLowPri.traverse { case (src, store) =>
        store.getBulk(!usedKeys.contains(_))
          .map(_.mapValuesNow(value => Map(src -> ConfigValue.Found(value))))
      }.map(_.foldLeft(emptyM)(_ |+| _))


    F.apply2(fUsed, fUnused)((used, unused) =>
      ConfigReport.withDefaults(srcs, used, unused))
  }

  def reportSoFar: Config[ConfigReport] =
    new Config[ConfigReport] {
      private[config] override def step[F[_]](implicit F: Applicative[F]) =
        RWS((r, s) => ((), generateReport(r, s).map(_.point[StepResult]), s))
    }
}
