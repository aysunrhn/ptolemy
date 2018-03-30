package construct

import java.io.{File, PrintWriter}

import utilities.FileHandling.{openFileWithIterator, timeStamp, verifyDirectory}
import utilities.GFAutils.{ConstructGFA}

import scala.collection.immutable.{HashMap, HashSet}

/**
  * Author: Alex N. Salazar
  * Created on 16-2-2018
  * Contact a.n.salazar@tudelft.nl for questions
  *
  * Description:
  */
object HLGG extends ConstructGFA {

  case class Config(
                     syntenicAnchors: File = null,
                     database: File = null,
                     outputDir: File = null,
                     verbose: Boolean = false,
                     dump: Boolean = false
                   )

  def main(args: Array[String]) {
    val parser = new scopt.OptionParser[Config]("construct-hlgg") {
      opt[File]('s', "syntenic-anchors") required() action { (x, c) =>
        c.copy(syntenicAnchors = x)
      } text ("Path to file containing syntenic anchors.")
      opt[File]("db") required() action { (x, c) =>
        c.copy(database = x)
      } text ("Directory path of database (e.g. output directory of 'extract' module).")
      opt[File]('o', "output-directory") required() action { (x, c) =>
        c.copy(outputDir = x)
      } text ("Output directory.")
      note("\nOPTIONAL\n")
      opt[Unit]("dump") action { (x, c) =>
        c.copy(dump = true)
      }
      opt[Unit]("verbose") action { (x, c) =>
        c.copy(verbose = true)
      }

    }
    parser.parse(args, Config()).map { config =>
      //check whether output directory exists. If not, create it.
      verifyDirectory(config.database)
      constructHLGG(config)
    }
  }

  def constructHLGG(config: Config): Unit = {
    println("Fetching hashmap Z" + timeStamp)
    val path_hashmap_Z = config.database.listFiles().find(_.getName == "global_z.txt").get
    val path_hashmap_Y = config.database.listFiles().find(_.getName == "global_y.txt").get
    val path_orfids = config.database.listFiles().find(_.getName == "orf2id_mapping.txt").get
    println("Fetching starting node ID" + timeStamp)
    //get last orf id to be used as the starting node id for new nodes
    val node_id = {
      //open file as iterator
      val iterator = openFileWithIterator(path_orfids)
      //get last orf id, increment +1
      iterator.foldLeft(0)((last, line) => {
        val id = line.split("\t").last.toInt
        if (id > last) id else last
      }) + 1
    }
    //get total number of genomes
    val total_genomes = openFileWithIterator(path_hashmap_Y).toList.size
    println("--Starting node is: " + node_id)
    println("Formatting syntenic anchors" + timeStamp)
    //open syntenic anchors and format to hashmap as original orf id -> (assigned node id, genome count)
    val (syntenic_anchors, node_to_genome_count, next_id) =
      openFileWithIterator(config.syntenicAnchors).foldLeft(HashSet[Set[Int]]())((sa, line) => {
      val anchors = {
        val tmp = line.split("\t")
        (tmp.take(1) ++ tmp(1).split(",")).map(_.toInt).toSet
      }
      sa + (anchors)
    }).foldLeft((HashMap[Int,Int](), HashMap[Int,Int](), node_id)){ case((map, counts, id), sa) => {
        if(sa.size > total_genomes){
          println("--WARNING: unexpected number of nodes in a syntenic anchor. Discarding: " + sa.mkString(","))
          (map, counts, id)
        } else {
          (sa.foldLeft((map))((local_map, orf) => local_map + (orf -> id)), counts + (id -> sa.size), id + 1)
        }
      }}

    /**
      * Function to fetch node ID given an ORF ID
      * @return node ID as INT
      */
    def getNodeID: Int => Int = orf => {
      val get = syntenic_anchors.get(orf)
      if (get == None) orf else get.get
    }

    if (config.dump) {
      val pw = new PrintWriter(config.outputDir + "/node2genome_count.txt")
      node_to_genome_count.foreach(x => pw.println(x._1 + "\t" + x._2))
      pw.close
    }

    println("Constructing HLGG" + timeStamp)
    //iterate through each sequence and construct HLGG in context of syntenic anchors
    val (hlgg_edges, hlgg_nodes) =
      openFileWithIterator(path_hashmap_Z)
        //iterate through each sequence with global adjacency map and node set
        .foldLeft((HashMap[Int, Set[Int]](), HashSet[Int]())) { case ((adj_map, node_set), _sequence) => {
        //get sequence id and sequence of orf ids
        val (sequence, orfs) = getSequence(_sequence)
        orfs.size match {
          case 1 => (adj_map, node_set + orfs.head)
          case _ => //iterate through each orf as (node, edge)
            orfs.sliding(2).foldLeft((adj_map, node_set)) { case ((local_adj_map, local_node_set), adj) => {
              //determine node and edge id
              val node_id = getNodeID(adj(0))
              val edge_id = getNodeID(adj(1))
              //get current edges for current node
              val current = local_adj_map.getOrElse(node_id, Set[Int]())
              //update accordingly
              (local_adj_map + (node_id -> current.+(edge_id)), (local_node_set + (node_id)) + edge_id)
            }
        }
        }
      }
      }
    println("Constructed HLGG with " + hlgg_nodes.size + " nodes and " + hlgg_edges.map(_._2.size).sum +
      " edges" + timeStamp)
    println("Writing GFA file to disk" + timeStamp)
    val pw = new PrintWriter(config.outputDir + "/hlgg.gfa")
    pw.println(getGFAHeader)
    hlgg_nodes.foreach(x => pw.println(constructSegmentLine(x) + addGenomeCountField(node_to_genome_count.get(x))))
    hlgg_edges.foreach { case (node, edges) => edges.foreach(edge => pw.println(constructLinkLine(node, edge))) }
    //create paths for each sequence
    openFileWithIterator(path_hashmap_Z).foreach(entry => {
      //parse
      val (sequence, orfs) = getSequence(entry)
      //exchange orf id to new assigned node id if it was involved in an anchor
      val orf_to_node_ids = orfs.map(orf => {
        val get = syntenic_anchors.get(orf)
        if (get == None) orf + "+" else get.get + "+"
      })
      //output paths
      pw.println(Seq("P", sequence, orf_to_node_ids.mkString(",")).mkString("\t"))
    })
    openFileWithIterator(path_hashmap_Y).foreach(line => {
      val split = line.split("\t")
      pw.println(Seq("G", split.head, split(1)).mkString("\t"))
    })
    pw.close
    val pw_orfids = new PrintWriter(config.outputDir + "/orf2node_id.txt")
    openFileWithIterator(path_orfids).foreach(line => {
      val split = line.split("\t")
      val node_id = getNodeID(split(2).toInt)
      pw_orfids.println(Seq(split(0), split(1), node_id).mkString("\t"))
    })
    pw_orfids.close
    println("Successfully completed!" + timeStamp)
  }

  /**
    * Function to parse file containing hashmap Z
    *
    * @return Tuple (String, Seq[Int]) representing Sequence ID and sorted ORFs
    */
  def getSequence: String => (String, Seq[Int]) = line => {
    val split = line.split("\t")
    (split.head, split(1).split(",").map(_.toInt).toSeq)
  }

}