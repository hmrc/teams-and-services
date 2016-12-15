package uk.gov.hmrc.teamsandrepositories

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterEach, FunSpec, LoneElement, OptionValues}
import org.scalatestplus.play.OneAppPerTest
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global



class MongoTeamsAndRepositoriesPersisterSpec extends UnitSpec with LoneElement with MongoSpecSupport with ScalaFutures with OptionValues with BeforeAndAfterEach with OneAppPerTest {


  val mongoTeamsAndReposPersister = new MongoTeamsAndReposPersister(mongo)

  override def beforeEach() {
    await(mongoTeamsAndReposPersister.drop)
  }


//  "getAll" should {
//    "return all the deployments in descending order of productionDate" in {
//
//      val now: LocalDateTime = LocalDateTime.now()
//
//      await(mongoTeamsAndReposPersister.add(Deployment("test2", "v2", None, productionDate = now.minusDays(6))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test3", "v3", None, productionDate = now.minusDays(5))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test1", "v1", None, productionDate = now.minusDays(10))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test4", "v4", None, productionDate = now.minusDays(2))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test5", "vSomeOther1", None, now.minusDays(2), Some(1))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test5", "vSomeOther2", None, now, Some(1))))
//
//      val result: Seq[Deployment] = await(mongoTeamsAndReposPersister.getAllDeployments)
//
//      result.map(x => (x.name, x.version)) shouldBe Seq(
//        ("test5", "vSomeOther2"),
//        ("test4", "v4"),
//        ("test5", "vSomeOther1"),
//        ("test3", "v3"),
//        ("test2", "v2"),
//        ("test1", "v1")
//      )
//
//    }
//  }

//  "getForService" should {
//    "return deployments for a service sorted in descending order of productionDate" in {
//      val now: LocalDateTime = LocalDateTime.now()
//
//      await(mongoTeamsAndReposPersister.add(Deployment("randomService", "vSomeOther1", None, now, Some(1))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test", "v1", None, productionDate = now.minusDays(10), interval = Some(1))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test", "v2", None, productionDate = now.minusDays(6), interval = Some(1))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test", "v3", None, productionDate = now.minusDays(5), Some(1))))
//      await(mongoTeamsAndReposPersister.add(Deployment("test", "v4", None, productionDate = now.minusDays(2), Some(1))))
//
//      val deployments: Option[Seq[Deployment]] = await(mongoTeamsAndReposPersister.getForService("test"))
//
//      deployments.get.size shouldBe 4
//
//      deployments.get.map(_.version) shouldBe List("v4", "v3", "v2", "v1")
//
//    }
//  }


  "add" should {
    "be able to insert a new record" in {
      val now: LocalDateTime = LocalDateTime.now()
      val gitRepository1 = GitRepository("repo-name1", "Desc1", "url1", 1, 2, false, RepoType.Deployable)
      val gitRepository2 = GitRepository("repo-name2", "Desc2", "url2", 3, 4, true, RepoType.Library)

      val teamAndRepositories1 = PersistedTeamAndRepositories("test-team", now, List(gitRepository1, gitRepository2))
      await(mongoTeamsAndReposPersister.add(teamAndRepositories1))
      val all = await(mongoTeamsAndReposPersister.allTeamsAndRepositories)

      all.values.flatten.size shouldBe 1
      val teamAndRepositories: PersistedTeamAndRepositories = all.values.flatten.loneElement

      teamAndRepositories.teamName shouldBe "test-team"
      teamAndRepositories.time.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")) shouldBe now.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"))
      teamAndRepositories.repositories shouldBe List(gitRepository1, gitRepository2)

    }
  }

  "upsert" should {
    "update already existing team" in {

      val now: LocalDateTime = LocalDateTime.now()
      val oneHourLater = now.plusHours(1)

      val gitRepository1 = GitRepository("repo-name1", "Desc1", "url1", 1, 2, false, RepoType.Deployable)
      val gitRepository2 = GitRepository("repo-name2", "Desc2", "url2", 3, 4, true, RepoType.Library)

      val teamAndRepositories1 = PersistedTeamAndRepositories("test-team", now, List(gitRepository1))
      await(mongoTeamsAndReposPersister.add(teamAndRepositories1))

      val teamAndRepositories2 = PersistedTeamAndRepositories("test-team", oneHourLater, List(gitRepository2))
      await(mongoTeamsAndReposPersister.upsert(teamAndRepositories2))

      val allUpdated = await(mongoTeamsAndReposPersister.allTeamsAndRepositories)
      allUpdated.size shouldBe 1
      val updatedDeployment: PersistedTeamAndRepositories = allUpdated.values.flatten.loneElement

      updatedDeployment.teamName shouldBe "test-team"
      updatedDeployment.time.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")) shouldBe oneHourLater.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"))
      updatedDeployment.repositories shouldBe List(gitRepository2)

    }

  }

}