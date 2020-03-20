package com.cultureamp.eventsourcing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.datatype.joda.JodaModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*

class PostgresDatabaseEventStore private constructor(private val db: Database) : EventStore {
    companion object {
        fun create(db: Database): PostgresDatabaseEventStore {
            return PostgresDatabaseEventStore(db)
        }
    }

    override val listeners: MutableList<EventListener> = mutableListOf()

    override fun setup() {
        transaction(db) {
            // TODO don't do this if pointing directly to Murmur DB or potentially introduce separate migrations
            SchemaUtils.create(Events)
        }
    }

    override fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String): Either<CommandError, Unit> {
        return try {
            return transaction(db) {
                newEvents.forEach { event ->
                    val body = om.writeValueAsString(event.domainEvent)
                    val eventType = event.domainEvent.javaClass
                    // prove that json body can be deserialized, which catches invalid fields types, e.g. interfaces
                    om.readValue<DomainEvent>(body, eventType)
                    Events.insert { row ->
                        row[Events.aggregateSequence] = event.aggregateSequence
                        row[Events.eventId] = event.id
                        row[Events.aggregateId] = aggregateId
                        row[Events.aggregateType] = aggregateType
                        row[Events.eventType] = eventType.canonicalName
                        row[Events.createdAt] = DateTime.now()
                        row[Events.body] = body
                        row[Events.metadata] = om.writeValueAsString(event.metadata)
                    }
                }

                notifyListeners(newEvents, aggregateId)
                Right(Unit)
            }
        } catch (e: ExposedSQLException) {
            if (e.message.orEmpty().contains("violates unique constraint")) {
                Left(ConcurrencyError)
            } else {
                throw e
            }
        }
    }

    private fun rowToSequencedEvent(row: ResultRow): SequencedEvent = row.let {
        val type = row[Events.eventType].asClass<DomainEvent>()
        val domainEvent = om.readValue(row[Events.body], type)
        val metadata = om.readValue(row[Events.metadata], Metadata::class.java)
        SequencedEvent(Event(
            id = row[Events.eventId],
            aggregateId = row[Events.aggregateId],
            aggregateSequence = row[Events.aggregateSequence],
            createdAt = row[Events.createdAt],
            metadata = metadata,
            domainEvent = domainEvent
        ), row[Events.sequence])
    }

    override fun replay(aggregateType: String, project: (Event) -> Unit) {
        return transaction(db) {
            Events
                .select {
                    Events.aggregateType eq aggregateType
                }
                .orderBy(Events.sequence)
                .mapLazy(::rowToSequencedEvent)
                .mapLazy { it.event }
                .forEach(project)
        }
    }

    override fun getAfter(sequence: Long, batchSize: Int): Iterable<SequencedEvent> {
        return transaction(db) {
            Events
                .select {
                    Events.sequence greater sequence
                }
                .orderBy(Events.sequence)
                .limit(batchSize)
                .mapLazy(::rowToSequencedEvent)
        }
    }

    override fun eventsFor(aggregateId: UUID): List<Event> {
        return transaction(db) {
            Events
                .select { Events.aggregateId eq aggregateId }
                .orderBy(Events.sequence)
                .map(::rowToSequencedEvent)
                .map { it.event }
        }
    }
}

private fun <T> String.asClass(): Class<out T>? {
    return Class.forName(this) as Class<out T>?
}

val om = ObjectMapper().registerKotlinModule().registerModule(JodaModule()).configure(WRITE_DATES_AS_TIMESTAMPS, false)

object Events : Table() {
    val sequence = long("sequence").autoIncrement().index()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence").primaryKey(1)
    val aggregateId = uuid("aggregate_id").primaryKey(0)
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 128)
    val createdAt = date("createdAt")
    val body = jsonb("json_body")
    val metadata = jsonb("metadata")
}

object ConcurrencyError : RetriableError