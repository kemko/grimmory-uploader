package io.github.kemko.grimmoryuploader.upload.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {RoomUploadJobEntity.class}, version = 1, exportSchema = false)
public abstract class RoomUploadDatabase extends RoomDatabase {
    public abstract RoomUploadJobDao jobs();
}
