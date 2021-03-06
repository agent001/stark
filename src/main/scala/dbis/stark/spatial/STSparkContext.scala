package dbis.stark.spatial

import java.awt.image.BufferedImage
import java.nio.file.Paths

import com.esotericsoftware.kryo.io.Input
import dbis.stark.raster.{RasterRDD, RasterUtils, SMA, Tile}
import dbis.stark.spatial.indexed.IndexConfig
import dbis.stark.spatial.partitioner.{OneToManyPartition, PartitionerConfig, PartitionerFactory}
import dbis.stark.{Instant, Interval, STObject}
import javax.imageio.ImageIO
import org.apache.hadoop.fs.Path
import org.apache.spark.SpatialRDD._
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.{Partition, SparkConf, SparkContext}
import org.locationtech.jts.index.strtree.RTree

import scala.collection.mutable
import scala.reflect.ClassTag


object STSparkContext {
  final val PARTITIONINFO_FILE = "partition_info"
  final val PARTITIONINFO_DELIM = ";"
}

/**
  * A spatio-temporal aware context
  *
  */
class STSparkContext(conf: SparkConf) extends SparkContext(conf) {

  def this() = this(new SparkConf())

  // a representation of the filesystem
  private val dfs = org.apache.hadoop.fs.FileSystem.get(hadoopConfiguration)

  /**
    * Load a text file and decide which partitions to load based on the given query object
    *
    * If the given path is a directory which contains an info file, we will use this info file
    * to decide which partition we have to load. If this file is not present, all partitions are loaded
    * @param path The base path
    * @param qry The query object used to decide which partition to load
    * @return Returns the [[org.apache.spark.rdd.RDD]]
    */
  def textFile(path: String, qry: STObject, minPartitions: Int): RDD[String] = {
    val partitionsToLoad: String = getPartitionsToLoad(path, Some(qry))

    if(partitionsToLoad.isEmpty)
      this.emptyRDD[String]
    else
      super.textFile(partitionsToLoad, minPartitions)
  }

  def objectFile[T : ClassTag](path: String, qry: STObject, minPartitions: Int): RDD[T] =
    this.objectFile(path, Some(qry), minPartitions)

  def objectFile[T : ClassTag](path: String, qry: Option[STObject], minPartitions: Int): RDD[T] = {

    val partitionstoLoad = getPartitionsToLoad(path, qry)

    if(partitionstoLoad.isEmpty)
      emptyRDD[T]
    else
      super.objectFile[T](partitionstoLoad, minPartitions)
  }

  def jointextFiles[V1 : ClassTag, V2: ClassTag](leftPath: String, rightPath: String, qry:Option[STObject],
                                                 pred: JoinPredicate.JoinPredicate,
                                                 partitioner: Option[PartitionerConfig], indexer: IndexConfig,
                                                 lMapper: String => (STObject, V1),
                                                 rMapper: String => (STObject, V2)
                                                 ): RDD[(V1, V2)] = {

    val lp = Paths.get(leftPath)
    val lInfoFile = lp.resolve(STSparkContext.PARTITIONINFO_FILE).toString
    val lIsDir = dfs.isDirectory(new Path(leftPath))

    val rp = Paths.get(rightPath)
    val rInfoFile = rp.resolve(STSparkContext.PARTITIONINFO_FILE).toString
    val rIsDir = dfs.isDirectory(new Path(rightPath))

    if(!(lIsDir && dfs.exists(new Path(lInfoFile))) || !(rIsDir && dfs.exists(new Path(rInfoFile)))) {
      val l = super.textFile(leftPath).map(lMapper)
      val r = super.textFile(rightPath).map(rMapper)

      val parti = partitioner.flatMap(c => PartitionerFactory.get(c, l))

      l.liveIndex(parti, indexer).join(r,pred,parti, oneToMany = true)
    } else {

      val tree = new RTree[String](10)
      metaInfo(lInfoFile).collect().foreach{ case (so, file) =>
        tree.insert(so,file)
      }
      tree.build()

      val depMap = mutable.Map.empty[String, mutable.Set[String]]

      var i = 0
      val rightParts = metaInfo(rInfoFile).collect()
      while(i < rightParts.length) {

        val intersecting = tree.query(rightParts(i)._1)

        if(intersecting.nonEmpty) {

          intersecting.foreach{ lFile =>

            if(depMap.contains(lFile))
              depMap(lFile).add(rightParts(i)._2)
            else
              depMap += lFile -> mutable.Set(rightParts(i)._2)
          }
        }
        i += 1
      }

      val lSortedNames = depMap.keys.toList.sorted
      val rSortedNames = depMap.valuesIterator.reduce(_ union _).toList.sorted

      val lDict = lSortedNames.zipWithIndex.toMap
      val rDict = rSortedNames.zipWithIndex.toMap


      val lFiles = lSortedNames.map(f => Paths.get(leftPath, f))
      val rFiles = rSortedNames.map(f => Paths.get(rightPath, f))


      val lRDD = super.textFile(lFiles.mkString(","), lFiles.length).map(lMapper)
      val rRDD = super.textFile(rFiles.mkString(","), rFiles.length).map(rMapper)

      val thePartitions: Array[Partition] = depMap.iterator.zipWithIndex.map{ case ((lName, rNames),idx) =>
        val lID = lDict(lName)
        val rIDs = rNames.map(rDict).toList
        OneToManyPartition(idx,lRDD,rRDD,lID,rIDs)
      }.toArray

      new SpatialJoinRDD[STObject, V1,V2](lRDD, rRDD,pred,Some(indexer),true) {
        override def getPartitions: Array[Partition] = thePartitions
      }
    }
  }


  def tileFile[U: ClassTag](file: String, partitions: Int, qry: Option[STObject],f: String => U)(implicit ord: Ordering[U]): RasterRDD[U] = {

    val raw = qry match {
      case None =>
        super.textFile(file, partitions)
      case Some(so) =>
        this.textFile(file, so, partitions)
    }

    raw.map{ line =>
      val arr = line.split(',')
      val ulx = arr(0).toDouble
      val uly = arr(1).toDouble

      val tWidth = arr(2).toInt
      val tHeight = arr(3).toInt

      val pw = arr(4).toDouble
//      require(tWidth * tHeight == arr.length - 4, s"w*h = ${tWidth * tHeight} != ${arr.length - 4}")

//      val data = new Array[Double](arr.length - 5)
      val data = new Array[U](tWidth * tHeight)

//      Array.copy(arr, 4, data, 0, arr.length - 4)

      var i = 0
      while(i < data.length) {
        data(i) = f(arr(i+5))
        i += 1
      }

      val sma: Option[SMA[U]] = if(i < arr.length) {
        // we have SMA
        val min = f(arr(i))
        val max = f(arr(i+1))
        val avg = arr(i+2).toDouble

        Some(SMA(min, max, avg))
      } else
        None

      Tile[U](ulx, uly, tWidth, tHeight, data, pw, sma)
    }

//    val dir = new File(folderPath)
//    var fileList = List[File]()
//    if(dir.exists() && dir.isDirectory) {
//      fileList = dir.listFiles.filter(_.isFile).toList
//    }
//
//    var uplx = 0
//    var uply = 0
//    var tileList = List[Tile[Double]]()
//
//    for(f <- fileList) {
//      if(f.getName.startsWith(filePrefix)) {
//        val data = Source.fromFile(f)
//          .getLines().flatMap(_.split(",").map(_.toDouble))
//          .toArray
//
//        tileList ::= Tile[Double](uplx, uply + tileHeight, tileWidth, tileHeight, data)
//        if(uplx + tileWidth >= totalWidth) {//for now tiles will be loaded row by row
//          uplx = 0
//          uply += tileHeight
//        } else {
//          uplx += tileWidth
//        }
//      }
//    }
//
//    sc.parallelize(tileList)
  }

  def objectTiles[U: ClassTag](file: String, partitions: Int = 1024, query: Option[STObject])(implicit ord: Ordering[U]): RasterRDD[U] = {

    val byteRDD = this.objectFile[(STObject, Array[Byte])](file, query, partitions)

    val ser = new KryoSerializer(getConf)

    byteRDD.mapPartitions { iter =>

      val kryo = ser.newKryo()

      iter.map { case (_, arr) =>
        val input = new Input(arr)
        val tile = kryo.readObject(input, classOf[Tile[U]])

        tile

      }
    }
  }

  /**
    * Loads a raster data set where each tile is stored in its own file and parameters are encoded in file name
    * @param folderPath The folder containing all tile files
    * @param imageReader A function converting the image of a tile into a data array (grey scale, RGB, ...).E.g. [[dbis.stark.raster.RasterUtils.greyScaleImgToUnsignedByteArray]]
    * @return Returns a [[RasterRDD[Byte]] containing all tiles
    */
  def loadNanoFiles(folderPath: String, imageReader: BufferedImage => Array[Array[Int]] = RasterUtils.greyScaleImgToUnsignedByteArray, useSma: Boolean = false): RasterRDD[Int] = {

    super.binaryFiles(folderPath)
      .map { case (fqn, data) =>

        val pos = fqn.lastIndexOf("/")
        val name = fqn.substring(pos+1)
        (name.split("_"), data) }


//    println(s"a ${a.take(10).map(_._1.mkString("-")).mkString("\n")}")
    .filter(_._1.length == 10)
//    println(s"b ${b.count()}")

//    parallelize(Seq(Tile[Int](0, 0, 10, 10)))
          .map{case(nameEncodedData, binaryData) =>
            val str = binaryData.open()
            val img = ImageIO.read(str)
            str.close()

            val data = imageReader(img)


            val xCount = nameEncodedData(0)//Counter of Image x-axes
            val yCount = nameEncodedData(1)
    //        val zCount = nameEncodedData(2)
    //        val xCoord = nameEncodedData(3)//x-coordinate of image creation position (ul?)
    //        val yCoord = nameEncodedData(4)
    //        val zCoord = nameEncodedData(5)
    //        val pixelDeltaX = nameEncodedData(6)//distance between pixels in x-coordinate
    //        val pixelDeltaY = nameEncodedData(7)
    //        val pixelDeltaZ = nameEncodedData(8)

            val flatData = data.flatten

            val sma = SMA(flatData(0), flatData(0), 0.0)
            var i = 1

            val byteData = new Array[Int](flatData.length)
            byteData(0) = flatData(0).toByte

            while(i < flatData.length) {
              val b = flatData(i)
              if(useSma) {
                if (b < sma.min)
                  sma.min = b
                else if (b > sma.max)
                  sma.max = b
              }

              byteData(i) = b
              i += 1
            }

            // TODO what is -100 here? make it generic
            Tile((xCount.toDouble - 100) * img.getWidth(),
              ((yCount.toDouble - 100) + 1) * img.getHeight(),
              img.getWidth(), img.getHeight(), byteData, sma = if(useSma) Some(sma) else None)//this converts to signed again
          }
  }

  private[stark] def metaInfo(infoFile: String) = {
    super.textFile(infoFile) // load info file
      .map(_.split(STSparkContext.PARTITIONINFO_DELIM))
      .map { arr =>
        val stobj = {
          val interval = if (arr(1).isEmpty)
            None
          else {
            if (arr(2).isEmpty)
              Some(Interval(Instant(arr(1).toLong), None))
            else
              Some(Interval(Instant(arr(1).toLong), Instant(arr(2).toLong)))
          }

          if (interval.isDefined)
            STObject(arr(0), interval.get)
          else
            STObject(arr(0))
        }

        (stobj, arr(3))
      } // (STObject, path)
  }

  private[stark] def getPartitionsToLoad(path: String, qry: Option[STObject]): String = {
    val p = Paths.get(path)
    // the path to the info file
    val infoFile = p.resolve(STSparkContext.PARTITIONINFO_FILE).toString

    val isDir = dfs.isDirectory(new Path(path))
    val partitionsToLoad = if (qry.isDefined && isDir && dfs.exists(new Path(infoFile))) {
      val query = qry.get
      metaInfo(infoFile)
        .intersects(query) // find relevant partitions
        .map { case (_, file) => Paths.get(path, file).toString } // only path
        .collect() // fetch into single array
        .mkString(",") // make comma separated string for SparkContext#textFile

    } else if(isDir){
      p.resolve("part-*").toString
    } else // if it's not possible to load info file, load everything under this path
      path

    partitionsToLoad
  }
}
