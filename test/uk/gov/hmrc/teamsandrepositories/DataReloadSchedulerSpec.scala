package uk.gov.hmrc.teamsandrepositories

import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.OptionValues
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.{OneServerPerSuite, PlaySpec}
import play.api.inject.ApplicationLifecycle
import play.api.mvc.Results
import uk.gov.hmrc.teamsandrepositories.config.CacheConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}


class DataReloadSchedulerSpec extends PlaySpec with MockitoSugar with Results with OptionValues with OneServerPerSuite with Eventually {

  val mockCacheConfig = mock[CacheConfig]
  val syncFc = mock[SynchroniserFactory]

  var callCounter = 0
  private val synchroniser = GithubDataSynchroniser{() => callCounter += 1; Future(Nil)}

  when(syncFc.getSynchroniser).thenReturn(synchroniser)

  "reload the cache at the configured intervals" in {
    import scala.concurrent.duration._
    when(mockCacheConfig.teamsCacheDuration).thenReturn(100 millisecond)

    val testMongoLock = new MongoLock(mock[MongoConnector]) {
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        body.map(Some(_))
    }

    val testScheduler =
      new DataReloadScheduler(actorSystem = app.actorSystem,
        applicationLifecycle = mock[ApplicationLifecycle],
        synchroniserFactory = syncFc,
        cacheConfig = mockCacheConfig,
        mongoLock = testMongoLock)

    verify(syncFc, Mockito.timeout(300).atLeast(2)).getSynchroniser
    callCounter must be >=  2

  }
}