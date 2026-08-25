package io.github.kemko.grimmoryuploader.upload.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "upload_jobs")
public class RoomUploadJobEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public String sourceUri;
    public String sourceUrl;
    public String stagedPath;
    public String displayName;
    public String mimeType;
    public String state;
    public String serverUrl;
    public long libraryId;
    public long pathId;
    public boolean recompressEpub;
    public String failureReason;
    public long createdAt;
    public long updatedAt;
}
