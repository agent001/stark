package dbis.stark.raster

import dbis.stark.STObject
import dbis.stark.STObject.MBR
import dbis.stark.spatial.indexed.{IndexConfig, IndexFactory, RTreeConfig}
import dbis.stark.spatial.partitioner.{JoinPartition, SpatialPartitioner}
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.locationtech.jts.geom.GeometryFactory

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

class RasterJoinVectorRDD[U: ClassTag, P: ClassTag](var left: RasterRDD[U],
                                                    var right: RDD[(STObject, P)],
                                                    indexConfig: Option[IndexConfig] = None) extends RDD[(Tile[U],P)](left.context, Nil) {

  private val numPartitionsInRight = right.getNumPartitions

  def leftPartitioner = left.partitioner.map {
    case r: RasterPartitioner => r
  }

  def rightPartitioner = right.partitioner.map {
    case sp: SpatialPartitioner => sp
  }

  override protected def getPartitions = {
    val parts = ArrayBuffer.empty[JoinPartition]

    val checkPartitions = leftPartitioner.isDefined && rightPartitioner.isDefined
    var idx = 0

    for (
      s1 <- left.partitions;
      s2 <- right.partitions
      if !checkPartitions || leftPartitioner.get.idToNRectRange(s1.index).intersects(rightPartitioner.get.partitionExtent(s2.index))) {

      //        val leftContainsRight = leftParti.get.partitionExtent(s1.index).contains(rightParti.get.partitionExtent(s2.index))
      //        val rightContainsLeft = if(!leftContainsRight) rightParti.get.partitionExtent(s1.index).contains(leftParti.get.partitionExtent(s2.index)) else false

      val p = new JoinPartition(idx, left, right, s1.index, s2.index)//, leftContainsRight, rightContainsLeft)
      parts += p
      idx += 1
      //        }

    }
    parts.toArray
  }

  override def compute(s: Partition, context: TaskContext): Iterator[(Tile[U], P)] = {
    val split = s.asInstanceOf[JoinPartition]



    if(indexConfig.isDefined) {
      val index = IndexFactory.get[MBR, Tile[U]](indexConfig.get)

      val geoFactory = new GeometryFactory()

      left.iterator(split.leftPartition, context).foreach{ t =>
        val tileMBR = new MBR(t.ulx, t.ulx + t.width, t.uly - t.height, t.uly)
        val tileMBRGeo = geoFactory.toGeometry(tileMBR)
        index.insert(tileMBRGeo, t)
      }

      index.build()

      // geometry factory used to create a Geometry from a MBR
      // will be instantiated below while building the index
      var factory: GeometryFactory = null

      val resultIter = right.iterator(split.rightPartition,context).flatMap{ case (rg, rv) =>

        // create factory object only once
        if(factory == null) {
          factory = new GeometryFactory(rg.getGeo.getPrecisionModel, rg.getGeo.getSRID)
        }

        // put all tiles into the tree
        index.query(rg).map(t => (t, rv))
      }

      new InterruptibleIterator[(Tile[U], P)](context, resultIter)

    } else {

      val rightList = right.iterator(split.rightPartition, context).toList
      val head = rightList.head._1
      val factory = new GeometryFactory(head.getGeo.getPrecisionModel, head.getGeo.getSRID)

      // loop over the left partition and check join condition on every element in the right partition's array
      val resultIter = left.iterator(split.leftPartition, context).flatMap { t =>

        val tileMBR = new MBR(t.ulx, t.ulx + t.width, t.uly - t.height, t.uly)
        val tileGeom = factory.toGeometry(tileMBR)

        // TODO: get the pixels of the tile that actually intersect with the geometry
        // this would create a new Tile
        rightList.iterator.filter { case (rg, _) =>
          tileGeom.intersects(rg) // TODO: predicateFunction as a parameter
        }.map { case (_, rv) => (t, rv) }
      }

      new InterruptibleIterator(context, resultIter)
    }
  }


  override def getPreferredLocations(split: Partition): Seq[String] = {
    val currSplit = split.asInstanceOf[JoinPartition]
    (left.preferredLocations(currSplit.leftPartition) ++ right.preferredLocations(currSplit.rightPartition)).distinct
  }

  override def getDependencies: Seq[Dependency[_]] = List(
    new NarrowDependency(left) {
      def getParents(id: Int): Seq[Int] = List(id / numPartitionsInRight)
    },
    new NarrowDependency(right) {
      def getParents(id: Int): Seq[Int] = List(id % numPartitionsInRight)
    }
  )

  override def clearDependencies() {
    super.clearDependencies()
    left = null
    right = null
  }
}
