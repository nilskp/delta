package sampler.aggr.dept

import sampler._
import ulysses.ddd.StateMutator

case class State(
  name: String,
  employees: Set[EmpId] = Set.empty)

class Mutator (
  var state: State = null)
    extends DeptEventHandler
    with StateMutator[DeptEvent, State] {

  protected def process(evt: DeptEvent) = dispatch(evt)

  type RT = Unit

  def this(state: Option[State]) = this(state.orNull)

  def on(evt: DeptCreated): RT = {
    require(state == null)
    state = new State(evt.name)
  }
  def on(evt: EmployeeAdded): RT = {
    state = state.copy(employees = state.employees + evt.id)
  }
  def on(evt: EmployeeRemoved): RT = {
    state = state.copy(employees = state.employees - evt.id)
  }
  def on(evt: NameChanged): RT = {
    state = state.copy(name = evt.newName)
  }

}