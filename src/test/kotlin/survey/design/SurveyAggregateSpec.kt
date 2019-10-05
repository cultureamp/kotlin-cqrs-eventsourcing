package survey.design

import eventsourcing.Left
import eventsourcing.ReadOnlyDatabase
import eventsourcing.Right
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.lang.IllegalStateException
import java.util.*

class SurveyAggregateSpec : ShouldSpec({
	val namedAt = Date()
	val createdAt = Date()
	val deletedAt = Date()
	val restoredAt = Date()
	val name = mapOf(Locale.en to "name")
	val accountId = UUID.randomUUID()
	val aggregateId = UUID.randomUUID()
	val surveyCaptureLayoutAggregateId = UUID.randomUUID()

	"Created" {
		should("created a new Survey") {
			SurveyAggregate.created(
				Created(aggregateId, name, accountId, createdAt)
			) shouldBe
				SurveyAggregate(aggregateId, name, accountId, deleted = false)
		}
	}

	"Snapshot" {
		should("created a Survey at a point in time") {
			SurveyAggregate.created(
				Snapshot(aggregateId, name, accountId, true, createdAt)
			) shouldBe
				SurveyAggregate(aggregateId, name, accountId, true)
		}
	}

	"Renamed" {
		should("updated the name for one Locale") {
			SurveyAggregate.created(
				Created(aggregateId, name, accountId, createdAt)
			).updated(
				Renamed(aggregateId, "rename", Locale.en, Date())
			).name.getValue(Locale.en) shouldBe
				"rename"
		}
	}

	"Deleted" {
		should("set the deleted flag from true") {
			SurveyAggregate.created(
				Created(aggregateId, name, accountId, createdAt)
			).updated(
				Deleted(aggregateId, Date())
			).deleted shouldBe
				true
		}
	}

	"Restored" {
		should("set the deleted flag from true") {
			SurveyAggregate.created(
				Created(aggregateId, name, accountId, createdAt)
			).updated(
				Deleted(aggregateId, Date())
			).updated(
				Restored(aggregateId, Date())
			).deleted shouldBe
				false
		}
	}

	"CreateSurvey" {
		should("return Created event") {
			SurveyAggregate.create(
				NameTaken(false), CreateSurvey(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt)
			) shouldBe
				Right(Created(aggregateId, name, accountId, createdAt))
		}

		"when Survey name already taken" {
			should("fail with SurveyNameNotUnique") {
				SurveyAggregate.create(
					NameTaken(true), CreateSurvey(aggregateId, surveyCaptureLayoutAggregateId, name, accountId, createdAt)
				) shouldBe
					Left(SurveyNameNotUnique)
			}
		}
	}

	"Rename" {
		should("return Renamed event") {
			SurveyAggregate.created(
				Created(aggregateId, name, accountId, createdAt)
			).update(
				NameTaken(false), Rename(aggregateId, "rename", Locale.en, namedAt)
			) shouldBe
				Right.list(Renamed(aggregateId, "rename", Locale.en, namedAt))
		}

		"when the name is the same" {
			should("fail with SurveyNameNotUnique") {
				SurveyAggregate.created(
					Created(aggregateId, name, accountId, createdAt)
				).update(
					NameTaken(true), Rename(aggregateId, "rename", Locale.en, namedAt)
				) shouldBe
					Left(SurveyNameNotUnique)
			}
		}

		"when the name is the same for a different locale" {
			should("return Renamed event") {
				SurveyAggregate.created(
					Created(aggregateId, name, accountId, createdAt)
				).update(
					NameTaken(false), Rename(aggregateId, name.getValue(Locale.en), Locale.de, namedAt)
				) shouldBe
					Right.list(Renamed(aggregateId, name.getValue(Locale.en), Locale.de, namedAt))
			}
		}
	}

	"Delete" {
		should("return Deleted event") {
			SurveyAggregate.created(
				Created(aggregateId, name, accountId, createdAt)
			).update(
				Stub(), Delete(aggregateId, deletedAt)
			) shouldBe
				Right.list(Deleted(aggregateId, deletedAt))
		}

		"when Survey already deleted" {
			should("fail with AlreadyDeleted") {
				SurveyAggregate.created(
					Created(aggregateId, name, accountId, createdAt)
				).updated(
					Deleted(aggregateId, deletedAt)
				).update(
					Stub(), Delete(aggregateId, deletedAt)
				) shouldBe
					Left(AlreadyDeleted)
			}
		}
	}

	"Restore" {
		should("fail with NotDeleted") {
			SurveyAggregate.created(
				Created(aggregateId, name, accountId, createdAt)
			).update(
				Stub(), Restore(aggregateId, restoredAt)
			) shouldBe
				Left(NotDeleted)
		}

		"when Survey already deleted" {
			should("return Restored event") {
				SurveyAggregate.created(
					Created(aggregateId, name, accountId, createdAt)
				).updated(
					Deleted(aggregateId, deletedAt)
				).update(
					Stub(), Restore(aggregateId, restoredAt)
				) shouldBe
					Right.list(Restored(aggregateId, restoredAt))
			}
		}
	}
})

class Stub : SurveyNamesProjection(StubReadOnlyDatabase()) {
	override fun nameExistsFor(accountId: UUID, name: String, locale: Locale): Boolean {
		throw IllegalStateException("Should not have been called")
	}
}

class NameTaken(val taken: Boolean) : SurveyNamesProjection(StubReadOnlyDatabase()) {
	override fun nameExistsFor(accountId: UUID, name: String, locale: Locale) = taken
}

class StubReadOnlyDatabase : ReadOnlyDatabase
