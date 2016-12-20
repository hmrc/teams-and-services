package uk.gov.hmrc.teamsandrepositories

import akka.actor.ActorSystem
import com.google.inject.{Inject, Singleton}
import play.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.teamsandrepositories.config.CacheConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DataReloadScheduler @Inject()(actorSystem: ActorSystem,
                                    applicationLifecycle: ApplicationLifecycle,
                                    githubCompositeDataSource: GitCompositeDataSource,
                                    cacheConfig: CacheConfig,
                                    mongoLock: MongoLock
                                   )(implicit ec: ExecutionContext) {

  private val cacheDuration = cacheConfig.teamsCacheDuration

  println("-" * 100)
  println(cacheDuration)

  private val scheduledReload = actorSystem.scheduler.schedule(cacheDuration, cacheDuration) {
    Logger.info("Scheduled teams repository cache reload triggered")
    reload
  }

  applicationLifecycle.addStopHook(() => Future(scheduledReload.cancel()))

    def reload: Future[Seq[PersistedTeamAndRepositories]] = {
      mongoLock.tryLock {
        Logger.info(s"Starting mongo update")
        githubCompositeDataSource.traverseDataSources
      } map { _.getOrElse(throw new RuntimeException(s"Mongo is locked for ${mongoLock.lockId}"))
      } map { r =>
        Logger.info(s"mongo update completed")
        r
      }
    }


}
