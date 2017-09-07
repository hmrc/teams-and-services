package uk.gov.hmrc.teamsandrepositories.services

import java.time.LocalDateTime

import com.google.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import uk.gov.hmrc.githubclient.{GhTeam, GithubApiClient}
import uk.gov.hmrc.teamsandrepositories.config.GithubConfig
import uk.gov.hmrc.teamsandrepositories.persitence.{MongoConnector, TeamsAndReposPersister}
import uk.gov.hmrc.teamsandrepositories.persitence.model.TeamRepositories
import uk.gov.hmrc.teamsandrepositories.BlockingIOExecutionContext

import scala.concurrent.Future
import scala.util.{Failure, Success}


@Singleton
class GitCompositeDataSource @Inject()(val githubConfig: GithubConfig,
                                       val persister: TeamsAndReposPersister,
                                       val mongoConnector: MongoConnector,
                                       val githubApiClientDecorator: GithubApiClientDecorator) {


  import BlockingIOExecutionContext._

  lazy val logger = LoggerFactory.getLogger(this.getClass)

  val gitApiEnterpriseClient: GithubApiClient =
    githubApiClientDecorator.githubApiClient(githubConfig.githubApiEnterpriseConfig.apiUrl, githubConfig.githubApiEnterpriseConfig.key)

  val enterpriseTeamsRepositoryDataSource: GithubV3RepositoryDataSource =
    new GithubV3RepositoryDataSource(githubConfig, gitApiEnterpriseClient, persister, isInternal = true)

  val gitOpenClient: GithubApiClient =
    githubApiClientDecorator.githubApiClient(githubConfig.githubApiOpenConfig.apiUrl, githubConfig.githubApiOpenConfig.key)

  val openTeamsRepositoryDataSource: GithubV3RepositoryDataSource =
    new GithubV3RepositoryDataSource(githubConfig, gitOpenClient, persister, isInternal = false)

  val dataSources = List(enterpriseTeamsRepositoryDataSource, openTeamsRepositoryDataSource)


  //!@ test
  def persistTeamRepoMapping_new: Future[Seq[TeamRepositories]] = {
    val teamsOrderedByUpdateDate: Future[Seq[TeamRepositories]] = persister.getAllTeams.map(_.sortBy(_.updateDate))

    groupAndOrderTeamsAndTheirDataSources(teamsOrderedByUpdateDate).flatMap((ts: Seq[OneTeamAndItsDataSources]) =>
      Future.sequence(ts.map { aTeam: OneTeamAndItsDataSources =>
        getRepositoriesForTeam(aTeam).map(mergeRepositoriesForTeam(aTeam.teamName, _)).flatMap(persister.update)
      })).map(_.toSeq)
  }

  def getRepositoriesForTeam(aTeam: OneTeamAndItsDataSources): Future[Seq[TeamRepositories]] = {
    Future.sequence(aTeam.teamAndDataSources.map { teamAndDataSource =>
      teamAndDataSource.dataSource.mapTeam_new(teamAndDataSource.organisation, teamAndDataSource.team)
    })
  }

  //!@ test
  def groupAndOrderTeamsAndTheirDataSources(teamNamesOrderedByUpdateDateF: Future[Seq[TeamRepositories]]): Future[Seq[OneTeamAndItsDataSources]] = {
    for {
      teamsAndTheirOrgAndDataSources <- Future.sequence(dataSources.map(ds => ds.getTeamsWithOrgAndDataSourceDetails))
      teamNamesOrderedByUpdateDate <- teamNamesOrderedByUpdateDateF
    } yield {
      val teamNameToSources: Map[String, List[TeamAndOrgAndDataSource]] = teamsAndTheirOrgAndDataSources.flatten.groupBy(_.team.name)
      teamNameToSources.map { case (teamName, tds) => {
        OneTeamAndItsDataSources(teamName, tds, teamNamesOrderedByUpdateDate.find(_.teamName == teamName).fold(0L)(_.updateDate))
      }}
    }.toSeq.sortBy(_.updateDate)
    
  }

  def mergeRepositoriesForTeam(teamName: String, aTeamAndItsRepositories: Seq[TeamRepositories]) = {
    aTeamAndItsRepositories.foldLeft(TeamRepositories(teamName, Nil)) { case (acc, tr) =>
      acc.copy(repositories = acc.repositories ++ tr.repositories)
    }
  }

  def persistTeamRepoMapping: Future[Seq[TeamRepositories]] = {

    Future.sequence(dataSources.map(_.getTeamRepoMapping)).map { results =>
      val flattened: List[TeamRepositories] = results.flatten

      logger.info(s"Combining ${flattened.length} results from ${dataSources.length} sources")
      Future.sequence(flattened.groupBy(_.teamName).map { case (name, teams) =>
        TeamRepositories(name, teams.flatMap(t => t.repositories).sortBy(_.name))
      }.toList.map(tr => persister.update(tr)))
    }.flatMap(identity).andThen {
      case Failure(t) => throw t
      case Success(_) => persister.updateTimestamp(LocalDateTime.now())
    }
  }

  def removeOrphanTeamsFromMongo(teamRepositoriesFromGh: Seq[TeamRepositories]) = {

    val teamNamesFromMongo: Future[Set[String]] = {
      persister.getAllTeamAndRepos.map { case (allPersistedTeamAndRepositories, _) =>
        allPersistedTeamAndRepositories.map(_.teamName).toSet
      }
    }

    val teamNamesFromGh = teamRepositoriesFromGh.map(_.teamName)

    val orphanTeams: Future[Set[String]] = for {
      mongoTeams <- teamNamesFromMongo
    } yield mongoTeams.filterNot(teamNamesFromGh.toSet)

    orphanTeams.flatMap { (teamNames: Set[String]) =>
      logger.info(s"Removing these orphan teams:[${teamNames}]")
      persister.deleteTeams(teamNames)
    }
  }


}

@Singleton
case class GithubApiClientDecorator @Inject()() {
  def githubApiClient(apiUrl: String, apiToken: String) = GithubApiClient(apiUrl, apiToken)
}
