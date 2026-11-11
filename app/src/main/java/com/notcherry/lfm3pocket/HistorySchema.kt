package com.notcherry.lfm3pocket

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName="conversations")
data class HistoryConversation(
    @PrimaryKey val id:String,
    val title:String,
    val createdAt:Long,
    val updatedAt:Long,
) {
    init { require(id.isNotBlank() && createdAt>=0 && updatedAt>=0) }
}

@Entity(tableName="messages",foreignKeys=[ForeignKey(entity=HistoryConversation::class,
    parentColumns=["id"],childColumns=["conversationId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["conversationId","sequence"],unique=true),Index(value=["turnId"],unique=true)])
data class HistoryMessage @JvmOverloads constructor(
    @PrimaryKey val id:String,
    val conversationId:String,
    val sequence:Long,
    val role:String,
    val text:String,
    val speechTranscript:String?,
    val createdAt:Long,
    val turnId:String?=null,
    val turnStatus:String="COMPLETED",
) {
    init {
        require(id.isNotBlank() && conversationId.isNotBlank() && sequence>=0 && createdAt>=0)
        require(role in setOf("user","assistant","system","tool"))
    }
}

@Entity(tableName="tool_outcomes",foreignKeys=[ForeignKey(entity=HistoryMessage::class,
    parentColumns=["id"],childColumns=["messageId"],onDelete=ForeignKey.CASCADE)],
    indices=[Index("messageId"),Index(value=["requestId"],unique=true)])
data class HistoryToolOutcome(
    @PrimaryKey val id:String,
    val messageId:String,
    val requestId:String,
    val tool:String,
    val argumentsJson:String,
    val succeeded:Boolean,
    val status:String,
    val resultText:String,
    val completedAt:Long,
) {
    init { require(id.isNotBlank() && messageId.isNotBlank() && requestId.isNotBlank() && completedAt>=0) }
}

@Dao
interface HistoryDao {
    @Insert fun insertConversation(value:HistoryConversation)
    @Query("SELECT * FROM conversations WHERE id=:id LIMIT 1") fun conversation(id:String):HistoryConversation?
    @Query("UPDATE conversations SET updatedAt=MAX(updatedAt,:time) WHERE id=:id") fun touchConversation(id:String,time:Long)
    @Insert fun insertMessage(value:HistoryMessage)
    @Query("SELECT MAX(sequence) FROM messages WHERE conversationId=:id") fun lastSequence(id:String):Long?
    @Query("SELECT * FROM messages WHERE turnId=:id LIMIT 1") fun messageForTurn(id:String):HistoryMessage?
    @Query("SELECT * FROM messages WHERE conversationId=:id AND role='assistant' ORDER BY sequence DESC LIMIT 1") fun latestAssistant(id:String):HistoryMessage?
    @Insert fun insertToolOutcome(value:HistoryToolOutcome)
    @Query("SELECT * FROM tool_outcomes WHERE requestId=:id LIMIT 1") fun toolOutcome(id:String):HistoryToolOutcome?
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC,id ASC") fun conversations():List<HistoryConversation>
    @Query("SELECT * FROM messages WHERE conversationId=:id ORDER BY sequence ASC") fun messages(id:String):List<HistoryMessage>
    @Query("DELETE FROM conversations WHERE id=:id") fun deleteConversation(id:String):Int
    @Query("SELECT tool_outcomes.* FROM tool_outcomes INNER JOIN messages ON messages.id=tool_outcomes.messageId WHERE messages.conversationId=:id ORDER BY completedAt ASC,tool_outcomes.id ASC")
    fun toolOutcomes(id:String):List<HistoryToolOutcome>
}

@Database(entities=[HistoryConversation::class,HistoryMessage::class,HistoryToolOutcome::class],version=3,exportSchema=true)
abstract class HistoryDatabase:RoomDatabase() {
    abstract fun history():HistoryDao
    companion object {
        const val NAME="conversations.db"
        @Volatile private var instance:HistoryDatabase?=null
        @JvmField val MIGRATION_1_2:Migration=object:Migration(1,2){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS `tool_outcomes` (`id` TEXT NOT NULL, `messageId` TEXT NOT NULL, `requestId` TEXT NOT NULL, `tool` TEXT NOT NULL, `argumentsJson` TEXT NOT NULL, `succeeded` INTEGER NOT NULL, `status` TEXT NOT NULL, `resultText` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_outcomes_messageId` ON `tool_outcomes` (`messageId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_outcomes_requestId` ON `tool_outcomes` (`requestId`)")
            }
        }
        @JvmField val MIGRATION_2_3:Migration=object:Migration(2,3){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `turnId` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `turnStatus` TEXT NOT NULL DEFAULT 'COMPLETED'")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_turnId` ON `messages` (`turnId`)")
            }
        }
        @JvmStatic fun get(context:Context):HistoryDatabase=instance ?: synchronized(this){
            instance ?: Room.databaseBuilder(context.applicationContext,HistoryDatabase::class.java,NAME)
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3).build().also { instance=it }
        }
    }
}
