/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.teamsandrepositories

import java.time.LocalDateTime
import java.util.Date

import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.SpanSugar
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import uk.gov.hmrc.githubclient
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.teamsandrepositories.config.GithubConfig

import scala.concurrent.{ExecutionContext, Future}


class GithubV3RepositoryDataSourceSpec extends WordSpec with ScalaFutures with Matchers with DefaultPatienceConfig with MockitoSugar with SpanSugar with BeforeAndAfterEach {

  trait Setup  {
    val githubClient = mock[GithubApiClient]
    val persister = new StubTeamsAndReposPersister
    val dataSource = new GithubV3RepositoryDataSource(githubConfig, githubClient, persister, isInternal = false)

    when(githubClient.repoContainsContent(anyString(),anyString(),anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))
    when(githubClient.getFileContent(anyString(),anyString(),anyString())(any[ExecutionContext])).thenReturn(Future.successful(None))
    when(githubClient.getTags(anyString(),anyString())(any[ExecutionContext])).thenReturn(Future.successful(List.empty))

    when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
    when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
  }

  val now = new Date().getTime

  val testHiddenRepositories = List("hidden_repo1", "hidden_repo2")
  val testHiddenTeams = List("hidden_team1", "hidden_team2")

  val githubConfig: GithubConfig = mock[GithubConfig]

  val ec = BlockingIOExecutionContext.executionContext

  class StubTeamsAndReposPersister extends TeamsAndReposPersister(mock[MongoTeamsAndRepositoriesPersister], mock[MongoUpdateTimePersister]) {
    var captor: List[TeamRepositories] = Nil

    override def update(teamsAndRepositories: TeamRepositories): Future[TeamRepositories] = {
      captor +:= teamsAndRepositories
      Future(teamsAndRepositories)(ec)
    }

    override def getAllTeamAndRepos: Future[(Seq[TeamRepositories], Option[LocalDateTime])] =
      Future.successful((Nil, None))
  }

  "Github v3 Data Source" should {

    "Return a list of teams and repositories, filtering out forks" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1),githubclient.GhOrganisation("DDCN",2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1),githubclient.GhTeam("B", 2))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("C", 3),githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description",  1, "url_A", fork = false, now, now), githubclient.GhRepository("A_r2",  "some description",  5, "url_A2", fork = true, now, now))))
      when(githubClient.getReposForTeam(2)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("B_r",   "some description", 2, "url_B", fork = false, now, now))))
      when(githubClient.getReposForTeam(3)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("C_r",   "some description", 3, "url_C", fork = false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r",   "some description", 4, "url_D", fork = true, now, now))))

      val eventualPersistedTeamAndRepositorieses = dataSource.getTeamRepoMapping
      eventualPersistedTeamAndRepositorieses.futureValue shouldBe List(
        TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now))),
        TeamRepositories("B", List(GitRepository("B_r", "some description", "url_B", now, now))),
        TeamRepositories("C", List(GitRepository("C_r", "some description", "url_C", now, now))),
        TeamRepositories("D", List()))
    }

    "Set internal = true if the DataSource is marked as internal" in new Setup {

      val internalDataSource = new GithubV3RepositoryDataSource(githubConfig, githubClient, persister, isInternal = true)

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", fork = false, now, now))))

      internalDataSource.getTeamRepoMapping.futureValue shouldBe List(
        TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, isInternal = true))))
    }

    "Filter out repositories according to the hidden config" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(
        githubclient.GhRepository("hidden_repo1", "some description", 1, "url_A", false, now, now),
        githubclient.GhRepository("A_r",   "some description", 2, "url_A", false, now, now))))
      
      dataSource.getTeamRepoMapping.futureValue shouldBe List(
        TeamRepositories("A", List(GitRepository("A_r" , "some description", "url_A", now, now))))
    }
    
    "Filter out teams according to the hidden config" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("hidden_team1", 1), githubclient.GhTeam("B", 2))))
      when(githubClient.getReposForTeam(2)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("B_r", "some description", 2, "url_B", false, now, now))))
      
      dataSource.getTeamRepoMapping.futureValue shouldBe List(
        TeamRepositories("B", List(GitRepository("B_r", "some description", "url_B", now, now))))
    }

    "Set repoType Service if the repository contains an app/application.conf folder" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", false, now, now))))

      when(githubClient.repoContainsContent("conf/application.conf","A_r","HMRC")(ec)).thenReturn(Future.successful(true))

      dataSource.getTeamRepoMapping.futureValue shouldBe List(
        TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A",now, now, repoType = RepoType.Service))))
    }

    "Set repoType Service if the repository contains a Procfile" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", false, now, now))))

      when(githubClient.repoContainsContent("Procfile","A_r","HMRC")(ec)).thenReturn(Future.successful(true))

      dataSource.getTeamRepoMapping.futureValue shouldBe List(
        TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Service))))
    }

    "Set type Service if the repository contains a deploy.properties" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", false, now, now))))

      when(githubClient.repoContainsContent(same("deploy.properties"),same("A_r"),same("HMRC"))(same(ec))).thenReturn(Future.successful(true))

      dataSource.getTeamRepoMapping.futureValue should contain(TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Service))))
    }

    "Set type as Deployable according if the repository.yaml contains a type of 'service'" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", fork = false, now, now))))

      when(githubClient.getFileContent(same("repository.yaml"),same("A_r"),same("HMRC"))(same(ec))).thenReturn(Future.successful(Some("type: service")))

      dataSource.getTeamRepoMapping.futureValue should contain(TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Service))))
    }

    "Set type as Library according if the repository.yaml contains a type of 'library'" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", fork = false, now, now))))

      when(githubClient.getFileContent(same("repository.yaml"),same("A_r"),same("HMRC"))(same(ec))).thenReturn(Future.successful(Some("type: library")))

      dataSource.getTeamRepoMapping.futureValue should contain(TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Library))))
    }

    "Set type as Other if the repository.yaml contains any other value for type" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", fork = false, now, now))))

      when(githubClient.getFileContent(same("repository.yaml"),same("A_r"),same("HMRC"))(same(ec))).thenReturn(Future.successful(Some("type: somethingelse")))

      dataSource.getTeamRepoMapping.futureValue should contain(TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Other))))
    }

    "Set type as Other if the repository.yaml does not contain a type" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", fork = false, now, now))))

      when(githubClient.getFileContent(same("repository.yaml"),same("A_r"),same("HMRC"))(same(ec))).thenReturn(Future.successful(Some("description: not a type")))

      dataSource.getTeamRepoMapping.futureValue should contain(TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Other))))
    }

    "Set type Library if not Service and has src/main/scala and has tags" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", false, now, now))))

      when(githubClient.getTags("HMRC", "A_r")(ec)).thenReturn(Future.successful(List("A_r_tag")))
      when(githubClient.repoContainsContent(same("src/main/scala"),same("A_r"),same("HMRC"))(same(ec))).thenReturn(Future.successful(true))

      val repositories: Seq[TeamRepositories] = dataSource.getTeamRepoMapping.futureValue
      repositories should contain(TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Library))))
    }

    "Set type Library if not Service and has src/main/java and has tags" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", false, now, now))))

      when(githubClient.getTags("HMRC", "A_r")(ec)).thenReturn(Future.successful(List("A_r_tag")))
      when(githubClient.repoContainsContent(same("src/main/java"),same("A_r"),same("HMRC"))(same(ec))).thenReturn(Future.successful(true))

      val repositories: Seq[TeamRepositories] = dataSource.getTeamRepoMapping.futureValue
      repositories should contain(TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Library))))
    }

    "Set type Prototype if the repository name ends in '-prototype'" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("CATO-prototype", "some description", 1, "url_A", false, now, now))))

      val repositories: Seq[TeamRepositories] = dataSource.getTeamRepoMapping.futureValue
      repositories should contain(TeamRepositories("A", List(GitRepository("CATO-prototype", "some description", "url_A", now, now, repoType = RepoType.Prototype))))
    }

    "Set type Other if not Service, Library nor Prototype and no repository.yaml file" in new Setup {

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC",1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r",   "some description", 1, "url_A", false, now, now))))

      val repositories: Seq[TeamRepositories] = dataSource.getTeamRepoMapping.futureValue
      repositories should contain(TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Other))))
    }

    "Retry up to 5 times in the event of a failed api call" in new Setup {

      when(githubClient.getOrganisations(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1))))

      when(githubClient.getTeamsForOrganisation("HMRC")(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))

      when(githubClient.getReposForTeam(1)(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))

      when(githubClient.repoContainsContent("app", "A_r", "HMRC")(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent("Procfile", "A_r", "HMRC")(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(false))

      dataSource.getTeamRepoMapping.futureValue(Timeout(1 minute)) shouldBe List(
        TeamRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now))))
    }
  }
}
