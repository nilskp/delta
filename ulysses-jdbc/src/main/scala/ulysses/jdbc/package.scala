package ulysses

import java.util.UUID
import scala.reflect._
import java.math.BigInteger
import java.sql.ResultSet
import scuff.Numbers._

package object jdbc {
  object UUIDBinaryColumn extends ColumnType[UUID] {
    def typeName = "BINARY(16)"
    override def writeAs(uuid: UUID): Array[Byte] = {
      val bytes = new Array[Byte](16)
      longToBytes(uuid.getMostSignificantBits, bytes, 0)
      longToBytes(uuid.getLeastSignificantBits, bytes, 8)
    }
    def readFrom(row: ResultSet, col: Int) = {
      val bytes = row.getBytes(col)
      val msb = bytesToLong(bytes, 0)
      val lsb = bytesToLong(bytes, 8)
      new UUID(msb, lsb)
    }
  }
  object UUIDCharColumn extends ColumnType[UUID] {
    def typeName = "CHAR(36)"
    override def writeAs(uuid: UUID): String = uuid.toString
    def readFrom(row: ResultSet, col: Int) = UUID fromString row.getString(col)
  }
  implicit object LongColumn extends ColumnType[Long] {
    def typeName = "BIGINT"
    def readFrom(row: ResultSet, col: Int) = row.getLong(col)
  }
  implicit object IntColumn extends ColumnType[Int] {
    def typeName = "INT"
    def readFrom(row: ResultSet, col: Int) = row.getInt(col)
  }
  implicit object StringColumn extends ColumnType[String] {
    def typeName = "VARCHAR"
    def readFrom(row: ResultSet, col: Int) = row.getString(col)
  }
  implicit object BigIntColumn extends ColumnType[BigInt] {
    def typeName = "NUMERIC"
    def readFrom(row: ResultSet, col: Int): BigInt = row.getBigDecimal(col).toBigInteger
    override def writeAs(bint: BigInt) = new java.math.BigDecimal(bint.underlying)
  }
  implicit object UnitColumn extends ColumnType[Unit] {
    private[this] final val Zero = java.lang.Byte.valueOf(0.asInstanceOf[Byte])
    def typeName = "TINYINT"
    def readFrom(row: ResultSet, col: Int): Unit = ()
    override def writeAs(unit: Unit) = Zero
  }
  implicit def JavaEnumColumn[T <: java.lang.Enum[T]: ClassTag] =
    new ColumnType[T] with conv.JavaEnumType[T] {
      def typeName = "VARCHAR(255)"
      def readFrom(row: ResultSet, col: Int) = byName(row.getString(col))
    }
  abstract class ScalaEnumColumn[EV <: Enumeration#Value: ClassTag](val enum: Enumeration)
      extends ColumnType[EV] with conv.ScalaEnumType[EV] {
    def typeName = "VARCHAR(255)"
    def readFrom(row: ResultSet, col: Int) = byName(row.getString(col))
  }
}