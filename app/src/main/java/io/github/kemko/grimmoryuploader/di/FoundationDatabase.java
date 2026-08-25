package io.github.kemko.grimmoryuploader.di;

import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.RoomDatabase;

@Database(entities = {FoundationRecord.class}, version = 1, exportSchema = false)
public abstract class FoundationDatabase extends RoomDatabase {
}

@Entity(tableName = "foundation_records")
class FoundationRecord {
    @PrimaryKey
    public int id;
}
