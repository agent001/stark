package dbis.stark

//import com.vividsolutions.jts.geom.{Envelope, Geometry, Point}
//import com.vividsolutions.jts.io.WKTReader
import STObject._
import dbis.stark.spatial.Utils
import org.locationtech.jts.geom._
import org.locationtech.jts.io.WKTReader

/**
 * A STObject represents some spatial geometry. It can also have
 * a time component, thus it represents an object in space and time.
 * 
 * The idea for the following methods is to check whether their spatial component fulfills the requirement (validity, intersection, etc)
 * and also to check their temporal components.
 * 
 * The respective check is only true iff:
 *  - the spatial check yields true AND
 *  - both temporal components are NOT defined OR
 *  - both temporal components are defined AND they also return true for the respective check 
 * 
 * @param g The geometry 
 * @param time The optional time component 
 */
case class STObject(
   private val g: GeoType,
   time: Option[TemporalExpression]) extends BaseExpression[STObject] {

  def area = g.getArea
  def length = time.map(_.length)

  def intersectsSpatial(t: STObject) = g.intersects(t.g)
  def intersectsTemporal(t: STObject) = time.isEmpty && t.time.isEmpty || (time.isDefined && t.time.isDefined && time.get.intersects(t.time.get))
  
  /**
   * Check if this spatial object intersects with the other given object.
   * <br><br> 
   * This return <code>true</code> only iff the spatial component intersects with the given object and the 
   * temporal components are either both <b>not</b> defined, or are both defined and the this temporal
   * component intersects with the temporal expression of the given object.
   * 
   * @param t The other spatial object to check
   * @return Returns <code>true</code> iff this object intersects with the other given object in both space and time.
   */
  def intersects(t: STObject) = intersectsSpatial(t) && intersectsTemporal(t)
  
  
  def containsSpatial(t: STObject) = g.contains(t.g)
  def containsTemporal(t: STObject) = time.isEmpty && t.time.isEmpty || (time.isDefined && t.time.isDefined && time.get.contains(t.time.get))


  @inline
  def covers(o: STObject) = coversSpatial(o) && coversTemporal(o)

  @inline
  def coversSpatial(o: STObject) = g.covers(o.getGeo)
  def coversTemporal(o: STObject) = time.isEmpty && o.time.isEmpty || (time.isDefined && o.time.isDefined && time.get.contains(o.time.get))


  def coveredByTemporal(o: STObject) = g.coveredBy(o.getGeo)

  def coveredBySpatial(o: STObject) = time.isEmpty && o.time.isEmpty || (time.isDefined && o.time.isDefined && time.get.containedBy(o.time.get))

  def coveredBy(o: STObject) = coveredBySpatial(o) && coveredByTemporal(o)
  /**
   * Check if this spatial object completely contains the other given object.
   * <br><br>
   * This return <code>true</code> only iff the spatial component contains the given object and the 
   * temporal components are either both <b>not</b> defined, or are both defined and the this temporal
   * component completely contains the temporal expression of the given object.      
   * 
   * @param t The other spatial object to check
   * @return Returns <code>true</code> iff this object completely contains the other given object in both space and time
   */
  def contains(t: STObject) = containsSpatial(t) && containsTemporal(t)
  
  // just the reverse operation of contains
  def containedBySpatial(t: STObject) = t.containsSpatial(this)
  def containedByTemporal(t: STObject) = t.containsTemporal(this)
  
  /**
   * Check if this spatial object is completely contained by the other given object.
   * <br><br>
   * This is the reverse operation of [[dbis.stark.STObject#contains]] 
   */
  def containedBy(t: STObject) = containedBySpatial(t) && containedByTemporal(t) 
  
  def before(t: TemporalExpression) = time.map(_ < t)
  
  def after(t: TemporalExpression) = time.map(_ > t)

  lazy val getMaxX = this.g.getCoordinates.iterator.map(c => c.x).max
  lazy val getMinX = this.g.getCoordinates.iterator.map(c => c.x).min
  lazy val getMaxY = this.g.getCoordinates.iterator.map(c => c.y).max
  lazy val getMinY = this.g.getCoordinates.iterator.map(c => c.y).min
  lazy val getMinZ = this.g.getCoordinates.iterator.map(c => c.z).min
  lazy val getMaxZ = this.g.getCoordinates.iterator.map(c => c.z).max

  def getMinMaxXY = this.g.getCoordinates.iterator.map(c => (c.x, c.y, c.x,c.y)).reduce( (l,r) => {
    val minX = math.min(l._1, r._1)
    val minY = math.min(l._2, r._2)

    val maxX = math.max(l._3, r._3)
    val maxY = math.max(l._4, r._4)

    (minX, minY, maxX, maxY)
  })

  def getStart = time.map(_.start)
  def getEnd = time.flatMap(_.end)
  
  /**
   * Check if this NRechtRange is equal to some other object.
   * <br><br>
   * They can only be equal if the other object is also a NRectRange 
   * which has the same <code>ll</code> and <code>ur</code> values.
   * The ID is <emph>NOT</emph> considered for equality check!  
   */
  override def equals(that: Any): Boolean = that match {
    case STObject(geo, t) => (this.g equals geo) && (this.time equals t)
    case _ => false
  }
  
  override def hashCode() = (g,time).hashCode()


  def getGeo = g
  def getTemp = time
  
}

object STObject {

  type GeoType = Geometry
  type MBR = Envelope

  def apply(wkt: String): STObject = this(new WKTReader().read(wkt))
  def apply(wkt: String, ts: Long): STObject = STObject(new WKTReader().read(wkt), ts)
  def apply(wkt: String, start: Long, end: Long): STObject = STObject(wkt, Interval(start, end))
  def apply(wkt: String, ts: Instant): STObject = this(new WKTReader().read(wkt), ts)
  def apply(wkt: String, temp: Interval): STObject = this(new WKTReader().read(wkt), temp)

  def apply(g: GeoType): STObject = this(g, None)
  def apply(g: GeoType, t: TemporalExpression): STObject = this(g, Some(t))
  def apply(g: GeoType, time: Long): STObject = this(g, Some(Instant(time)))
  def apply(g: GeoType, start: Long, stop: Long): STObject = this(g, Some(Interval(start, stop)))
//  def apply(g: GeoType, start: Long, stop: Option[Long]): STObject = this(g, Some(Interval(start, Instant(stop))))
  def apply(e: MBR): STObject = this(Utils.makeGeo(e), None)

  def apply(x: Double, y: Double): STObject = this(new GeometryFactory().createPoint(new Coordinate(x,y)))
  def apply(x: Double, y: Double, ts: Long): STObject = this(new GeometryFactory().createPoint(new Coordinate(x,y)), ts)
  def apply(x: Double, y: Double, z: Double): STObject = this(new GeometryFactory().createPoint(new Coordinate(x,y,z)))
  def apply(x: Double, y: Double, z: Double, ts: Long): STObject = this(new GeometryFactory().createPoint(new Coordinate(x,y,z)), ts)

  implicit def getInternal(s: STObject): GeoType = s.g
  
  implicit def makeSTObject(g: GeoType): STObject = STObject(g)
  
  /**
	 * Convert a string into a geometry object. The String must be a valid WKT representation
	 * 
	 * @param s The WKT string
	 * @return The geometry parsed from the given textual representation
	 */
	implicit def fromWKT(s: String): STObject = STObject(s)

  implicit def asString(s: STObject): String = s.toString
  
}
