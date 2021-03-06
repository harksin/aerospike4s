package io.aerospike4s

import com.aerospike.client.query.IndexType
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FlatSpec, GivenWhenThen, Matchers}

import cats.effect.IO

class OperationSpec extends FlatSpec with Matchers with BeforeAndAfterAll with GivenWhenThen with ScalaFutures {

  import scala.concurrent.ExecutionContext.Implicits.global

  import org.scalatest.time._

  import connection._
  import syntax._

  implicit val patience = PatienceConfig.apply(timeout = Span(10, Seconds), interval = Span(300, Millis))

  val manager = AerospikeManager("localhost", 3000)


  override protected def afterAll(): Unit = manager.client.close()

  object TestSet extends Set("test", "set")

  case class TestValue(id: String)

  case class LongValue(value: Long)

  "Append / Prepend" should "work" in {
    val key = TestSet.key("AppendOps")
    val io = for {
      _ <- put(key, TestValue("value"))
      _ <- append(key, Map(
        "id" -> "_with_suffix"
      ))
      _ <- prepend(key, Map(
        "id" -> "with_prefix_"
      ))
      v <- get[TestValue](key)
    } yield v

    io.run[IO](manager).unsafeRunSync should equal(Some(TestValue("with_prefix_value_with_suffix")))
  }

  "Add operation" should "work" in {
    val key = TestSet.key("AddOps")
    val io = for {
      _ <- put(key, LongValue(1L))
      _ <- add(key, Seq(("value" -> 2L)))
      v <- get[LongValue](key)
    } yield v

    io.run[IO](manager).unsafeRunSync should equal(Some(LongValue(3L)))
  }

  "Delete operation" should "work" in {
    val key = TestSet.key("AddOps")
    val io = for {
      _ <- put(key, TestValue("value"))
      _ <- delete(key)
      v <- get[TestValue](key)
    } yield v

    io.run[IO](manager).unsafeRunSync should equal(None)
  }

  "Touch operation" should "work" in {
    val key = TestSet.key("TouchOps")
    val io = for {
      _ <- put(key, TestValue("value"))
      _ <- touch(key)
    } yield ()

    io.run[IO](manager).unsafeRunSync //throw exception on fail
  }

  "Header operation" should "work" in {
    val key = TestSet.key("HeaderOps")
    val io = for {
      _ <- put(key, TestValue("value"))
      _ <- header(key)
    } yield ()

    io.run[IO](manager).unsafeRunSync //throw exception on fail
  }

  "Exists operation" should "work" in {
    val key = TestSet.key("TouchOps")
    val io = for {
      _ <- put(key, TestValue("value"))
      existsKey <- exists(key)
      notExistsKey <- exists(TestSet.key("notexists"))
    } yield (existsKey, notExistsKey)

    io.run[IO](manager).unsafeRunSync should equal((true, false))
  }

  "Query statement operation" should "work" in {
    val set = Set("test", "setQueryOps")
    val io = for {
      _ <- createIndex("test", "setQueryOps", "id", IndexType.STRING)
      _ <- put(set.key("test_stmt_1"), TestValue("stmt1"))
      _ <- put(set.key("test_stmt_2"), TestValue("stmt2"))
      record <- query(statement[TestValue]("test", "setQueryOps").binEqualTo("id", "stmt1"))
    } yield record.map(_._2)


    io.run[IO](manager).unsafeRunSync should contain theSameElementsAs Seq(TestValue("stmt1"))
  }

  "scan all operation" should "work" in {
    val io = for {
      _ <- put(TestSet.key("test_scan_1"), TestValue("scan1"))
      _ <- put(TestSet.key("test_scan_2"), TestValue("scan2"))
      _ <- put(TestSet.key("test_scan_3"), TestValue("scan3"))
      record <- scanAll[TestValue]("test", "set")
    } yield record.map(_._2)


    io.run[IO](manager).unsafeRunSync should contain allElementsOf Seq(
      TestValue("scan1"),
      TestValue("scan2"),
      TestValue("scan3")
    )
  }

  "Get all operation" should "work" in {
    val key1 = TestSet.key("test_getall_1")
    val key2 = TestSet.key("test_getall_2")

    val io = for {
      _ <- put(key1, TestValue("getall1"))
      _ <- put(key2, TestValue("getall2"))
      record <- getAll[TestValue](Seq(key1, key2))
    } yield record.map(_._2)

    io.run[IO](manager).unsafeRunSync should contain theSameElementsAs Seq(
      TestValue("getall1"),
      TestValue("getall2")
    )
  }

  "Create / Drop index operation" should "work" in {
    val io = for {
      _ <- createIndex("test", "set", "id", IndexType.STRING)
      _ <- dropIndex("test", "set", "test_set_id")
    } yield ()

    io.run[IO](manager).unsafeRunSync
  }

  "Operate operation" should "work" in {
    val key = TestSet.key("Operate_Ops")
    val io = operate[TestValue](key)(
      ops.put("id", "value"),
      ops.append("id", "_with_suffix"),
      ops.prepend("id", "with_prefix_"),
      ops.getAll
    )

    io.run[IO](manager).unsafeRunSync should equal(Some(TestValue("with_prefix_value_with_suffix")))
  }

  case class Person(name: String, age: Int)

  "Aggregate operation" should "work" in {
    val key = Set("test", "setAggregateOps").key("Aggregate_Ops")

    object PersonLuaScript extends UDFScript("persons.lua", "persons") {
      val filterByAge = function[(Int, Double)]("filterByAge")
    }

    val io = for {
      _ <- registerUDF("persons.lua", "persons.lua")
      _ <- createIndex("test", "setAggregateOps", "age", IndexType.NUMERIC)
      _ <- put(key, Person("Romain", 28))
      _ <- put(key, Person("Bob", 33))
      r <- query(
        statement[Person]("test", "setAggregateOps")
          .onRange("age", 10, 40)
          .aggregate(PersonLuaScript.filterByAge)((30, 2d))
      )
      _ <- removeUdf("persons.lua")
    } yield r.map(_._2)

    io.run[IO](manager).unsafeRunSync should contain theSameElementsAs Seq(Person("Bob", 33))
  }
}
