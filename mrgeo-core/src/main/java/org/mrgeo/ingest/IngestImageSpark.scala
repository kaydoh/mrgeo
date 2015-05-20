package org.mrgeo.ingest

import java.awt.image.{DataBuffer, Raster, WritableRaster}
import java.io.{Externalizable, ObjectInput, ObjectOutput}
import java.util
import java.util.Properties

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.{PairRDDFunctions, RDD}
import org.gdal.gdal.gdal
import org.gdal.gdalconst.gdalconstConstants
import org.mrgeo.data.DataProviderFactory
import org.mrgeo.data.DataProviderFactory.AccessMode
import org.mrgeo.data.image.MrsImageDataProvider
import org.mrgeo.data.ingest.{ImageIngestDataProvider, ImageIngestWriterContext}
import org.mrgeo.data.raster.{RasterUtils, RasterWritable}
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.hdfs.partitioners.{ImageSplitGenerator, TileIdPartitioner}
import org.mrgeo.hdfs.utils.HadoopFileUtils
import org.mrgeo.image.MrsImagePyramid
import org.mrgeo.spark.SparkTileIdPartitioner
import org.mrgeo.spark.job.{JobArguments, MrGeoDriver, MrGeoJob}
import org.mrgeo.utils.{Bounds, GDALUtils, HadoopUtils, TMSUtils}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer


object IngestImageSpark extends MrGeoDriver with Externalizable {

  def ingest(inputs: Array[String], output: String,
      categorical: Boolean, conf: Configuration, bounds: Bounds,
      zoomlevel: Int, tilesize: Int, nodata: Number, bands: Int, tiletype:Int,
      tags: java.util.Map[String, String], protectionLevel: String,
      providerProperties: Properties):Boolean = {

    val name = "IngestImage"

    val args = setupParams(inputs.mkString(","), output, categorical, bounds, zoomlevel, tilesize, nodata, bands, tiletype, tags, protectionLevel,
      providerProperties)

    run(name, classOf[IngestImageSpark].getName, args.toMap, conf)

    true
  }

  private def setupParams(input: String, output: String, categorical: Boolean, bounds: Bounds, zoomlevel: Int, tilesize: Int, nodata: Number,
      bands: Int, tiletype: Int, tags: util.Map[String, String], protectionLevel: String,
      providerProperties: Properties): mutable.Map[String, String] = {

    val args =  mutable.Map[String, String]()

    args += "inputs" -> input
    args += "output" -> output
    args += "bounds" -> bounds.toDelimitedString
    args += "zoom" -> zoomlevel.toString
    args += "tilesize" -> tilesize.toString
    args += "nodata" -> nodata.toString
    args += "bands" -> bands.toString
    args += "tiletype" -> tiletype.toString
    args += "categorical" -> categorical.toString

    var t: String = ""
    tags.foreach(kv => {
      if (t.length > 0) {
        t += ","
      }
      t += kv._1 + "=" + kv._2
    })

    args += "tags" -> t
    args += "protection" -> protectionLevel

    var p: String = ""
    providerProperties.foreach(kv => {
      if (p.length > 0) {
        p += "||"
      }
      p += kv._1 + "=" + kv._2
    })
    args += "providerproperties" -> p

    args
  }

  def localIngest(inputs: Array[String], output: String,
      categorical: Boolean, config: Configuration, bounds: Bounds,
      zoomlevel: Int, tilesize: Int, nodata: Number, bands: Int, tiletype:Int,
      tags: java.util.Map[String, String], protectionLevel: String,
      providerProperties: Properties):Boolean = {

    val provider: ImageIngestDataProvider = DataProviderFactory
        .getImageIngestDataProvider(HadoopFileUtils.createUniqueTmpPath().toUri.toString, AccessMode.OVERWRITE)

    var conf: Configuration = config
    if (conf == null) {
      conf = HadoopUtils.createConfiguration
    }

    //    final Path unique = HadoopFileUtils.createUniqueTmpPath();
    val context = new ImageIngestWriterContext()
    context.setZoomlevel(zoomlevel)
    context.setPartNum(0)

    val writer = provider.getMrsTileWriter(context)

    inputs.foreach(input => {
      val tiles =  IngestImageSpark.makeTiles(input, zoomlevel, tilesize, categorical)

      tiles.foreach(kv => {
        writer.append(new TileIdWritable(kv._1.get()), RasterWritable.toRaster(kv._2))
      })
    })

    writer.close()

    val args = setupParams(writer.getName, output, categorical, bounds, zoomlevel, tilesize, nodata, bands, tiletype, tags, protectionLevel,
      providerProperties)

    val name = "IngestImageLocal"

    run(name, classOf[IngestLocalSpark].getName, args.toMap, conf)

    provider.delete()

    true
  }

  private def makeTiles(image: String, zoom:Int, tilesize:Int, categorical:Boolean): TraversableOnce[(TileIdWritable, RasterWritable)] = {

    val result = ListBuffer[(TileIdWritable, RasterWritable)]()

    //val start = System.currentTimeMillis()

    // open the image
    val src = GDALUtils.open(image)

    val datatype = src.GetRasterBand(1).getDataType
    val datasize = gdal.GetDataTypeSize(datatype) / 8

    val bands = src.GetRasterCount()

    val imageBounds = GDALUtils.getBounds(src).getTMSBounds
    val tiles = TMSUtils.boundsToTile(imageBounds, zoom, tilesize)
    val tileBounds = TMSUtils.tileBounds(imageBounds, zoom, tilesize)

    val w = tiles.width() * tilesize
    val h = tiles.height() * tilesize

    val res = TMSUtils.resolution(zoom, tilesize)


    val scaledsize = w * h * bands * datasize

    // TODO:  Figure out chunking...
    val scaled = GDALUtils.createEmptyMemoryRaster(src, w.toInt, h.toInt)

    val xform = Array.ofDim[Double](6)

    xform(0) = tileBounds.w /* top left x */
    xform(1) = res /* w-e pixel resolution */
    xform(2) = 0 /* 0 */
    xform(3) = tileBounds.n /* top left y */
    xform(4) = 0 /* 0 */
    xform(5) = -res /* n-s pixel resolution (negative value) */

    scaled.SetGeoTransform(xform)
    scaled.SetProjection(GDALUtils.EPSG4326)


    var resample:Int = gdalconstConstants.GRA_Bilinear
    if (categorical) {
      // use gdalconstConstants.GRA_Mode for categorical, which may not exist in earlier versions of gdal,
      // in which case we will use GRA_NearestNeighbour
      try {
        val mode = classOf[gdalconstConstants].getDeclaredField("foo")
        if (mode != null) {
          resample = mode.getInt()
        }
      }
      catch {
        case e:Exception => resample = gdalconstConstants.GRA_NearestNeighbour
      }
    }

    gdal.ReprojectImage(src, scaled, src.GetProjection(), GDALUtils.EPSG4326, resample)

    //    val time = System.currentTimeMillis() - start
    //    println("scale: " + time)

    //    val band = scaled.GetRasterBand(1)
    //    val minmax = Array.ofDim[Double](2)
    //    band.ComputeRasterMinMax(minmax, 0)

    //GDALUtils.saveRaster(scaled, "/data/export/scaled.tif")

    // close the image
    GDALUtils.close(image)

    val bandlist = Array.ofDim[Int](bands)
    for (x <- 0 until bands)
    {
      bandlist(x) = x + 1  // bands are ones based
    }


    val buffer = Array.ofDim[Byte](datasize * tilesize * tilesize * bands)

    for (dty <- 0 until tiles.height.toInt) {
      for (dtx <- 0 until tiles.width().toInt) {

        //val start = System.currentTimeMillis()

        val tx:Long = dtx + tiles.w
        val ty:Long = tiles.n - dty

        val x: Int = dtx * tilesize
        val y: Int = dty * tilesize

        val success = scaled.ReadRaster(x, y, tilesize, tilesize, tilesize, tilesize, datatype, buffer, null)

        if (success != gdalconstConstants.CE_None)
        {
          println("Failed reading raster" + success)
        }

        // switch the byte order...
        GDALUtils.swapBytes(buffer, datatype)

        val writable = RasterWritable.toWritable(buffer, tilesize, tilesize,
          bands, GDALUtils.toRasterDataBufferType(datatype))

        // save the tile...
        //        GDALUtils.saveRaster(RasterWritable.toRaster(writable),
        //          "/data/export/tiles/tile-" + ty + "-" + tx, tx, ty, zoom, tilesize, GDALUtils.getnodata(scaled))

        result.append((new TileIdWritable(TMSUtils.tileid(tx, ty, zoom)), writable))


        //val time = System.currentTimeMillis() - start
        //println(tx + ", " + ty + ", " + time)
      }
    }

    scaled.delete()

    result.iterator
  }


  override def readExternal(in: ObjectInput) {}
  override def writeExternal(out: ObjectOutput) {}
}

class IngestImageSpark extends MrGeoJob with Externalizable {
  var inputs: Array[String] = null
  var output:String = null
  var bounds:Bounds = null
  var zoom:Int = -1
  var bands:Int = -1
  var tiletype:Int = -1
  var tilesize:Int = -1
  var nodata:Number = Double.NaN
  var categorical:Boolean = false
  var providerproperties:Properties = null
  var protectionlevel:String = null



  override def registerClasses(): Array[Class[_]] = {
    val classes = Array.newBuilder[Class[_]]

    classes += classOf[TileIdWritable]
    classes += classOf[RasterWritable]

    classes.result()

  }

  override def setup(job: JobArguments): Boolean = {

    inputs = job.getSetting("inputs").split(",")
    output = job.getSetting("output")

    bounds = Bounds.fromDelimitedString(job.getSetting("bounds"))
    zoom = job.getSetting("zoom").toInt
    bands = job.getSetting("bands").toInt
    tiletype = job.getSetting("tiletype").toInt
    tilesize = job.getSetting("tilesize").toInt
    nodata = job.getSetting("nodata").toDouble
    categorical = job.getSetting("categorical").toBoolean

    protectionlevel = job.getSetting("protection")
    if (protectionlevel == null)
    {
      protectionlevel = ""
    }

    val props = job.getSetting("providerproperties").split("||")
    providerproperties = new Properties()
    props.foreach (prop => {
      if (prop.contains("=")) {
        val kv = prop.split("=")
        providerproperties.put(kv(0), kv(1))
      }
    })

    true
  }


  private def copyPixel(x: Int, y: Int, b: Int, src: Raster, dst: WritableRaster): Unit = {
    src.getTransferType match {
    case DataBuffer.TYPE_BYTE =>
      val p: Byte = src.getSample(x, y, b).toByte
      if (p != nodata.byteValue()) {
        dst.setSample(x, y, b, p)
      }
    case DataBuffer.TYPE_FLOAT =>
      val p: Float = src.getSampleFloat(x, y, b)
      if (!p.isNaN && p != nodata.floatValue()) {
        dst.setSample(x, y, b, p)
      }
    case DataBuffer.TYPE_DOUBLE =>
      val p: Double = src.getSampleDouble(x, y, b)
      if (!p.isNaN && p != nodata.doubleValue()) {
        dst.setSample(x, y, b, p)
      }
    case DataBuffer.TYPE_INT =>
      val p: Int = src.getSample(x, y, b)
      if (p != nodata.intValue()) {
        dst.setSample(x, y, b, p)
      }
    case DataBuffer.TYPE_SHORT =>
      val p: Short = src.getSample(x, y, b).toShort
      if (p != nodata.shortValue()) {
        dst.setSample(x, y, b, p)
      }
    case DataBuffer.TYPE_USHORT =>
      val p: Int = src.getSample(x, y, b)
      if (p != nodata.intValue()) {
        dst.setSample(x, y, b, p)
      }
    }
  }

  protected def mergeTile(r1: RasterWritable, r2: RasterWritable):RasterWritable = {
    val src = RasterWritable.toRaster(r1)
    val dst = RasterUtils.makeRasterWritable(RasterWritable.toRaster(r2))

    for (y <- 0 until src.getHeight) {
      for (x <- 0 until src.getWidth) {
        for (b <- 0 until src.getNumBands) {
          copyPixel(x, y, b, src, dst)
        }
      }
    }

    RasterWritable.toWritable(dst)
  }

  override def execute(context: SparkContext): Boolean = {

    val in = context.makeRDD(inputs)

    val rawtiles = new PairRDDFunctions(in.flatMap(input => {
      IngestImageSpark.makeTiles(input, zoom, tilesize, categorical)
    }))

    val mergedTiles=rawtiles.reduceByKey((r1, r2) => {
      mergeTile(r1, r2)
    })

    saveRDD(mergedTiles)

    true
  }

  protected def saveRDD(tiles: RDD[(TileIdWritable, RasterWritable)]): Unit = {
    implicit val tileIdOrdering = new Ordering[TileIdWritable] {
      override def compare(x: TileIdWritable, y: TileIdWritable): Int = x.compareTo(y)
    }


    val job: Job = new Job(HadoopUtils.createConfiguration())

    val tileIncrement = 1

    job.getConfiguration.setInt(TileIdPartitioner.INCREMENT_KEY, tileIncrement)
    // save the new pyramid
    val dp = MrsImageDataProvider.setupMrsPyramidOutputFormat(job, output, bounds, zoom,
      tilesize, tiletype, bands, protectionlevel, providerproperties)

    val tileBounds = TMSUtils.boundsToTile(bounds.getTMSBounds, zoom, tilesize)

    val splitGenerator = new ImageSplitGenerator(tileBounds.w, tileBounds.s,
      tileBounds.e, tileBounds.n, zoom, tileIncrement)

    val sparkPartitioner = new SparkTileIdPartitioner(splitGenerator)

    val sorted = tiles.sortByKey().partitionBy(sparkPartitioner)
    // this is missing in early spark APIs
    //val sorted = mosaiced.repartitionAndSortWithinPartitions(sparkPartitioner)

    // save the image
    sorted.saveAsNewAPIHadoopDataset(job.getConfiguration)

    dp.teardown(job)

    // calculate and save metadata
    val nodatas = Array.ofDim[Double](bands)
    for (x <- 0 until nodatas.length) {
      nodatas(x) = nodata.doubleValue()
    }

    MrsImagePyramid.calculateMetadata(output, zoom, dp.getMetadataWriter, null,
      nodatas, bounds, job.getConfiguration, protectionlevel, providerproperties)
  }

  override def teardown(job: JobArguments): Boolean = {
    true
  }

  override def readExternal(in: ObjectInput) {
    inputs = in.readUTF().split(",")
    output = in.readUTF()
    bounds = Bounds.fromDelimitedString(in.readUTF())
    zoom = in.readInt()
    tilesize = in.readInt()
    tiletype = in.readInt()
    bands = in.readInt()
    nodata = in.readDouble()
    categorical = in.readBoolean()

    providerproperties = in.readObject().asInstanceOf[Properties]
    protectionlevel = in.readUTF()


  }

  override def writeExternal(out: ObjectOutput) {
    out.writeUTF(inputs.mkString(","))
    out.writeUTF(output)
    out.writeUTF(bounds.toDelimitedString)
    out.writeInt(zoom)
    out.writeInt(tilesize)
    out.writeInt(tiletype)
    out.writeInt(bands)
    out.writeDouble(nodata.doubleValue())
    out.writeBoolean(categorical)

    out.writeObject(providerproperties)

    out.writeUTF(protectionlevel)

  }
}
