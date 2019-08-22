import com.fasterxml.jackson.core.json.ReaderBasedJsonParser
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import eventsourcing.*
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.pipeline.PipelineContext
import survey.design.*
import survey.thing.ThingAggregate
import survey.thing.ThingCommand
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ClassCastException
import kotlin.reflect.KClass

// custom app wiring
val surveyNamesProjection = StubSurveyNamesProjection
val commandToConstructor: Map<KClass<out Command>, AggregateConstructor<*, *, *, *, *, *>> = mapOf(
    ThingCommand::class to ThingAggregate,
    SurveyCaptureLayoutCommand::class to SurveyCaptureLayoutAggregate,
    SurveyCommand::class to SurveyAggregate.curried(surveyNamesProjection)
)
val eventStore = InMemoryEventStore

// 100% generic server
fun main() {
    val commandGateway = CommandGateway(eventStore, commandToConstructor)
    embeddedServer(Netty, 8080) {
        install(ContentNegotiation) {
            jackson {}
        }
        routing {
            post("/command/{command}") {
                val command = command(call.parameters["command"]!!)
                when (command) {
                    is Left -> call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = command.error ?: "Something went wrong"
                    )
                    is Right -> {
                        val statusCode = commandGateway.dispatch(command.value)
                        val message: Any = when (statusCode) {
                            HttpStatusCode.Created, HttpStatusCode.OK  -> {
                                val (created, updated) = eventStore.eventsFor(command.value.aggregateId)
                                val events = listOf(created) + updated
                                events.map { EventData(it::class.simpleName!!, it) }
                            }
                            else -> command

                        }
                        call.respond(
                            status = statusCode,
                            message = message
                        )
                    }
                }
            }
        }
    }.start(wait = true)
}

data class EventData(val type: String, val data: Event)
data class BadData(val field: String, val invalidValue: String?)

@Suppress("UNCHECKED_CAST")
private suspend fun PipelineContext<Unit, ApplicationCall>.command(commandClassName: String): Either<BadData?, Command> {
    return try {
        Right(call.receive(Class.forName(commandClassName).kotlin as KClass<Command>))
    } catch (e: ClassNotFoundException) {
        Left(BadData("commandClassName", commandClassName))
    } catch (e: ClassCastException) {
        Left(BadData("commandClassType", commandClassName))
    } catch (e: MismatchedInputException) {
        val field = e.path.first().fieldName
        val value = (e.processor as ReaderBasedJsonParser).text
        Left(BadData(field, value))
    } catch (e: MissingKotlinParameterException) {
        val field = e.path.first().fieldName
        Left(BadData(field, null))
    } catch (e: Exception) {
        logStacktrace(e)
        Left(null)
    }
}

fun logStacktrace(e: Exception) {
    val sw = StringWriter()
    e.printStackTrace(PrintWriter(sw))
    print(sw.toString())
}