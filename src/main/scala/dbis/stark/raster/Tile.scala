package dbis.stark.raster

import scala.reflect.ClassTag
import scala.reflect._

/**
 * Tile represents a data type for 2D raster data.
 *
 */
case class Tile[U : ClassTag](ulx: Double, uly: Double, width: Int, height: Int, data: Array[U], pixelWidth: Short = 1) extends Serializable {

  /**
   * Contructor for tile with given data.
   */
  def this(width: Int, height: Int, data: Array[U]) = this(0, height, width, height, data)

  def this(ulx: Double, uly: Double, width: Int, height: Int) =
    this(ulx, uly, width, height, Array.fill[U](width * height)(null.asInstanceOf[U]))

  def this(ulx: Double, uly: Double, width: Int, height: Int, pixelWidth: Short, default: U) =
    this(ulx, uly, width, height, Array.fill[U](width * height)(default), pixelWidth)

  /**
    * Constructor for an empty tile of given size.
    */
  def this(width: Int, height: Int) = this(0, height, width, height)


  /**
   * Set a raster point at a given position to a value.
   */
  def set(x: Double, y: Double, v: U): Unit =
    data(idxFromPos(x,y)) = v

  def set(i: Int, v: U) = data(i) = v

  def setArray(i: Int, j: Int, v: U) = data(j * width + i) = v

  def setRow(x: Double, y: Double, array: Array[U]) = {
    val r = row(y)
    val c = column(x)
    setRowIdx(c,r, array)
  }

  def setRowIdx(i: Int, j: Int, array: Array[U]) = {
//    require(array.length == width, s"array length (${array.length} must match tile width ${width}")
    val start = j * width + i
    Array.copy(array, 0, data, start, array.length)
  }

  /**
   * Return the value at the given position of the raster.
   */
  def value(x: Double, y: Double): U = data(idxFromPos(x,y))

  @inline
  private[raster] def column(x: Double): Int = math.abs(x - ulx).toInt
  @inline
  private[raster] def row(y: Double): Int = (uly - y).toInt

  @inline
  private[raster] def idxFromPos(x: Double, y: Double): Int =
    row(y) * width + column(x)


  @inline
  private[raster] def posFromColRow(i: Int, j: Int): (Double, Double) = {
    val col = ulx + ((i % width) * pixelWidth)
    val row = uly - ((j / width) * pixelWidth)

    (col, row)
  }


  @inline
  def colRow(idx: Int): (Int, Int) = {
    (idx % width, idx / width)
  }


  def value(i: Int): U = data(i)

  def valueArray(i: Int, j: Int): U = data(j * width + i)

  /**
   * Apply a function to each raster point and return the new resulting tile.
   */
  def map[T : ClassTag](f: U => T): Tile[T] = Tile(ulx, uly, width, height, data.map(f))

  /**
   * Count the number of points with the given value.
   */
  def count(v: U): Int = data.count(_ == v)

  /**
   * Return a string representation of the tile.
   */
  override def toString: String = s"tile(ulx = $ulx, uly = $uly, w = $width, h = $height, pixelWidth = $pixelWidth, data = array of ${classTag[U].runtimeClass} with length ${data.length})"

  def matrix = {

    val b = new StringBuilder

    for(j <- 0 until height) {
      for(i <- 0 until width) {
        b.append(valueArray(i,j))

        if(i == width - 1) {
          if(j < height - 1)
            b.append("\n")
        } else
          b.append(", ")
      }
    }

    b.toString()
  }

  def intersects(t: Tile[_]): Boolean = RasterUtils.intersects(this, t)

  def contains(t: Tile[_]): Boolean = RasterUtils.contains(this, t)
}

//object Tile {
//  def apply(w: Int, h: Int, data: Array[Byte]) : Tile = new Tile(w, h, data)
//  def apply(x: Double, y: Double, w: Int, h: Int, data: Array[Byte]) : Tile = new Tile(x, y, w, h, data)
//}