package dbis.stark.spatial.partitioner

import java.nio.file.{Path, Paths}

import dbis.stark.STObject
import dbis.stark.spatial.{Cell, NPoint, NRectRange, Utils}
import org.apache.spark.Partitioner
import org.apache.spark.rdd.RDD

import scala.collection.JavaConverters._

case class CellHistogram(buckets: Array[(Cell, Int)])

trait SpatialPartitioner extends Partitioner {

  private final lazy val empties = Array.fill(numPartitions)(true)

  @inline
  final def isEmpty(id: Int): Boolean = empties(id)

  @inline
  def getPartitionId(key: Any): Int

  override final def getPartition(key: Any) = {
    val id = getPartitionId(key)
    empties(id) = false
    id
  }

  def printPartitions(fName: java.nio.file.Path): Unit

  def printPartitions(fName: String): Unit =
    printPartitions(Paths.get(fName))


  protected[stark] def writeToFile(strings: Iterable[String], fName: Path) =
    java.nio.file.Files.write(fName, strings.asJava, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
}


/**
  * Contains convenience functions used in spatial partitioners
  */
object GridPartitioner {

  var EPS: Double = 1 / 1000000.0


  /**
    * Determine the min/max extents of a given RDD
    *
    * Since we use right-open intervals in [[NRectRange]] we add [[EPS]] to the both max values
    *
    * @param rdd The RDD
    * @tparam G The data type representing spatio-temporal data
    * @tparam V The type for payload data
    * @return Returns a 4-tuple for min/max values in the two dimensions in the form <code>(min-x, max-x, min-y, max-y)</code>
    */
  def getMinMax[G <: STObject, V](rdd: RDD[(G,V)]): (Double, Double, Double, Double) = {

//    val theRDD = if(sampleFraction > 0) rdd.sample(withReplacement = false, fraction = sampleFraction) else rdd

    val (minX, maxX, minY, maxY) = rdd.map{ case (g,_) =>
      val env = g.getEnvelopeInternal
      (env.getMinX, env.getMaxX, env.getMinY, env.getMaxY)

    }.reduce { (oldMM, newMM) =>
      val newMinX = oldMM._1 min newMM._1
      val newMaxX = oldMM._2 max newMM._2
      val newMinY = oldMM._3 min newMM._3
      val newMaxY = oldMM._4 max newMM._4

      (newMinX, newMaxX, newMinY, newMaxY)
    }

    // do +1 for the max values to achieve right open intervals
    (minX, maxX + EPS, minY, maxY + EPS)
  }

  def getMinMax[G <: STObject, V](samples: Iterator[(G,V)]): (Double, Double, Double, Double) = {

    val (minX, maxX, minY, maxY) = samples.map{ case (g,_) =>
      val env = g.getEnvelopeInternal
      (env.getMinX, env.getMaxX, env.getMinY, env.getMaxY)

    }.reduce { (oldMM, newMM) =>
      val newMinX = oldMM._1 min newMM._1
      val newMaxX = oldMM._2 max newMM._2
      val newMinY = oldMM._3 min newMM._3
      val newMaxY = oldMM._4 max newMM._4

      (newMinX, newMaxX, newMinY, newMaxY)
    }

    // do +1 for the max values to achieve right open intervals
    (minX, maxX + EPS, minY, maxY + EPS)
  }


  protected[stark] def getCellId(_x: Double, _y: Double, minX: Double, minY: Double, maxX: Double, maxY: Double, xLength: Double, yLength:Double, numXCells: Int): Int = {
    require(_x >= minX && _x <= maxX && _y >= minY && _y <= maxY, s"(${_x},${_y}) out of range!")

    val x = math.floor(math.abs(_x - minX) / xLength).toInt
    val y = math.floor(math.abs(_y - minY) / yLength).toInt

    val cellId = y * numXCells + x

    cellId
  }

  /**
    * Compute the bounds of a cell with the given ID
    * @param id The ID of the cell to compute the bounds for
    */
  @inline
  protected[stark] def getCellBounds(id: Int, xCells: Int, xLength: Double, yLength: Double, minX: Double, minY: Double): NRectRange = {

    val dy = id / xCells
    val dx = id % xCells

    val llx = dx * xLength + minX
    val lly = dy * yLength + minY

    val urx = llx + xLength
    val ury = lly + yLength

    NRectRange(NPoint(llx, lly), NPoint(urx, ury))
  }


  def buildHistogram[G <: STObject, V](rdd: RDD[(G,V)], pointsOnly: Boolean, numXCells: Int, numYCells: Int,
                                       minX: Double, minY: Double, maxX: Double, maxY: Double,
                                       xLength: Double, yLength:Double): Array[(Cell,Int)] = {

    def seq(histo1: CellHistogram, pt: (G,V)): CellHistogram = {

      val p = Utils.getCenter(pt._1.getGeo)
      val cellId = getCellId(p.getX, p.getY,minX, minY, maxX, maxY, xLength, yLength, numXCells)

      histo1.buckets(cellId) = (histo1.buckets(cellId)._1, histo1.buckets(cellId)._2 + 1)
      if(!pointsOnly) {
        histo1.buckets(cellId)._1.extendBy(Utils.fromGeo(pt._1.getGeo))
//        histo1.buckets(cellId)._1.extent.extend(
      }
//        histo1.buckets(cellId) = (), histo1.buckets(cellId)._2)
      histo1
    }

    def combine(histo1: CellHistogram, histo2: CellHistogram): CellHistogram = {

      val newBuckets = histo1.buckets.iterator.zip(histo2.buckets.iterator).map{ case ((cell1, cnt1),(cell2,cnt2)) =>

        val newCell = if(pointsOnly) Cell(cell1.range) else Cell(cell1.range, cell1.extent.extend(cell2.extent))
        val newCnt = cnt1 + cnt2
        (newCell, newCnt)
      }.toArray


      CellHistogram(newBuckets)
    }

    val histo = buildGrid(numXCells,numYCells, xLength, yLength, minX,minY)

    rdd.aggregate(CellHistogram(histo))(seq, combine).buckets

    /* fill the array. If with extent, we need to keep the exent of each element and combine it later
     * to create the extent of a cell based on the extents of its contained objects
     */
//    if(pointsOnly) {
//      rdd.map{ case (g,_) =>
//        val p = Utils.getCenter(g.getGeo)
//
//        val cellId = getCellId(p.getX, p.getY,minX, minY, maxX, maxY, xLength, yLength, numXCells)
//
//        (cellId, 1)
//      }
//      .reduceByKey(_ + _)
////      .collect
//      .cache()
//      .toLocalIterator
//      .foreach{ case (cellId, cnt) =>
//        histo(cellId) = (histo(cellId)._1, cnt)
//      }
//
//
//    } else {
//      rdd.map { case (g, _) =>
//        val p = Utils.getCenter(g.getGeo)
////        val env = g.getEnvelopeInternal
////        val extent = NRectRange(NPoint(env.getMinX, env.getMinY), NPoint(env.getMaxX, env.getMaxY))
//        val extent = Utils.fromGeo(g.getGeo)
//        val cellId = getCellId(p.getX, p.getY,minX, minY, maxX, maxY, xLength, yLength, numXCells)
//
//        (cellId,(1, extent))
//      }
//      .reduceByKey{ case ((lCnt, lExtent), (rCnt, rExtent)) =>
//        val cnt = lCnt + rCnt
//
//        val extent = lExtent.extend(rExtent)
//
//        (cnt, extent)
//
//      }
////        .collect
//      .cache()
//      .toLocalIterator
//      .foreach{case (cellId, (cnt,ex)) =>
//        histo(cellId) = (Cell(cellId, histo(cellId)._1.range, ex) , cnt)
//      }
//    }
//    histo

  }

  def buildGrid(numXCells: Int, numYCells: Int, xLength: Double, yLength: Double, minX: Double, minY: Double): Array[(Cell, Int)] =
    Array.tabulate(numXCells * numYCells){ i =>
      val cellBounds = getCellBounds(i, numXCells, xLength, yLength, minX, minY)
      (Cell(i,cellBounds), 0)
    }

}



/**
  * Base class for spatial partitioners
  * @param minX The min value in x dimension
  * @param maxX The max value in x dimension
  * @param minY The min value in y dimension
  * @param maxY The max value in y dimension
  */
abstract class GridPartitioner(
    val minX: Double, var maxX: Double, val minY: Double, var maxY: Double
  ) extends SpatialPartitioner {


  def partitionBounds(idx: Int): Cell
  def partitionExtent(idx: Int): NRectRange


}
