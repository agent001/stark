package dbis.spark.spatial

import org.apache.spark.Partition
import dbis.spatial.NRectRange

class SpatialPartition(
    private val partitionId: Int, 
    val bounds: NRectRange
  ) extends Partition with Serializable {
  
  override def index = partitionId
}