package dbis.stark.spatial.partitioner

import dbis.stark.spatial.{Cell, NPoint, NRectRange}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer




/**
 * A data class to store information about the created partitioning
 */
case class PartitionStats(
    ll: NPoint,
    ur: NPoint,
    start: Cell,
    numPartitions: Int, 
    avgPoints: Double,    
    maxPoints: List[(Cell, Int)],
    minPoints: List[(Cell, Int)],
    numPointsVariance: Double,
    volume: Double,
    avgVolume: Double,
    maxVolume: List[(Cell, Double)],
    minVolume: List[(Cell, Double)],
    histoSize: Int
  ) {
  
  override def toString = s"""stats:
    start range: $start 
    ll: $ll
    ur: $ur
    numPartitions: $numPartitions
    avgPoints: $avgPoints
    maxPoints: $maxPoints
    minPoints: $minPoints
    numPointsVariance: $numPointsVariance
    volume: $volume
    avg vol: $avgVolume
    max vol: $maxVolume
    min vol: $minVolume
    histo size: $histoSize
    """
}
  
object BSP {
  
  val DEFAULT_PARTITION_BUFF_SIZE = 100

  /**
    * Compute the cost for a partition, i.e. sum the cost
    * for each cell in that partition.
    *
    * @param part The partition
    * @return Returns the cost, i.e. the number of points, of the given cell
    */
  def costEstimation(part: NRectRange, sideLength: Double, range: NRectRange, numXCells: Int,
                     cellHistogram: CellHistogram): Int = {
    val cellIds = GridPartitioner.getCellsIn(part,sideLength,range,numXCells)
    var i = 0

    var sum = 0
    while (i < cellIds.size) {
      val id = cellIds(i)
      if (id >= 0 && id < cellHistogram.length) {
        sum += cellHistogram(id)._2
      }
      i += 1
    }
    sum
  }
  /**
    * Determine the extent of the given range. The extent is computed by combining the extents
    * of all cotnained elements
    * @param range The range to determine the extent fr
    * @return Returns the extent
    */
  protected[spatial] def extentForRange(range: NRectRange, sideLength: Double, numXCells: Int, cellHistogram: CellHistogram, global: NRectRange): NRectRange = {
    //    getCellsIn(range)
    //      .filter { id => id >= 0 && id < _cellHistogram.length } // FIXME: we should actually make sure cellInRange produces always valid cells
    //      .map { id => _cellHistogram(id)._1.extent } // get the extent for the cells
    //      .foldLeft(range){ (e1,e2) => e1.extend(e2) } // combine all extents to the maximum extent

    val cellIds = GridPartitioner.getCellsIn(range, sideLength, global, numXCells)

    var i = 0
    var extent = range

    while(i < cellIds.length) {
      val id = cellIds(i)
      if(id >= 0 && id < cellHistogram.length) {
        extent = extent.extend(cellHistogram(id)._1.extent)
      }
      i += 1
    }

    extent

  }
  
}

/**
 * A binary space partitioning algorithm implementation based on 
 * 
 * MR-DBSCAN: A scalable MapReduce-based DBSCAN algorithm for heavily skewed data
 * by He, Tan, Luo, Feng, Fan 
 * 
 * @param _cellHistogram A list of all cells and the number of points in them. Empty cells can be left out
 * @param _sideLength The side length of the (quadratic) cell
 * @param _maxCostPerPartition The maximum cost that one partition should have to read (currently: number of points).
 * This cannot be guaranteed as there may be more points in a cell than <code>maxCostPerPartition</code>, but a cell
 * cannot be further split.
 */
@deprecated("this version is extremely slow and will be removed soon")
class BSP(private val _universe: NRectRange, protected[stark] val _cellHistogram: CellHistogram,
          private val _sideLength: Double, private val _pointsOnly: Boolean, private val _maxCostPerPartition: Double,
          private val _numXCells: Option[Int] = None, private val _numCellThreshold: Int = -1)
  extends CostBasedPartitioner(_universe, _cellHistogram, _sideLength,
    _numXCells.getOrElse(GridPartitioner.cellsPerDimension(_universe,_sideLength)(0)),
  _maxCostPerPartition,_pointsOnly,_numCellThreshold) with Serializable {

  def this(_universe: NRectRange,_cellHistogram: CellHistogram,_sideLength: Double,_numXCells: Int,
            _maxCostPerPartition: Double,_pointsOnly: Boolean,_numCellThreshold: Int) {
    this(_universe,_cellHistogram,_sideLength,_pointsOnly, _maxCostPerPartition,Some(_numXCells),_numCellThreshold)
  }


  val start = Cell(0, universe)

  /**
   * Split the given partition into two partitions so that
   * <br/>
   * part = part1 u part2
   * <br/><br/>
   * A split is done along the cell borders of each dimension. The generate candidate split lines,
   * which is each cell border in each dimension and compute the cost for this candidate split.
   * The first split that creates a minimal cost difference between the two created partitions
   * is chosen
   * <br><br>
   * Note that if a partition consists of two cells and one cell has more elements that max costs
   * allows and the other one is empty, then the partition will be split, resulting in two partitions
   * with one cell each. One partition contains the cell with the elements and the other partition contains
   * an empty cell. In this case we return <code>None</code> for the empty partition
   *
   * @param part The partition to split
   * @return Returns the two created partitions. If one of them is empty, it is <code>None</code>
   */
  protected[spatial] def costBasedSplit(part: Cell): (Option[Cell], Option[Cell]) = {
    var minCostDiff = Double.PositiveInfinity

    // result variable with default value - will be overridden in any case
    var parts: (Option[Cell], Option[Cell]) = ( None, None )

    /*
     * count how many cells we have in each dimension and
     * process only those dimensions, were there is more than on cell,
     * i.e. we could split, actually
     */

    GridPartitioner.cellsPerDimension(part.range,sideLength).iterator.zipWithIndex      // index is the dimension -- (numCells, dim)
                      .filter(_._1 > 0)             // filter for number of cells
                      .foreach { case (numCells, dim) =>


      var prevP1Range: Option[Cell] = None
//       calculate candidate partitions it we split at each possible cell
//      for(i <- (1 until numCells)) {
      var i = 1
      while(i < numCells) {

        // TODO: better documentation for this calculation formulas
        val p1 = {

          /* we need to copy the array, otherwise we have wrong values
           * in calculation for p2
           */
          val ur = part.range.ur.c.clone()
          ur(dim) = part.range.ll(dim) + i*sideLength

          val range = NRectRange(part.range.ll.clone(), NPoint(ur))

          val cell = if(pointsOnly) {

            Cell(range)
          } else {

            /* we create the extent of this new partition from the extent of
             * all contained cells
             * TODO: for each iteration, we could re-use the extent from the
             * previous iteration and extend it with the extent of the new cells
             */

            val diffRange = if(prevP1Range.isEmpty) range else range.diff(prevP1Range.get.range)
            val diffRangeExtent = BSP.extentForRange(diffRange,sideLength,numXCells,cellHistogram,start.range)
            val extent = prevP1Range.map{ p => p.extent.extend(diffRangeExtent)}.getOrElse(diffRangeExtent)
            Cell(range, extent)

          }

          cell
        }

        if(!pointsOnly) {
          prevP1Range = Some(p1)
        }


        val p2 = {
          val rll = part.range.ll.c.clone()
          rll(dim) += i*sideLength

          /*
           * Here, we cannot add the extent of new cells, since P2 shrinks with the increase of
           * P1. Thus we have fewer cells and our extent can only shrink as well (or stay unchanged).
           * However, I have no good idea how to compute the shrinking.
           */
          val range = NRectRange(NPoint(rll), part.range.ur.clone())

          val cell = if(pointsOnly) {
            Cell(range)
          } else {
            val extent = BSP.extentForRange(range,sideLength,numXCells,cellHistogram,start.range)
            Cell(range, extent)
          }

          cell
        }

        require(p1.range.extend(p2.range) == part.range, "created partitions must completely cover input partition")

        // calculate costs in each candidate partition
        val p1Cost = BSP.costEstimation(p1.range,sideLength,start.range,numXCells,cellHistogram)
        val p2Cost = BSP.costEstimation(p2.range,sideLength,start.range,numXCells,cellHistogram)

        // if cost difference is (currently) minimal, store this partitioning
        val diff = Math.abs( p1Cost - p2Cost )
        if(diff < minCostDiff) {
          minCostDiff = diff

          var s1 = if(p1Cost <= 0) None else Some(p1)
          var s2 = if(p2Cost <= 0) None else Some(p2)

          if(s1.nonEmpty && s2.isEmpty) {
            s1 = Some(Cell(s1.get.range.extend(p2.range)))
          } else if(s1.isEmpty && s2.nonEmpty) {
            s2 = Some(Cell(s2.get.range.extend(p1.range)))
          }

          parts = (s1,s2)
        }
        i += 1
      }
    }


    /* at this point we have checked all candidate partitionings in each dimension
     * and have stored the one that creates a minimal cost difference between both
     * partitions - return this
     */

    parts
  }



  /**
   * Compute the partitioning using the cost based BSP algorithm
   *
   * This is a lazy value
   */
  lazy val partitions = {
//    val startTime = System.currentTimeMillis()
    val resultPartitions = new ArrayBuffer[Cell](BSP.DEFAULT_PARTITION_BUFF_SIZE)

//    val nonempty = cellHistogram.withFilter{ case (_, cnt) => cnt > 0 }.map(_._1)


    val startTime = System.currentTimeMillis()
    if(cellHistogram.nonEmptyCells.nonEmpty && cellHistogram.nonEmptyCells.size <= numCellThreshold) {
      val nonempty = cellHistogram.nonEmptyCells.map(_._1) //.map{ cellId => cellHistogram(cellId)._1}
      resultPartitions ++= nonempty.map(_.clone())
    } else {
      // add it to processing queue
      val queue = mutable.Queue(start)

      while(queue.nonEmpty) {

        /* if the partition to process is more expensive (= has more elements) than max cost
         * AND it is still larger than one cell, split it
         * Otherwise we use it as a result partition
         *
         * It may happen that a cell (which is our finest granularity) contains more elements
         * than max cost allows, however, since we cannot split a cell, we have to live with this
         */


        val part = queue.dequeue()
        val theCost = BSP.costEstimation(part.range,sideLength,start.range,numXCells,cellHistogram)
        val hasMoreCells = part.range.lengths.exists(_ > sideLength)


        if((theCost > maxCostPerPartition) && hasMoreCells) {

          val (p1, p2) = costBasedSplit(part)

          /* Do not add partition for further processing if
           *  - the generated partition was empty
           *  - or it is the same as the input partition
           *
           * The second case may happen if one partition was empty
           *
           */
          if(p1.isDefined) {
            if(p1.get != part)
          	  queue.enqueue(p1.get.clone())
          	else {
          	  resultPartitions += p1.get.clone()
            }
          }

          if(p2.isDefined) {
            if(p2.get != part)
          	  queue.enqueue(p2.get.clone())
          	else {
          	  resultPartitions += p2.get.clone()
            }
          }


        } else {
          resultPartitions += part.clone()
        }
      }
    }

    // index is the ID of the partition
    resultPartitions.iterator.zipWithIndex.foreach { case (p, i) =>
      p.id = i
    }

    val endTime = System.currentTimeMillis()
    resultPartitions.toArray

  }

  /**
   * Collect statistics about the generated partitioning
   * <br><br>
   * This is a lazy value so it is not computed until it is needed.
   * However, if the partitioning was not created before this value
   * is accessed, it will trigger the partition computation
   */
  lazy val partitionStats: PartitionStats = ???
//  {
//
//    // this will trigger the computation, in case it was not done before
//    val numParts = partitions.length
//
//    val partCounts = cellHistogram.view
//      .flatMap { case (cell, count) =>
//        partitions.view
////          .map(_._1)
//          .filter { p => p.range.contains(cell.range) }
//          .map { p => (p, count) }
//      }
//      .groupBy(_._1)
//      .map { case (part, arr) =>
//        (part, arr.map(_._2).sum)
//      }.toList
//
//    // _2 is the count for each partition
//    val avgPoints = partCounts.view.map(_._2).sum.toDouble / partCounts.size
//    val maxPts = partCounts.view.map(_._2).max
//    val minPts = partCounts.view.map(_._2).min
//
//    val maxPoints = partCounts.filter(_._2 == maxPts)
//    val minPoints = partCounts.filter(_._2 == minPts)
//
//    val variance = partCounts.map { case (part, count) => Math.pow( count - avgPoints, 2) }.sum
//
//
//
//    val area = partitions.view.map(_.range.volume).sum
//    val avgArea = area / numParts
//    val partAreas = partCounts.map { case (part,_) => (part, part.range.volume) }
//    // _2 is the area of a partition
//    val maxA = partAreas.view.map(_._2).max
//    val minA = partAreas.view.map(_._2).min
//
//    val maxArea = partAreas.filter(_._2 == maxA)
//    val minArea = partAreas.filter(_._2 == minA)
//
////    val areaVariance = partAreas.map{ case (part, area) => Math.pow( area - avgArea, 2) }.sum
//
//    PartitionStats(start.range.ll, start.range.ur, start,numParts, avgPoints, maxPoints, minPoints, variance, area, avgArea, maxArea, minArea, cellHistogram.length)
//  }
    
}
