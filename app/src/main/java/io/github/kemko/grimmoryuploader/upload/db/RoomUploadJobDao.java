package io.github.kemko.grimmoryuploader.upload.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface RoomUploadJobDao {
    @Insert
    long insert(RoomUploadJobEntity job);

    @Update
    void update(RoomUploadJobEntity job);

    @Query("SELECT * FROM upload_jobs WHERE id = :id")
    RoomUploadJobEntity find(long id);

    @Query("SELECT * FROM upload_jobs WHERE serverUrl = :serverUrl")
    List<RoomUploadJobEntity> byServer(String serverUrl);

    @Query("DELETE FROM upload_jobs WHERE id = :id")
    void delete(long id);

    @Query("SELECT * FROM upload_jobs WHERE state IN ('STAGED', 'AWAITING_AUTH', 'QUEUED', 'RUNNING') ORDER BY createdAt")
    List<RoomUploadJobEntity> pending();
}
