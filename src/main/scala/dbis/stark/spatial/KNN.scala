package dbis.stark.spatial

import dbis.stark.Distance


case class KNN[PayloadT](k: Int) extends Serializable with Cloneable with Iterable[(Distance, PayloadT)] {

  var nn = new Array[(Distance, PayloadT)](k)
  protected[stark] var posMax = -1
  private[stark] var posMin = -1
  private[stark] var m = -1


  def min = nn(posMin)
  def max = nn(posMax)
  def full = m >= k

  protected[spatial] def set(nns: IndexedSeq[(Distance, PayloadT)]) = {
    require(nns.length == k, "provided list must have exactly length k")

    var i = 0
    var maxPos = -1
    var minPos = -1
    while(i < k) {
      nn(i) = nns(i)
      if(i == 0) {
        minPos = 0
        maxPos = 0
      } else {
        if(nns(i)._1 > nn(maxPos)._1)
          maxPos = i

        if(nns(i)._1 < nn(minPos)._1)
          minPos = i
      }

    }

    m = k
  }

  def insert(tuple: (Distance,PayloadT)) = {
    val pos = m+1
    if(pos < k) {
      nn(pos) = tuple
      if(posMax < 0 || nn(posMax)._1 < tuple._1) {
        posMax = pos
      }

      if(posMin < 0 || nn(posMin)._1 > tuple._1)
        posMin = pos

      m += 1
    } else if(nn(posMax)._1 > tuple._1) {
      nn(posMax) = tuple

      resetMinMax()
    }
  }

  override def iterator: Iterator[(Distance, PayloadT)] = new Iterator[(Distance,PayloadT)] {
    var j = 0
    override def hasNext: Boolean = j <= m && j < k

    override def next(): (Distance, PayloadT) = {
      val elem = nn(j)
      j += 1
      elem
    }
  }

//  def clone(): KNN[PayloadT] = {
//    val arr = Array.tabulate(k){ i =>
//      nn(i)
//    }
//    new
//  }

  def merge(other: KNN[PayloadT]): KNN[PayloadT] = {
    if(full && other.min._1 > max._1)
      this
    else {
      val knn = this //.clone()

      other.iterator.foreach(knn.insert)

      knn
    }
  }

  private def resetMinMax() = {
    var i  = 0
    while(i < m+1) {
      if(nn(i)._1 > nn(posMax)._1)
        posMax = i
      else if(nn(i)._1 < nn(posMin)._1)
        posMin = i

      i += 1
    }
  }

}
