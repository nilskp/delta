package delta

import scuff.FakeType

@SerialVersionUID(1)
final case class Transaction[+ID, +EVT](
    tick: Long,
    channel: Transaction.Channel,
    stream: ID,
    revision: Int,
    metadata: Map[String, String],
    events: List[EVT]) {
  private def writeObject(out: java.io.ObjectOutputStream): Unit = {
    Transaction.Serialization.writeObject(this, out)
  }
  private def readObject(inp: java.io.ObjectInputStream): Unit = {
    Transaction.Serialization.readObject[ID, EVT](inp) {
      case (tick, ch, id, rev, metadata, events) =>
        val surgeon = new scuff.reflect.Surgeon(this)
        surgeon.set('tick, tick)
        surgeon.set('channel, ch)
        surgeon.set('stream, id)
        surgeon.set('revision, rev)
        surgeon.set('metadata, metadata)
        surgeon.set('events, events)
        this
    }
  }

}

object Transaction {

  type Channel = Channel.Type
  val Channel: FakeType[String] { type Type <: AnyRef } = new FakeType[String] {
    type Type = String
    def apply(str: String) = str
  }

  object Serialization {
    def writeObject(txn: Transaction[_, _], out: java.io.ObjectOutput): Unit = {
      out.writeLong(txn.tick)
      out.writeUTF(txn.channel.toString)
      out.writeObject(txn.stream)
      out.writeInt(txn.revision)
      out.writeChar(txn.metadata.size)
      txn.metadata.foreach {
        case (key, value) =>
          out.writeUTF(key)
          out.writeUTF(value)
      }
      txn.events.reverse.foreach(out.writeObject)
      out.writeObject(null)
    }
    @annotation.tailrec
    private def readEvents(
        in: java.io.ObjectInput,
        events: List[_ <: AnyRef] = Nil): List[_ <: AnyRef] =
      in.readObject match {
        case null => events
        case evt => readEvents(in, evt :: events)
      }
    def readObject[ID, EVT](
        inp: java.io.ObjectInput)(
        ctor: (Long, Channel, ID, Int, Map[String, String], List[EVT]) => Transaction[ID, EVT]): Transaction[ID, EVT] = {
      val tick = inp.readLong()
      val ch = Channel(inp.readUTF)
      val id = inp.readObject.asInstanceOf[ID]
      val rev = inp.readInt()
      val mdSize: Int = inp.readChar()
      var metadata = Map.empty[String, String]
      while (metadata.size < mdSize) {
        metadata += inp.readUTF -> inp.readUTF
      }
      val events = readEvents(inp).asInstanceOf[List[EVT]]
      ctor(tick, ch, id, rev, metadata, events)
    }
  }
}
