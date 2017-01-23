package delta.hazelcast

import java.util.Map.Entry

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap

import com.hazelcast.map.AbstractEntryProcessor

import delta.{ Fold, Snapshot, Transaction }

case class EntryState[D, EVT](
  snapshot: Snapshot[D],
  contentUpdated: Boolean = false,
  unapplied: TreeMap[Int, Transaction[_, EVT, _]] = TreeMap.empty[Int, Transaction[_, EVT, _]])

sealed abstract class EntryUpdateResult
case object IgnoredDuplicate extends EntryUpdateResult
case class MissingRevisions(range: Range) extends EntryUpdateResult
case class Updated[D](snapshot: Snapshot[D]) extends EntryUpdateResult

final class EntryStateUpdater[K, D, EVT](val snapshot: Snapshot[D])
    extends AbstractEntryProcessor[K, EntryState[D, EVT]](true) {

  type S = EntryState[D, EVT]

  def process(entry: Entry[K, S]): Object = {
    entry.getValue match {
      case null =>
        entry setValue new EntryState(snapshot, contentUpdated = true)

      case EntryState(null, _, unapplied) =>
        val remainingUnapplied = unapplied.dropWhile(_._1 <= snapshot.revision)
        entry setValue new EntryState(snapshot, contentUpdated = remainingUnapplied.isEmpty, remainingUnapplied)

      case EntryState(Snapshot(_, revision, _), _, unapplied) if snapshot.revision > revision =>
        val remainingUnapplied = unapplied.dropWhile(_._1 <= snapshot.revision)
        entry setValue new EntryState(snapshot, contentUpdated = remainingUnapplied.isEmpty, remainingUnapplied)

      case _ => // Ignore
    }
    null
  }

}

final class TransactionProcessor[K, D >: Null, EVT](
  val txn: Transaction[K, EVT, _],
  val stateFold: Fold[D, EVT])
    extends AbstractEntryProcessor[K, EntryState[D, EVT]](true) {

  type S = EntryState[D, EVT]

  private def apply(events: List[EVT], dataOrNull: D): D = events match {
    case Nil => dataOrNull.ensuring(_ != null)
    case evt :: tail => dataOrNull match {
      case null => apply(tail, stateFold.init(evt))
      case data => apply(tail, stateFold.next(data, evt))
    }
  }

  def process(entry: Entry[K, S]): Object = processTransaction(entry, this.txn)

  @tailrec
  private def processTransaction(entry: Entry[K, S], txn: Transaction[_, EVT, _]): EntryUpdateResult = {
    entry.getValue match {

      case null => // First transaction seen
        if (txn.revision == 0) { // First transaction, as expected
          val model = new Snapshot(apply(txn.events, null), txn.revision, txn.tick)
          entry setValue new S(model, true)
          Updated(model)
        } else { // Not first, so missing some
          entry setValue new S(null, false, TreeMap(txn.revision -> txn))
          MissingRevisions(0 until txn.revision)
        }

      case EntryState(null, _, unapplied) => // Un-applied transactions exists, no model yet
        if (txn.revision == 0) { // This transaction is first, so apply
          val model = new Snapshot(apply(txn.events, null), txn.revision, txn.tick)
          entry setValue new S(model, true, unapplied.tail)
          processTransaction(entry, unapplied.head._2)
        } else { // Still not first transaction
          val state = new S(null, false, unapplied.updated(txn.revision, txn))
          entry setValue state
          MissingRevisions(0 until state.unapplied.head._1)
        }

      case EntryState(model, _, unapplied) =>
        val expectedRev = model.revision + 1
        if (txn.revision == expectedRev) { // Expected revision, apply
          val updModel = new Snapshot(apply(txn.events, model.content), txn.revision, txn.tick)
          unapplied.headOption match {
            case None =>
              val dataUpdated = model.content != updModel.content
              entry setValue new S(updModel, dataUpdated)
              Updated(updModel)
            case Some((_, unappliedTxn)) =>
              entry setValue new S(updModel, false, unapplied.tail)
              processTransaction(entry, unappliedTxn)
          }
        } else if (txn.revision > expectedRev) { // Future revision, missing some
          val state = new S(model, false, unapplied.updated(txn.revision, txn))
          entry setValue state
          MissingRevisions(expectedRev until state.unapplied.head._1)
        } else {
          IgnoredDuplicate
        }

    }
  }

}