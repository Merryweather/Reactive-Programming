package kvstore

import akka.actor.{ OneForOneStrategy, Props, ActorRef, Actor }
import kvstore.Arbiter._
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.actor.AllForOneStrategy
import akka.actor.ActorKilledException

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case object RetryPersist
  case class TimeOut(id: Long)

  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  override def supervisorStrategy = AllForOneStrategy(){
    case _:PersistenceException => Restart
    case _:ActorKilledException => Stop
  }

  context.system.scheduler.schedule(100 millis, 100 millis, context.self, RetryPersist)

  val beforeStart = arbiter ! Join

  val persistence = context.actorOf(persistenceProps)

  var kv = Map.empty[String, String]
  var secondaries = Map.empty[ActorRef, ActorRef]
  var replicators = Set.empty[ActorRef]
  var notAcked = Map.empty[Long, (ActorRef, Option[Persist], Set[ActorRef])]

  var expectedVersion = 0L
  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  val leader: Receive = {
    case Insert(key, value, id) => {
      kv += key -> value
      persistPrimary(id, key, Some(value))
    }
    case Remove(key, id) => {
      kv -= key
      persistPrimary(id, key, None)
    }
    case Get(key, id) => sender ! GetResult(key, kv.get(key), id)
    case Persisted(key, id) => {
      notAcked.get(id).map{entry =>
        val (client, persist, replicas) = entry
        notAcked += id -> (client, None, replicas)
        checkStatus(id)
      }
    }
    case Replicated(key, id) => {
      notAcked.get(id).map{entry =>
        notAcked += id -> (entry._1, entry._2, entry._3 + sender)
        checkStatus(id)
      }
    }
    case Replicas(replicas) =>
      var updatedReplicators = Set.empty[ActorRef]
      var updatedSecondaries = Map.empty[ActorRef, ActorRef]
      replicas.map{replica =>
        if(!replica.equals(self)) {
          val replicator = secondaries.getOrElse(replica, context.actorOf(Props(classOf[Replicator], replica)))
          if (!secondaries.contains(replica)) {
            kv.foreach{entry => replicator ! Replicate(entry._1, Some(entry._2), nextSeq)}
          }

          updatedReplicators += replicator
          replicators -= replicator  //Remove active ones from original set
          updatedSecondaries += (replica -> replicator)
        }
      }
      replicators.foreach(context.stop) //Stop those left in original set
      replicators = updatedReplicators
      secondaries = updatedSecondaries
      notAcked.keySet.foreach(checkStatus)
  }

  val replica: Receive = {
    case Get(key, id) => sender ! GetResult(key, kv.get(key), id)
    case Snapshot(key, valueOpt, seq) => {
      if (seq == expectedVersion) {
        valueOpt match {
          case None => kv -= key
          case Some(value) => kv += key -> value
        }
        persist(seq, key, valueOpt)
      }
      else if (seq < expectedVersion) sender ! SnapshotAck(key, seq)
    }
    case Persisted(key, id) => {
      notAcked.get(id).map(entry => {
        val (requester, persist, _) = entry
        persist.map(cmd => requester ! SnapshotAck(key, cmd.id))
        expectedVersion += 1
      })
      notAcked -= id
    }
  }

  def persist(id: Long, key: String, valueOpt: Option[String]) = {
    val command = Persist(key, valueOpt, id)
    notAcked += id -> (sender, Some(command), Set.empty[ActorRef])
    persistence ! command
  }

  def persistPrimary(id: Long, key: String, valueOpt: Option[String]) = {
    persist(id, key, valueOpt)
    replicators.foreach(_  ! Replicate(key, kv.get(key), id))
    context.system.scheduler.scheduleOnce(1000 millis, context.self, TimeOut(id))
  }

  def checkStatus(id: Long) = {
    notAcked.get(id).map{entry =>
      val (client, persist, reps) = entry
      persist match {
        case None if replicators.forall(reps.contains(_)) => client ! OperationAck(id); notAcked -= id
        case _ =>
      }
    }
  }
}