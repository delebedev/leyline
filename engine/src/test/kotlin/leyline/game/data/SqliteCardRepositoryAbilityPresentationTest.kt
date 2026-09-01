package leyline.game.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import org.jetbrains.exposed.v1.jdbc.Database
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.io.File
import java.sql.DriverManager

class SqliteCardRepositoryAbilityPresentationTest :
    FunSpec({
        tags(UnitTag)

        fun withRepository(block: (SqliteCardRepository, String) -> Unit) {
            val dbFile = File.createTempFile("ability-presentation", ".sqlite").apply { deleteOnExit() }
            val url = "jdbc:sqlite:${dbFile.absolutePath}"
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("CREATE TABLE Localizations_enUS(LocId INT, Formatted INT, Loc TEXT);")
                    statement.executeUpdate(
                        """
                        CREATE TABLE Abilities(
                          Id INT PRIMARY KEY, BaseId INT DEFAULT 0, TextId INT DEFAULT 0,
                          OldSchoolManaText TEXT, ModalChildIds TEXT,
                          Category INT DEFAULT 0, SubCategory INT DEFAULT 0
                        );
                        """.trimIndent(),
                    )
                }
            }
            block(SqliteCardRepository(Database.connect(url, "org.sqlite.JDBC")), url)
        }

        test("ability presentation resolves localized text and mana cost") {
            withRepository { repository, url ->
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("INSERT INTO Localizations_enUS VALUES (21, 1, 'Choose one');")
                        statement.executeUpdate("INSERT INTO Abilities VALUES (7, 0, 21, 'o2oU', NULL, 8, 0);")
                    }
                }

                repository.findAbilityPresentation(7) shouldBe
                    AbilityPresentation(
                        text = "Choose one",
                        manaCost = listOf(ManaColor.Generic to 2, ManaColor.Blue_afc9 to 1),
                    )
            }
        }

        test("ability presentation returns null when either row is missing") {
            withRepository { repository, url ->
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("INSERT INTO Abilities VALUES (7, 0, 21, NULL, NULL, 8, 0);")
                    }
                }

                repository.findAbilityPresentation(7) shouldBe null
                repository.findAbilityPresentation(8) shouldBe null
            }
        }
    })
