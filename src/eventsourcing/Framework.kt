package eventsourcing

import java.util.UUID

interface Aggregate<UC: UpdateCommand, UE: UpdateEvent, E: CommandError, Self : Aggregate<UC, UE, E, Self>> {
    val aggregateId: UUID
    fun update(event: UE): Self
    fun handle(command: UC): Either<E, List<UE>>
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, E: CommandError, AggregateType> {
    fun create(event: CE): AggregateType
    fun handle(command: CC): Either<E, List<CE>>
}

//interface AggregateRootRepository {
//    fun <UC: UpdateCommand, UE: UpdateEvent, CommandError, Self : Aggregate<UC, UE, CommandError, Self>> get(aggregateId: UUID): Aggregate<UC, UE, CommandError, Aggregate<UC, UE, CommandError>>
//}

interface Command {
    val aggregateId: UUID
}

interface CreationCommand : Command {
    override val aggregateId: UUID
}

interface UpdateCommand : Command {
    override val aggregateId: UUID
}

interface Event

interface CreationEvent : Event {
    val aggregateId: UUID
}

interface UpdateEvent : Event

interface CommandError

class AggregateRootRegistry(val list: List<AggregateConstructor<out CreationCommand, out CreationEvent, out CommandError, out Aggregate<out UpdateCommand, out UpdateEvent, out CommandError, *>>>) {
    val commandToAggregateConstructor: Map<CreationCommand, AggregateConstructor<out CreationCommand, out CreationEvent, out CommandError, out Aggregate<out UpdateCommand, out UpdateEvent, out CommandError, *>>> =
        TODO()

    fun <CC : CreationCommand>aggregateRootConstructorFor(creationCommand: CC): AggregateConstructor<CC, *, *, *>? {
        return null
    }
}

sealed class Either<out E, out V>
data class Left<E>(val error: E) : Either<E, Nothing>()
data class Right<V>(val value: V) : Either<Nothing, V>() {
    companion object {
        fun <E,V> list(vararg values: V): Either<E,List<V>> = Right(listOf(*values))
    }
}

fun <E, V, R> Either<E, V>.map(transform: (V) -> R): Either<E, R> = when (this) {
    is Right -> Right(transform(this.value))
    is Left -> this
}
