package delta.hazelcast

import java.util.Map.Entry

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap

import com.hazelcast.map.AbstractEntryProcessor

import delta.{ EventReducer, Snapshot, Transaction }
import com.hazelcast.core.IMap
import scala.concurrent.Future
import scala.reflect.{ ClassTag, classTag }
import scuff.Codec

case class EntryState[S, EVT](
  snapshot: Snapshot[S],
  contentUpdated: Boolean = false,
  unapplied: TreeMap[Int, Transaction[_, EVT]] = TreeMap.empty[Int, Transaction[_, EVT]])

sealed abstract class EntryUpdateResult
case object IgnoredDuplicate extends EntryUpdateResult
case class MissingRevisions(range: Range) extends EntryUpdateResult
case class Updated[S](snapshot: Snapshot[S]) extends EntryUpdateResult

object DistributedMonotonicProcessor {
  /**
    * Process transaction, ensuring proper sequencing.
    */
  def apply[K, EVT: ClassTag, WS >: Null, RS >: Null](imap: IMap[K, EntryState[RS, EVT]], stateCodec: Codec[RS, WS], reducer: EventReducer[WS, EVT])(
    txn: Transaction[K, _ >: EVT]): Future[EntryUpdateResult] = {
    val verifiedTxn: Transaction[K, EVT] = {
      txn.events.collect { case evt: EVT => evt } match {
        case Nil => sys.error(s"${txn.channel} transaction ${txn.stream}(rev:${txn.revision}) events does not conform to ${classTag[EVT].runtimeClass.getName}")
        case events => txn.copy(events = events)
      }
    }
    val processor = new DistributedMonotonicProcessor[K, EVT, WS, RS](verifiedTxn, stateCodec, reducer)
    val callback = CallbackPromise[EntryUpdateResult]
    imap.submitToKey(txn.stream, processor, callback)
    callback.future
  }
  /**
    * Process transaction, ensuring proper sequencing.
    */
  def apply[K, EVT: ClassTag, S >: Null](imap: IMap[K, EntryState[S, EVT]], reducer: EventReducer[S, EVT])(
    txn: Transaction[K, _ >: EVT]): Future[EntryUpdateResult] = {
    val verifiedTxn: Transaction[K, EVT] = {
      txn.events.collect { case evt: EVT => evt } match {
        case Nil => sys.error(s"${txn.channel} transaction ${txn.stream}(rev:${txn.revision}) events does not conform to ${classTag[EVT].runtimeClass.getName}")
        case events => txn.copy(events = events)
      }
    }
    val processor = new DistributedMonotonicProcessor[K, EVT, S, S](verifiedTxn, Codec.noop, reducer)
    val callback = CallbackPromise[EntryUpdateResult]
    imap.submitToKey(txn.stream, processor, callback)
    callback.future
  }
}

/**
 *  Distributed monotonic [[delta.Transaction]] entry processor, ensuring
 *  monotonic stream revision ordering.
 */
final class DistributedMonotonicProcessor[K, EVT, WS >: Null, RS >: Null] private[hazelcast] (
  val txn: Transaction[K, EVT],
  val stateCodec: Codec[RS, WS],
  val reducer: EventReducer[WS, EVT])(implicit val evtTag: ClassTag[EVT])
    extends AbstractEntryProcessor[K, EntryState[RS, EVT]](true) {

  type EntryState = delta.hazelcast.EntryState[RS, EVT]
  type TXN = Transaction[_, EVT]

  private[this] val reduce = EventReducer.process(reducer, stateCodec) _

  def process(entry: Entry[K, EntryState]): Object = processTransaction(entry, this.txn)

  @tailrec
  private def processTransaction(entry: Entry[K, EntryState], txn: TXN): EntryUpdateResult = {
    entry.getValue match {

      case null => // First transaction seen
        if (txn.revision == 0) { // First transaction, as expected
          val snapshot = new Snapshot(reduce(None, txn.events), txn.revision, txn.tick)
          entry setValue new EntryState(snapshot, contentUpdated = true)
          Updated(snapshot)
        } else { // Not first, so missing some
          entry setValue new EntryState(null, contentUpdated = false, TreeMap(txn.revision -> txn))
          MissingRevisions(0 until txn.revision)
        }

      case EntryState(null, _, unapplied) => // Un-applied transactions exists, no snapshot yet
        if (txn.revision == 0) { // This transaction is first, so apply
          val snapshot = new Snapshot(reduce(None, txn.events), txn.revision, txn.tick)
          entry setValue new EntryState(snapshot, contentUpdated = true, unapplied.tail)
          processTransaction(entry, unapplied.head._2)
        } else { // Still not first transaction
          val state = new EntryState(null, contentUpdated = false, unapplied.updated(txn.revision, txn))
          entry setValue state
          MissingRevisions(0 until state.unapplied.head._1)
        }

      case EntryState(snapshot, _, unapplied) =>
        val expectedRev = snapshot.revision + 1
        if (txn.revision == expectedRev) { // Expected revision, apply
          val updSnapshot = new Snapshot(reduce(Some(snapshot.content), txn.events), txn.revision, txn.tick)
          unapplied.headOption match {
            case None =>
              val contentUpdated = !(snapshot contentEquals updSnapshot)
              entry setValue new EntryState(updSnapshot, contentUpdated)
              Updated(updSnapshot)
            case Some((_, unappliedTxn)) =>
              entry setValue new EntryState(updSnapshot, contentUpdated = false, unapplied.tail)
              processTransaction(entry, unappliedTxn)
          }
        } else if (txn.revision > expectedRev) { // Future revision, missing some
          val state = new EntryState(snapshot, contentUpdated = false, unapplied.updated(txn.revision, txn))
          entry setValue state
          MissingRevisions(expectedRev until state.unapplied.head._1)
        } else {
          IgnoredDuplicate
        }

    }
  }

}
