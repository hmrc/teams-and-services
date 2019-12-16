package uk.gov.hmrc.teamsandrepositories.services

import java.time.Instant
import com.google.inject.{Inject, Singleton}
import com.kenshoo.play.metrics.Metrics
import play.api.{Configuration, Logger}
import uk.gov.hmrc.githubclient.{GhTeam, GithubApiClient}
import uk.gov.hmrc.teamsandrepositories.config.GithubConfig
import uk.gov.hmrc.teamsandrepositories.controller.BlockingIOExecutionContext
import uk.gov.hmrc.teamsandrepositories.helpers.FutureHelpers
import uk.gov.hmrc.teamsandrepositories.persitence.TeamsAndReposPersister
import uk.gov.hmrc.teamsandrepositories.persitence.model.TeamRepositories
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class Timestamper {
  def timestampF() = Instant.now().toEpochMilli
}

@Singleton
case class PersistingService @Inject()(
  githubConfig: GithubConfig,
  persister: TeamsAndReposPersister,
  githubApiClientDecorator: GithubApiClientDecorator,
  timestamper: Timestamper,
  metrics: Metrics,
  configuration: Configuration,
  futureHelpers: FutureHelpers) {

  private val defaultMetricsRegistry = metrics.defaultRegistry

  val repositoriesToIgnore: List[String] =
    configuration.getOptional[Seq[String]]("shared.repositories").map(_.toList).getOrElse(List.empty[String])

  val gitOpenClient: GithubApiClient =
    githubApiClientDecorator
      .githubApiClient(githubConfig.githubApiOpenConfig.apiUrl, githubConfig.githubApiOpenConfig.key)

  val dataSource: GithubV3RepositoryDataSource =
    new GithubV3RepositoryDataSource(
      githubConfig,
      gitOpenClient,
      timestamper.timestampF,
      defaultMetricsRegistry,
      repositoriesToIgnore,
      futureHelpers
    )

  def persistTeamRepoMapping(implicit ec: ExecutionContext): Future[Seq[TeamRepositories]] = {
    val persistedTeamsF = persister.getAllTeamsAndRepos
    (for {
       sortedGhTeams   <- teamsOrderedByUpdateDate(persistedTeamsF)
       withTeams       <- Future.traverse(sortedGhTeams) { ghTeam =>
                            dataSource
                              .mapTeam(ghTeam, persistedTeamsF)
                              .map(tr => tr.copy(repositories = tr.repositories.sortBy(_.name)))
                              .flatMap(persister.update)
                          }
       withoutTeams    <- getRepositoriesWithoutTeams(withTeams).flatMap(persister.update)
     } yield withTeams :+ withoutTeams
    ).recoverWith {
      case NonFatal(ex) =>
        Logger.error("Could not persist to teams repo.", ex)
        Future.failed(ex)
    }
  }

  def getRepositoriesWithoutTeams(
      persistedReposWithTeams: Seq[TeamRepositories]
    )( implicit ec: ExecutionContext
    ): Future[TeamRepositories] =
    dataSource.getAllRepositories
      .map { repos =>
        val reposWithoutTeams = {
          val urlsOfPersistedRepos = persistedReposWithTeams.flatMap(_.repositories.map(_.url)).toSet
          repos.filterNot(r => urlsOfPersistedRepos.contains(r.url))
        }
        TeamRepositories(
          teamName     = TeamRepositories.TEAM_UNKNOWN,
          repositories = reposWithoutTeams,
          updateDate   = timestamper.timestampF()
        )
      }

  private def teamsOrderedByUpdateDate(
      persistedTeamsF: Future[Seq[TeamRepositories]]
    )( implicit ec: ExecutionContext
    ): Future[List[GhTeam]] =
    for {
      ghTeams        <- dataSource.getTeamsForHmrcOrg
      persistedTeams <- persistedTeamsF
    } yield {
      ghTeams
        .map { ghTeam =>
          val updateDate = persistedTeams.find(_.teamName == ghTeam.name).fold(0L)(_.updateDate)
          (updateDate, ghTeam)
        }
        .sortBy(_._1)
        .map {
          case (_, team) => team
        }
    }

  def removeOrphanTeamsFromMongo(
       teamRepositoriesFromGh: Seq[TeamRepositories]
    )( implicit ec: ExecutionContext
    ): Future[Set[String]] = {
    import BlockingIOExecutionContext._

    for {
      mongoTeams      <- persister.getAllTeamsAndRepos.map(_.map(_.teamName).toSet)
      teamNamesFromGh =  teamRepositoriesFromGh.map(_.teamName)
      orphanTeams     =  mongoTeams.filterNot(teamNamesFromGh.toSet)
      _               =  Logger.info(s"Removing these orphan teams:[$orphanTeams]")
      deleted         <- persister.deleteTeams(orphanTeams)
    } yield deleted

  }.recover {
    case e =>
      Logger.error("Could not remove orphan teams from mongo.", e)
      throw e
  }
}

@Singleton
case class GithubApiClientDecorator @Inject()() {
  def githubApiClient(apiUrl: String, apiToken: String) = GithubApiClient(apiUrl, apiToken)
}
