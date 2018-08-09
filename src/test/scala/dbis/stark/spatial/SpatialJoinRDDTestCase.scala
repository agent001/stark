package dbis.stark.spatial

import dbis.stark.{STObject, TestUtils}
import org.apache.spark.SparkContext
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import dbis.stark.spatial.SpatialRDD._

class SpatialJoinRDDTestCase  extends FlatSpec with Matchers with BeforeAndAfterAll {

  private var sc: SparkContext = _

  override def beforeAll() {
    sc = TestUtils.createSparkContext("spatialjoinrddtestcase", 1)
  }

  override def afterAll() {
    if(sc != null)
      sc.stop()
  }

  "A SpatialJoinRDD" should "find the correct join result" in {

    val s = Seq("POLYGON ((-73.0 40.5, -70 40.5, -72 41, -73.0 40.5));42",
                "POINT (25 20);69")

    val rdd1 = sc.parallelize(s).map(_.split(';')).map(arr => (STObject(arr(0)), arr(1).trim.toInt))
    val rdd2 = sc.parallelize(s).map(_.split(';')).map(arr => (STObject(arr(0)), arr(1).trim.toInt))

    val joined = new SpatialJoinRDD(rdd1, rdd2, JoinPredicate.INTERSECTS).collect()

    val ref = List((42,42),(69,69))

    joined should contain theSameElementsAs ref

  }

}
