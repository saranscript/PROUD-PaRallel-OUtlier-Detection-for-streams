package rk_query

import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.streaming.api.scala.function.ProcessWindowFunction
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector
import utils.Helpers.distance
import utils.Utils.Query
import models.Data_mcsky
import org.apache.flink.api.scala.metrics.ScalaGauge
import org.apache.flink.configuration.Configuration
import org.apache.flink.metrics.Counter

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class PmcskyCluster(var center: ListBuffer[Double], var points: ListBuffer[Int])
case class PmcskyState(var index: mutable.LinkedHashMap[Int, Data_mcsky], var MC: mutable.HashMap[Int, PmcskyCluster], var PD: mutable.HashSet[Int])

class PmcSky(c_queries: ListBuffer[Query]) extends ProcessWindowFunction[(Int, Data_mcsky), (Long, Query), Int, TimeWindow] {

  @transient private var counter: Counter = _
  @transient private var cpu_time: Long = 0L

  val queries: ListBuffer[Query] = c_queries
  val slide: Int = queries.head.S

  val R_distinct_list = queries.map(_.R).distinct.sorted
  val k_distinct_list = queries.map(_.k).distinct.sorted
  val R_max = R_distinct_list.max
  val R_min = R_distinct_list.min
  val k_max = k_distinct_list.max
  val k_min = k_distinct_list.min
  val k_size = k_distinct_list.size
  val R_size = R_distinct_list.size

  var mc_counter = 1

  lazy val state: ValueState[PmcskyState] = getRuntimeContext
    .getState(new ValueStateDescriptor[PmcskyState]("myState", classOf[PmcskyState]))

  override def process(key: Int, context: Context, elements: Iterable[(Int, Data_mcsky)], out: Collector[(Long, Query)]): Unit = {

    //Metrics
    counter.inc()
    val time_init = System.currentTimeMillis()

    val window = context.window

    //create state
    if (state.value == null) {
      val index = mutable.LinkedHashMap[Int, Data_mcsky]()
      val MC = mutable.HashMap[Int, PmcskyCluster]()
      val PD = mutable.HashSet[Int]()
      val current = PmcskyState(index, MC, PD)
      state.update(current)
    }

    val all_queries = Array.ofDim[Int](R_size, k_size)

    //insert new elements to state
    elements
      .filter(_._2.arrival >= window.getEnd - slide)
      //Sort is needed when each point has a different timestamp
      //In our case every point in the same slide has the same timestamp
      .toList
      .sortBy(_._2.arrival)
      .foreach(p => state.value().index += ((p._2.id, p._2)))

    //Update elements
    elements.foreach(p => {
      if (!p._2.safe_inlier && p._2.flag == 0 && p._2.mc == -1) {
        checkPoint(p._2, window)
        if(p._2.mc == -1){
          if (p._2.lSky.getOrElse(1, ListBuffer()).count(_._2 >= p._2.arrival) >= k_max) p._2.safe_inlier = true
          else{
            var i, y: Int = 0
            var count = p._2.lSky.getOrElse(i+1, ListBuffer()).count(_._2 >= window.getStart)
            do{
              if(count >= k_distinct_list(y)){ //inlier for all i
                y += 1
              }else{ //outlier for all y
                for(z <- y until k_size){
                  all_queries(i)(z) += 1
                }
                i += 1
                count += p._2.lSky.getOrElse(i+1, ListBuffer()).count(_._2 >= window.getStart)
              }
            }while(i < R_size && y < k_size)
          }
        }
      }
    })

    for (i <- 0 until R_size){
      for (y <- 0 until k_size){
        val tmpQuery = Query(R_distinct_list(i),k_distinct_list(y),queries.head.W,queries.head.S,all_queries(i)(y))
        out.collect(window.getEnd, tmpQuery)
      }
    }

    //Remove old points
    elements
      .filter(p => p._2.arrival < window.getStart + slide)
      .foreach(p => {
        deletePoint(p._2)
      })

    //Metrics
    val time_final = System.currentTimeMillis()
    cpu_time += (time_final - time_init)
  }

  def checkPoint(el: Data_mcsky, window: TimeWindow): Unit = {
    if (el.lSky.isEmpty && el.mc == -1) { //It's a new point!
      insertPoint(el)
    } else if (el.mc == -1) { //It's an old point
      updatePoint(el, window)
    }
  }

  def insertPoint(el: Data_mcsky): Unit = {
    //Check against MCs on 3 / 2 * R_max
    val closeMCs = findCloseMCs(el)
    //Check if closer MC is within R_min / 2
    val closerMC = if (closeMCs.nonEmpty)
      closeMCs.minBy(_._2)
    else
      (0, Double.MaxValue)
    if (closerMC._2 <= R_min / 2) { //Insert element to MC
      insertToMC(el, closerMC._1)
    }
    else { //Check against PD
      val NC = ListBuffer[Data_mcsky]() //List to hold points for new cluster formation
      state.value().index.values.toList.reverse //get the points so far from latest to earliest
        .takeWhile(p => {
        var res = true
        if (p.id != el.id) {
          if (closeMCs.keySet.contains(p.mc) || p.mc == -1) { //check only the points in PD and close MCs
            val thisDistance = distance(el.value.toArray, p.value.toArray)
            if (thisDistance <= R_max) {
              val skyRes = neighborSkyband(el, p, thisDistance)
              if (!skyRes && thisDistance <= R_min) res = false
              if (p.mc == -1 && thisDistance <= R_min / 2) NC += p
            }
          }
        }
        res
      })
      if (NC.size >= k_max) { //Create new MC
        createNewMC(el, NC)
      }
      else { //Insert in PD
        state.value().PD += el.id
      }
    }
  }

  def updatePoint(el: Data_mcsky, window: TimeWindow): Unit = {
    //Remove old points from lSky
    el.lSky.keySet.foreach(p => el.lSky.update(p, el.lSky(p).filter(_._2 >= window.getStart)))
    //Create input
    val old_sky = el.lSky.values.flatten.toList.sortWith((p1, p2) => p1._2 > p2._2).map(_._1)
    el.lSky.clear()

    var res = true //variable to stop skyband loop
    val NC = ListBuffer[Data_mcsky]() //List to hold points for new cluster formation
    state.value().index.values.toList.reverse //Check new points
      .takeWhile(p => {
      var tmpRes = true
      if (p.arrival >= window.getEnd - slide) {
        val thisDistance = distance(el.value.toArray, p.value.toArray)
        if (thisDistance <= R_max) {
          val skyRes = neighborSkyband(el, p, thisDistance)
          if (!skyRes && thisDistance <= R_min) res = false
          if (state.value().PD.contains(p.id) && thisDistance <= R_min / 2) NC += p
        }
      } else tmpRes = false
      res && tmpRes
    })

    if (res)
      old_sky.foreach(l => { //Check the old skyband elements
        val p = state.value().index(l)
        val thisDistance = distance(el.value.toArray, p.value.toArray)
        if (thisDistance <= R_max) {
          val skyRes = neighborSkyband(el, p, thisDistance)
          if (!skyRes && thisDistance <= R_min) res = false
          if (state.value().PD.contains(p.id) && thisDistance <= R_min / 2) NC += p
        }
      })
    if (NC.size >= k_max) createNewMC(el, NC) //Create new MC
    else state.value().PD += el.id //Insert in PD
  }

  def findCloseMCs(el: Data_mcsky): mutable.HashMap[Int, Double] = {
    val res = mutable.HashMap[Int, Double]()
    state.value().MC.foreach(mc => {
      val thisDistance = distance(el.value.toArray, mc._2.center.toArray)
      if (thisDistance <= (3 * R_max) / 2) res.+=((mc._1, thisDistance))
    })
    res
  }

  def insertToMC(el: Data_mcsky, mc: Int): Unit = {
    el.clear(mc)
    state.value().MC(mc).points += el.id
    state.value().PD.remove(el.id)
  }

  def createNewMC(el: Data_mcsky, NC: ListBuffer[Data_mcsky]): Unit = {
    NC += el
    NC.foreach(p => {
      p.clear(mc_counter)
      state.value().PD.remove(p.id)
    })
    val newMC = new PmcskyCluster(el.value, NC.map(_.id))
    state.value().MC += ((mc_counter, newMC))
    mc_counter += 1
  }

  def deletePoint(el: Data_mcsky): Unit = {
    if (el.mc == -1) { //Delete it from PD
      state.value().PD.remove(el.id)
    } else {
      state.value().MC(el.mc).points -= el.id
      if (state.value().MC(el.mc).points.size <= k_max) {
        state.value().MC(el.mc).points.foreach(p => {
          state.value().index(p).clear(-1)
        })
        state.value().MC.remove(el.mc)
      }
    }
    state.value().index.remove(el.id)
  }

  def neighborSkyband(el: Data_mcsky, neigh: Data_mcsky, distance: Double): Boolean = {
    val norm_dist = normalizeDistance(distance)
    var count = 0
    for (i <- 1 to norm_dist) {
      count += el.lSky.getOrElse(i, ListBuffer[Long]()).size
    }
    if (count <= k_max - 1) {
      el.lSky.update(norm_dist, el.lSky.getOrElse(norm_dist, ListBuffer[(Int, Long)]()) += ((neigh.id, neigh.arrival)))
      true
    } else {
      false
    }
  }

  def normalizeDistance(distance: Double): Int = {
    var res, i = 0
    do {
      if (distance <= R_distinct_list(i)) res = i + 1
      i += 1
    } while (i < R_distinct_list.size && res == 0)
    res
  }

  override def open(parameters: Configuration): Unit = {
    counter = getRuntimeContext()
      .getMetricGroup()
      .counter("MySlideCounter")

    getRuntimeContext()
      .getMetricGroup()
      .gauge[Long, ScalaGauge[Long]]("MyTotalTime", ScalaGauge[Long]( () => cpu_time ) )

    getRuntimeContext()
      .getMetricGroup()
      .gauge[Double, ScalaGauge[Double]]("MyAverageTime", ScalaGauge[Double]( () => {
        if(counter.getCount == 0) cpu_time.toDouble
        else cpu_time.toDouble / counter.getCount
      } ) )
  }
}
