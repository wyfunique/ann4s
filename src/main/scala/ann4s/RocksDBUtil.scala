package ann4s

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util

import org.apache.spark.ml.linalg.{Vector => MlVector}
import org.rocksdb._

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object RocksDBUtil {

  def destroy(path: String): Unit = {
    val options = new Options()
    RocksDB.destroyDB(path, options)
    options.close()
  }

  def writeIndex(index: Index): String = {
    val file = File.createTempFile("sstutil_index.", ".sst").toString
    val envOptions = new EnvOptions
    val options = new Options
    val writer = new SstFileWriter(envOptions, options)
    writer.open(file)

    val nodes = index.nodes.zipWithIndex
    nodes foreach { case (node, i) =>
      val key = ByteBuffer.allocate(4).putInt(i).array()
      val value = node.toByteArray
      writer.put(key, value)
    }
    writer.finish()
    writer.close()
    options.close()
    envOptions.close()
    println(s"index is written in $file")
    file
  }

  def serializeItemSstFile(it: Iterator[(Int, MlVector, String)]): Array[Byte] = {
    val file = File.createTempFile("sstutil_item.", ".sst")
    val envOptions = new EnvOptions
    val options = new Options
    val writer = new SstFileWriter(envOptions, options)
    writer.open(file.toString)
    it foreach { case (id, vector, metadata) =>
      val metadataBytes = metadata.getBytes("UTF-8")
      val bb = ByteBuffer.allocate(4 + vector.size * 4 + metadataBytes.length)
      bb.putInt(vector.size)
      vector.toArray foreach { x => bb.putFloat(x.toFloat) }
      bb.put(metadataBytes)
      val key = ByteBuffer.allocate(4).putInt(id).array()
      val value = bb.array()
      writer.put(key, value)
    }
    writer.finish()
    writer.close()
    options.close()
    envOptions.close()
    val serialized = Files.readAllBytes(Paths.get(file.toString))
    file.delete()
    serialized
  }

  def mergeAll(path: String, indexSstFile: String, serializedItemSstFiles: Iterator[(Int, Array[Byte])]): Unit = {
    val writtenSstFiles = serializedItemSstFiles map { case (i, serialized) =>
      val file = Files.createTempFile(f"sstutil_item_$i%08d.", ".sst")
      Files.write(file, serialized, StandardOpenOption.WRITE)
      println(s"item $i is written in $file")
      file.toFile.toString
    }

    val dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
    val columnFamilyDescriptors = new util.ArrayList[ColumnFamilyDescriptor]()
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY))
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor("index".getBytes()))
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor("items".getBytes()))
    val columnFamilyHandles = new util.ArrayList[ColumnFamilyHandle]()
    val db = RocksDB.open(dbOptions, path, columnFamilyDescriptors, columnFamilyHandles)

    val ingestExternalFileOptions = new IngestExternalFileOptions()
    ingestExternalFileOptions.setMoveFiles(true)

    val externalIndexSstFiles = new util.ArrayList[String]()
    externalIndexSstFiles.add(indexSstFile)
    db.ingestExternalFile(columnFamilyHandles.get(1), externalIndexSstFiles, ingestExternalFileOptions)

    val externalItemSstFiles = new util.ArrayList[String]()
    writtenSstFiles.toSeq.sorted foreach externalItemSstFiles.add
    db.ingestExternalFile(columnFamilyHandles.get(2), externalItemSstFiles, ingestExternalFileOptions)

    ingestExternalFileOptions.close()
    dbOptions.close()
    columnFamilyHandles.asScala foreach (_.close)
    db.close()
  }

  def openIndex(path: String): Index = {
    val dbOptions = new DBOptions()
    val columnFamilyDescriptors = new util.ArrayList[ColumnFamilyDescriptor]()
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY))
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor("index".getBytes()))
    columnFamilyDescriptors.add(new ColumnFamilyDescriptor("items".getBytes()))
    val columnFamilyHandles = new util.ArrayList[ColumnFamilyHandle]()
    val db = RocksDB.openReadOnly(dbOptions, path, columnFamilyDescriptors, columnFamilyHandles)

    val readOptions = new ReadOptions()
    val it = db.newIterator(columnFamilyHandles.get(1), readOptions)
    it.seekToFirst()
    var expectedId = 0
    val nodes = ArrayBuffer[Node]()
    while (it.isValid) {
      val actualId = ByteBuffer.wrap(it.key()).getInt()
      val node = Node.fromByteArray(it.value())
      require(actualId == expectedId, s"actualId: $actualId, expectedId: $expectedId")
      nodes += node
      it.next()
      expectedId += 1
    }

    new Index(nodes, { leafNodeId =>
      val value = db.get(columnFamilyHandles.get(1),
        readOptions, ByteBuffer.allocate(4).putInt(leafNodeId).array())
      Node.fromByteArray(value) match {
        case LeafNode(children) => children
      }
    }, { itemId =>
      val value = db.get(columnFamilyHandles.get(2),
        readOptions, ByteBuffer.allocate(4).putInt(itemId).array())
      val bb = ByteBuffer.wrap(value)
      val size = bb.getInt()
      val floats = Array.fill(size)(bb.getFloat)
      val metadata = new String(bb.array(), bb.position(), bb.capacity() - bb.position())
      Vector32(floats) -> metadata
    })
  }

}
